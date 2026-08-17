# Pitfalls Research: Adding a Nonprod/Staging Environment to a Resource-Constrained Single-VM Production Deployment

**Domain:** Infrastructure — colocating a second (staging) Docker Compose stack alongside a live, traffic-serving production stack on one Netcup VPS, with shared-provider dependencies (Neon Postgres, Docker Hub, GitHub Actions, DuckDNS)
**Researched:** 2026-08-17
**Confidence:** MEDIUM overall (HIGH for findings grounded in this project's own already-documented incidents; MEDIUM/LOW for general Docker/GitHub Actions/Neon findings — see per-pitfall notes and Sources)

**Calibration baseline** (from `docs/INFRA_RUNBOOK.md`, do not re-derive): this exact host already produced one real cgroup-accounting incident — setting Redpanda's Docker `mem_limit` numerically equal to its own internal `--memory 2G` flag broke every container restart (`insufficient physical memory: needed 2147483648 available 2078277632`), because a cgroup limit exactly equal to the process's own requested usable memory leaves no room for cgroup accounting overhead. Fixed with `mem_limit: 2200m` (~150MiB headroom). This project has also already hit a Compose **project-name collision** for real: moving `docker-compose.prod.yml` between directories silently changed its default project name from `root` to `kanban-board-backend`, which would have started a second, unrelated set of containers and fresh empty volumes had it not been caught by `docker compose ps` returning nothing for containers that were demonstrably running elsewhere — fixed by pinning `name: kanban-board-backend` in the file itself. Both incidents are the calibration bar for "genuinely dangerous on this specific setup": a config value that *looks* safe in YAML but is wrong by a small, easy-to-miss margin, discovered only by trying the obvious value and watching it break.

**Current measured resource shape** (05-04 Task 3, real 54-request burst): host is 4 vCPU / 7.8GiB RAM. `app` `mem_limit: 3g` (actual usage ~458–471MiB, ~15% of cap). `redpanda` `mem_limit: 2200m` / `--memory 2G` (actual usage flat at 347.8MiB, ~16% of cap, CPU peaks 7–9% of one `--smp 1`-pinned core). `caddy` ~20MiB, negligible CPU. Real worst-case prod budget: `3G + 2200m ≈ 5.15G`, leaving **~2.65GiB** of the 7.8GiB host for Caddy + OS + anything else — this is the actual number a colocated nonprod stack has to fit inside, not the advertised 8GB.

---

## Critical Pitfalls

### Pitfall 1: A copy-pasted deploy job silently overwrites the live production stack instead of creating a second one

**What goes wrong:**
`.github/workflows/deploy.yml`'s `deploy-to-netcup` job SCPs `docker-compose.prod.yml`/`Caddyfile` to a fixed path (`/opt/deploy/kanban-board-backend/`) on a fixed host (`secrets.NETCUP_HOST`) and runs `docker compose --env-file ./.env.prod -f docker-compose.prod.yml up -d` there. The most natural way to add a nonprod deploy job is to copy this job. If the copy is not changed on **every** one of these axes — target directory, Compose project `name:`, `.env` file name, image tag source, and the `caddy` service's published `80:443` ports — `docker compose up -d` recognizes the *same* project (via the pinned `name: kanban-board-backend` this project already had to add after its own project-name incident) and converges the *existing* prod containers onto whatever the nonprod branch/image happens to be, rather than starting a separate stack. This is strictly worse than the project-name incident already logged in `INFRA_RUNBOOK.md`: that one silently *forked* into an orphaned second project; this one silently *merges into and mutates* the live one.

**Why it happens:**
This project's own deploy job is a template that "looks parametrized" (host/user/key are already secrets, `IMAGE_TAG` is already an env var) but every other identity-defining value — directory, Compose project name, port bindings — is a hardcoded literal in the job body, not a variable. Copy-paste-and-rename workflows naturally focus on the values that are already parameters and miss the ones that are hardcoded, exactly the same failure shape as the directory-move incident that already happened once in this repo.

**How to avoid:**
Give the nonprod stack its own dedicated identity on every axis simultaneously, not just one: a distinct target directory (e.g. `/opt/deploy/kanban-board-backend-nonprod/`), a distinct pinned `name:` in a nonprod-specific Compose file (not a copy of `docker-compose.prod.yml` with only the `name:` line changed — see Pitfall 2), a distinct `--env-file` (never `.env.prod` read or written by the nonprod job), and no `80:443` host-port publication for the nonprod `caddy` service pointed at the same ports prod already owns (see Pitfall 5 on Caddy — one Caddy instance handling both vhosts is the safer default specifically to avoid this). Add a CI-time assertion (a cheap grep/diff step before the SSH step) that the nonprod job's compose file's `name:` value is never `kanban-board-backend`.

**Warning signs:**
`docker compose ps` from the nonprod deploy path shows the prod containers' names/images. `docker inspect`'s `com.docker.compose.project` label on a container you expect to be nonprod says `kanban-board-backend` (the prod project name). A nonprod deploy run's logs show `Recreating` for `app`/`caddy`/`redpanda` container names that match prod's existing ones rather than `Creating` fresh ones.

**Phase to address:**
Nonprod/staging deploy target provisioning phase — the Compose manifest and CI job for nonprod should be designed with this cross-check as an explicit acceptance criterion from the start, not discovered by incident like the two precedents above.

---

### Pitfall 2: Docker Compose project-name, network-name, and volume-name collisions between the two stacks

**What goes wrong:**
Even with Pitfall 1's directory/host mistakes avoided, a *second, genuinely separate* Compose file for nonprod can still collide with prod if it reuses the same `name:` value, the same implicit default network name Compose derives from the project name (`<project>_default`), or the same *volume* names (`redpanda-data`, `caddy-data`, `caddy-config` in this project's own file) without qualifying them. Two stacks that each declare a plain `redpanda-data:` volume under different but colliding project names would each get their own namespaced volume (safe) — but two stacks that *share* a project name (accidentally, per Pitfall 1) or that both bind-mount instead of using named volumes would not.

**Why it happens:**
Compose's default namespacing (project name prefixes every network/volume it creates) is usually invisible and "just works" until two stacks exist on one host, at which point every implicit default becomes a collision surface. This project has already independently confirmed the project-name half of this in production (see calibration note above).

**How to avoid:**
Nonprod's Compose file needs its own explicit top-level `name:` (distinct string, e.g. `kanban-board-backend-nonprod`), which automatically namespaces its default network and any unqualified volume names under that project — verify this by `docker network ls`/`docker volume ls` showing two clearly distinct sets of names, not by reading the YAML. Do not rely on Compose's implicit per-project namespacing alone without the explicit `name:` pin — this project's own incident happened specifically because the *implicit* default (CWD-basename-derived) is fragile to directory moves, and a nonprod file is a second, brand-new place for that same fragility to bite.

**Warning signs:**
`docker network ls` / `docker volume ls` show fewer distinct entries than expected (two stacks, but only one set of Compose-managed resources). A nonprod container can resolve and reach a prod service by its Compose service name (e.g. nonprod `app` successfully connects to `redpanda:19092` and gets *prod's* broker) — Compose's per-project network isolation means this should be structurally impossible if the two stacks are genuinely on separate projects/networks; if it happens, the projects merged.

**Phase to address:**
Same phase as Pitfall 1 — this is the general form of that specific failure mode and should be verified together.

---

### Pitfall 3: Resource-cap arithmetic assumes prod's numbers can be halved, without re-measuring against Redpanda's real minimum footprint

**What goes wrong:**
The natural instinct for "a resource-shrunk nonprod stack" is to take prod's measured caps (`app: 3g`, `redpanda: 2200m`/`--memory 2G`) and scale them down proportionally (e.g. `app: 1g`, `redpanda: 1g`/`--memory 800M`) to fit the ~2.65GiB of real headroom this host has left. This is exactly the same category of mistake this project already hit once: Redpanda's Seastar allocator has a real, measured minimum viable footprint on this host (2G internal request needed ~2.15G of actual cgroup ceiling to boot at all) — there is no evidence that halving `--memory` again to something like 800M–1G would boot successfully rather than repeating the exact `insufficient physical memory` failure, just at a different threshold. Memory is a hard hard cap in cgroups (a process that exceeds it is OOM-killed, not throttled — see Pitfall 4), so guessing wrong here doesn't degrade gracefully, it fails every container start/restart, potentially discovered only during a CI deploy rather than locally.

**Why it happens:**
Budget arithmetic against advertised host totals ("we have 7.8GiB, prod uses 5.15G, so nonprod gets the remaining 2.65G split across app+redpanda+overhead") looks conservative and safe on paper but treats memory caps as infinitely divisible, when in practice a stateful broker's startup allocator has a real floor that arithmetic alone can't discover — only a live measurement can, exactly as 05-04 Task 3's "measured, not asserted" methodology already demonstrated for prod.

**How to avoid:**
Treat nonprod Redpanda sizing as its own measurement task, not a fraction of prod's already-measured numbers: start with a value at or above prod's known-working `--memory 2G` (do not assume less works), reduce in small increments with a live restart-and-observe-`(healthy)` check after each step (exactly the "try the obvious value first and watch it break" method that found the original bug), and stop at the first value that reliably reaches `(healthy)` within its `start_period`, not at an arithmetically "nice" fraction. If no value under ~1–1.2GiB internal `--memory` reliably boots, that is itself the finding that determines whether colocation is even viable for the Redpanda half of nonprod, independent of everything else in this document — budget the research/provisioning phase time for this to come back negative.

**Warning signs:**
`docker compose up -d` for the nonprod stack succeeds once but fails on the next restart/redeploy (the exact symptom the original prod incident produced — it broke "every container restart," not the first boot). `docker logs` on nonprod `redpanda` showing `Could not initialize seastar: std::runtime_error (insufficient physical memory...)`. `docker stats` showing a nonprod `redpanda`'s `MEM USAGE / LIMIT` sitting suspiciously close to 100% at idle, before any real traffic.

**Phase to address:**
Nonprod/staging deploy target provisioning phase, specifically the sizing sub-task — should explicitly budget time for a live measure-then-correct pass on the same footing as 05-04 Task 3, not a one-shot value chosen from arithmetic alone.

---

### Pitfall 4: cgroup mem_limit/cpus are hard per-container caps, not a host-level reservation — aggregate overcommit is still possible even with "safe-looking" YAML

**What goes wrong:**
Setting `mem_limit`/`cpus` on both stacks' services is necessary but not sufficient for isolation. Two separate, real mechanisms can still let one stack affect the other even when every service has an explicit cap:
1. **Overcommit**: nothing in Compose or Docker prevents the *sum* of every service's `mem_limit` across both stacks from exceeding the host's actual RAM — each cap only bounds that one container's own cgroup, not what's left over for everyone else. If prod (5.15G) + a naively-sized nonprod stack's caps sum to more than ~7.3–7.5GiB (leaving room for Caddy + OS), both stacks can independently stay under their own individual limits while the host itself runs out of memory, at which point the kernel's host-level OOM killer picks a victim by its own heuristics — which is not guaranteed to be the nonprod process, and could take down a fully-healthy prod container instead.
2. **Silent non-enforcement**: `mem_limit` is only enforced if the Docker daemon's cgroup driver actually has the memory controller available — `docker info` reports `WARNING: No memory limit support` when it doesn't (uncommon on a standard Debian 13 + Docker 29.7.2 stack like this one, but a real gap on some minimal/custom kernels or if the daemon config ever changes), at which point every `mem_limit` line in the YAML is silently a no-op rather than an error.

**Why it happens:**
`mem_limit: Xg` in a Compose file reads like a declarative reservation ("this container gets X, and nothing more, and everyone else is unaffected"), but the cgroup mechanism it maps to is a one-sided ceiling on a single cgroup, not a partition of the whole host's memory the way a hypervisor's VM sizing would be. There is no Compose-level primitive that reserves memory *away* from other containers ahead of time the way `mem_limit` reserves it *from* one container's own overreach.

**How to avoid:**
Do the sum, not per-service: `(prod app + prod redpanda) + (nonprod app + nonprod redpanda) + Caddy(s) + OS reserve` must stay comfortably under the host's measured 7.8GiB, with real headroom, not a value that only works if every container simultaneously sits at its *idle* usage rather than its *cap*. Since this project's own measurement showed prod running at only ~15–16% of its generous caps under real burst load, consider whether prod's caps themselves have slack to give back (re-measure, don't just assume) before assuming nonprod needs the full 2.65GiB remainder. Confirm `docker info | grep -A2 WARNING` shows no memory/swap-limit warnings on this exact host before trusting any `mem_limit` value at all.

**Warning signs:**
`docker stats` shows aggregate memory usage across all six-plus containers (three prod, three-plus nonprod) approaching the host's real 7.8GiB total even though no individual container is at its own cap. The kernel logs an OOM-kill event (`dmesg`/`journalctl -k | grep -i "killed process"`) naming a prod process while prod's own `mem_limit` was never exceeded — the tell that this was a host-level, not container-level, OOM.

**Phase to address:**
Nonprod/staging deploy target provisioning phase — the sizing sub-task's acceptance criterion should include a live burst test with **both stacks running simultaneously** (not just nonprod in isolation), mirroring 05-04 Task 3's methodology, since that is the only way to observe aggregate contention rather than each stack's own idle/burst numbers separately.

---

### Pitfall 5: No GitHub Environments exist today — a new nonprod CI job inherits every production secret by default

**What goes wrong:**
`.github/workflows/deploy.yml` (read directly, 2026-08-17) uses **zero** `environment:` keys anywhere — every secret reference (`secrets.NETCUP_HOST`, `secrets.NETCUP_SSH_KEY`, `secrets.DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS`, `secrets.DOCKERHUB_TOKEN`) is a plain repository-level secret, visible to any job in any workflow in this repository with no boundary at all. This means the *starting point* for adding nonprod CI is "there is currently no secret isolation to accidentally break" — a new nonprod deploy job added without deliberately introducing GitHub Environments will, by default, have the exact same blast-radius access to the production Neon database credentials and the production SSH deploy key as the existing prod job, whether or not it ever intends to use them.

**Why it happens:**
Single-environment projects never need `environment:` scoping, so it's easy for it to simply never have been introduced — this isn't a regression, it's the natural state of a repo that has only ever deployed to one place. Adding a second target is precisely the point at which that absence becomes a real risk rather than a non-issue.

**How to avoid:**
Before writing the nonprod deploy job, introduce GitHub Environments (`Settings > Environments`) for `production` and `staging`, move every existing prod secret from repository scope into the `production` environment (repo-level secrets remain visible everywhere unless removed, so this is a genuine migration, not just an addition), create parallel `staging`-scoped secrets for the nonprod Neon branch/SSH target/etc., and make every job that touches infrastructure declare `environment: production` or `environment: staging` explicitly. Only after that migration does a `staging`-scoped job structurally lack access to `production`-scoped secrets. Consider requiring manual approval on the `production` environment as a second, independent backstop against the exact copy-paste mistake in Pitfall 1 — if the nonprod job accidentally references `environment: production`, an approval gate at least surfaces that before it runs, rather than executing silently.

**Warning signs:**
`gh secret list --repo <repo>` shows only repository-level secrets, no environment-scoped ones (this is the current, verified state as of 2026-08-17 — ten repo secrets, no environments). A new nonprod workflow file references `secrets.DB_HOST` (the prod-named secret, per the existing deviation already recorded in `INFRA_RUNBOOK.md` reusing AWS-era names) instead of a new, distinctly-named nonprod secret — the easiest possible copy-paste-and-forget-to-rename mistake, and one this repo has explicit precedent for (`DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` already reused AWS-era secret *names* deliberately once).

**Phase to address:**
CI job deploying to nonprod phase — should be scoped to include the GitHub Environments migration as a prerequisite step, not an afterthought once the nonprod job already exists and works against the wrong secrets.

---

### Pitfall 6: A Neon branch mix-up points a nonprod job at the production database

**What goes wrong:**
Neon branches are fully isolated at the *connection-string* level (each branch has its own host/credentials) — this is good (a leaked nonprod credential cannot silently read/write prod data through some shared endpoint), but it also means the failure mode isn't "the nonprod job sees a filtered view of prod data," it's "the nonprod job silently and completely operates against the wrong database," including running Flyway migrations against it. Given this project's existing `flyway-verify` CI job pattern (a direct-connection Flyway CLI run gated on a `DB_HOST` env var pulled straight from a secret, with an explicit guard only against the *pooled* endpoint, not against the *wrong branch*), a nonprod Flyway-verify job that reuses `secrets.DB_HOST` instead of a new `secrets.DB_HOST_STAGING` would run real schema migrations against the live production database from a job whose entire purpose was to validate a nonprod-only change.

**Why it happens:**
This project's existing Flyway guard (`if [[ "$DB_HOST" == *"-pooler"* ]]`) proves the team already anticipated *one* class of wrong-endpoint mistake (pooled vs. direct) and built a real, working guard for it — but that guard has no opinion about *which branch* a direct-endpoint host string points to, because both prod's direct host and a hypothetical staging branch's direct host would pass the same pooler-marker check equally.

**How to avoid:**
Give the nonprod Neon branch's connection details their own distinctly-named secrets (`DB_HOST_STAGING` etc., not a reused prod name), and add a second guard alongside the existing pooler check — Neon branch hostnames are unique per branch (e.g. `ep-<random>-<random>...`), so a guard can pin the expected prod hostname as a known constant and fail loudly if a job that declares itself nonprod ever resolves to that exact string, mirroring the existing guard's own pattern rather than inventing a new one. Since branches also incur their own storage/compute billing, add automated cleanup (a scheduled or CI-triggered branch-deletion step) for any nonprod branch created per-PR/per-run rather than one long-lived staging branch, if the roadmap goes that route — an unbounded number of undeleted branches is a silent, creeping cost the way Docker Hub's untended tag accumulation already was for this project before `cleanup-old-images` was fixed (see Pitfall 8).

**Warning signs:**
A "nonprod-only" schema change appears in production's `flyway_schema_history` table. A nonprod E2E test run (Playwright, per the milestone's stated purpose) writes or deletes real production board/task data. The nonprod job's resolved `DB_HOST` value (never logged in full per this project's existing security convention) happens to match the exact hostname already recorded as production's in `INFRA_RUNBOOK.md`.

**Phase to address:**
Nonprod/staging deploy target provisioning phase (branch creation) and CI job deploying to nonprod phase (the guard) — both need this addressed, since the branch must exist correctly-scoped before the CI job can be pointed at it correctly.

---

### Pitfall 7: Sharing one Redpanda broker between prod and nonprod bleeds topics, consumer groups, and schema-registry subjects across the environment boundary

**What goes wrong:**
If nonprod colocates onto the *same* Redpanda broker as prod (rather than running its own, to save the ~2.2GiB a second broker instance would cost — see Pitfall 3/4's tight budget), there is no environment-level namespace primitive in the Kafka protocol or in Redpanda's own schema registry to keep the two apart automatically. This project's `RecordNameStrategy`-based subject naming (`com.vrudenko.kanban_board.event.avro.Avro*Event`, confirmed live as 14 registered subjects) and its topic/consumer-group names would be **identical** between a nonprod deployment of the same application and prod unless every one of those names is deliberately re-prefixed for nonprod. Concretely: (a) if nonprod's consumer uses the same `@KafkaListener` group id as prod against the same topic, Kafka's group-coordination protocol treats them as *one* logical consumer group sharing partition assignment and committed offsets — a nonprod consumer instance could steal partitions mid-rebalance from prod's real consumer, or silently advance prod's committed offset, causing production activity-log entries to be skipped; (b) if nonprod publishes with the same Avro subject name, it registers against and is bound by the *same* BACKWARD-compatibility contract as prod's schema, meaning a nonprod-only, intentionally-breaking schema experiment (exactly the kind of thing a staging environment exists to safely try) either gets rejected by the registry's compatibility check meant to protect prod, or — worse — succeeds and silently becomes a new version prod's own producer must now also remain compatible with.

**Why it happens:**
The existing single-environment application code has zero notion of "which environment am I" baked into its topic/group-id/subject naming — every one of those strings is currently a fixed literal in application config, not templated by environment, because there has only ever been one environment.

**How to avoid:**
If sharing one broker (the resource-cheaper option under this host's tight budget): prefix every topic name, every consumer group id, and — since this project uses `RecordNameStrategy`, which derives the subject name from the Avro record's fully-qualified class name, not the topic — either accept that schema subjects are inherently shared across environments running the same code (meaning nonprod schema experiments cannot diverge from prod's compatibility contract without a second registry) or switch strategy/config for nonprod specifically. The lower-risk default, given this project's tight resource budget already makes a full second broker questionable: treat topic/group prefixing as mandatory (cheap, config-only), and treat "nonprod needs to intentionally break schema compatibility" as an explicit non-goal for a colocated broker — if that capability is ever needed, that is the actual trigger for provisioning nonprod's own broker instance (or a second small VPS, per PROJECT.md's already-open sizing question) rather than trying to retrofit registry isolation onto a shared one.

**Warning signs:**
Production's `GET /boards/{boardId}/activity` feed shows gaps or an activity event that doesn't correspond to any real production mutation (a bled-through nonprod event). Redpanda's `rpk group describe <group>` for a prod consumer group shows a nonprod-tagged consumer instance as a live member. `rpk registry subject list` (the exact command this project's own runbook already uses for verification) shows fewer than the expected count for one environment because both environments' producers registered against the same subject.

**Phase to address:**
Nonprod/staging deploy target provisioning phase — topic/group/subject naming strategy needs to be decided (and, if shared-broker, implemented as environment-prefixed config) before any nonprod producer/consumer code path runs for the first time, since retrofitting a naming scheme after both environments have already produced/consumed under colliding names means reconciling already-bled data.

---

### Pitfall 8: A second Caddy vhost risks ACME rate-limit exhaustion and, more subtly, CORS/cookie scope leakage across a shared public-suffix domain

**What goes wrong:**
Two related but distinct risks from adding a nonprod subdomain:
1. **Cert issuance/renewal**: Let's Encrypt allows 50 certs per registered domain per week and Caddy self-throttles to 10 ACME attempts per account per 10 seconds — a single new vhost for a staging subdomain is normally far under either limit (this project's own prod cert issuance succeeded on the first attempt in ~6 seconds), but a *misconfigured* nonprod Caddy service that gets recreated repeatedly without a persistent volume for its ACME account/cert state (exactly the failure mode this project's own `docker-compose.prod.yml` comment already warns about for `caddy-data`/`caddy-config`) would re-request a cert on every recreation, and a rate-limit ban, once hit, lasts roughly a week — which would also affect prod if both vhosts share one Caddy instance/account.
2. **Cross-subdomain scope on a shared public suffix**: this project's domain (`kanban-board-rud-vlad-473.duckdns.org`) sits under DuckDNS's `duckdns.org`, a domain shared by many unrelated users' subdomains. A nonprod subdomain would almost certainly also be a sibling `*.duckdns.org` name. If either the session cookie's `Domain` attribute or the CORS allowed-origins list is ever set broadly (e.g. an explicit `Domain=.duckdns.org` cookie attribute, or a CORS config that matches `*.duckdns.org` instead of the two specific hostnames), that scope leaks to every other unrelated tenant's DuckDNS subdomain, not just this project's own two environments — a materially worse mistake than a normal same-owner staging/prod cookie leak, because the "other side" of the leak isn't even under this project's control.

**Why it happens:**
Session cookies default to host-only scope (no `Domain` attribute) unless explicitly widened, and CORS origin lists are usually hand-maintained strings — both are easy to "simplify" into a wildcard once a second, structurally-similar hostname exists, especially since the two hostnames share a literal suffix that makes a wildcard look like the obviously-correct generalization.

**How to avoid:**
Keep the session cookie host-only (do not add an explicit `Domain` attribute at all, even one scoped to this project's own two subdomains — the two apps don't need to share a session). For CORS, enumerate the nonprod origin as a second explicit string in the existing allowed-origins list (matching how local dev origins were already added per the v1.2 CORS work), never a wildcard/pattern match against the shared `duckdns.org` suffix. For Caddy, prefer one Caddy instance serving both vhosts (one Caddyfile, two site blocks) over two separate Caddy containers each binding `80`/`443` — this avoids the port-conflict failure mode entirely (only one process can bind 443) and keeps the existing named-volume-for-cert-persistence pattern working for both vhosts without duplicating it.

**Warning signs:**
`docker logs caddy` shows repeated `obtaining certificate` lines for the same hostname across container recreations rather than once. A cross-origin request from the nonprod frontend succeeds against the production API (or vice versa) when it should have been rejected by CORS. Any config, anywhere, contains the literal substring `.duckdns.org` as a pattern rather than a full hostname.

**Phase to address:**
Nonprod/staging deploy target provisioning phase (Caddy/vhost setup) — the "one Caddy, two site blocks" decision should be made explicitly during provisioning, not discovered as a port-binding failure during first deploy.

---

### Pitfall 9: The existing Docker Hub tag-pruning job deletes the other environment's live image tag

**What goes wrong:**
`cleanup-old-images` (the job this project fixed twice in the same session per `INFRA_RUNBOOK.md` — first a missing repo-name path segment, then missing pagination) iterates **every** tag in the `rudenkovladimir/kanban-board-backend` Docker Hub repository and deletes all of them except the single tag this run's own `build-and-push-docker-image` job just produced. If nonprod images are pushed to this same repository (the natural default, since `DOCKERHUB_USER`/`DOCKERHUB_REPOSITORY` are hardcoded `env:` values at the top of the workflow), the very next prod deploy's `cleanup-old-images` run will delete nonprod's currently-running image tag too, since the job has no concept of "a tag that's live in a different environment" — it only knows about the one tag its own run just built.

**Why it happens:**
The job's "keep the current tag, delete everything else" logic was correct and sufficient when exactly one environment ever existed; it silently stops being correct the moment a second environment starts sharing the same repository, and nothing about the job's own code would surface that as an error — it would simply succeed at deleting a tag someone else still needs, exactly the kind of "job reports green, real damage done" failure this project's own precedent (the two silent bugs already found in this job) shows is easy to miss without live verification.

**How to avoid:**
Either push nonprod images to a distinctly-named Docker Hub repository (simplest, avoids touching this job's logic at all), or — if reusing one repository is deliberate to save on registry sprawl — extend `cleanup-old-images`'s exclusion list to also fetch and preserve whatever tag is currently referenced by the nonprod deploy target (e.g. by reading nonprod's own `.env`-equivalent `IMAGE_TAG` off the VM, or maintaining an explicit allowlist of "protected" tags) before deleting anything. Given this job's demonstrated fragility (two real bugs found only by running it live), prefer the separate-repository option — it needs zero changes to already-fragile cleanup logic.

**Warning signs:**
A nonprod deploy that was working stops being pullable (`docker compose pull` on the nonprod host fails with `manifest unknown`) shortly after an unrelated prod push completes. `cleanup-old-images`' own run log lists a tag being deleted that matches a commit SHA currently deployed to nonprod, not prod.

**Phase to address:**
CI job deploying to nonprod phase — the repository-naming decision (shared vs. separate) should be made and the cleanup job's blast radius verified before the first nonprod image push, not discovered when nonprod goes unexpectedly unpullable.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|-----------------|------------------|
| Share one Redpanda broker between prod/nonprod with topic/group prefixing instead of a second broker instance | Saves ~2.2GiB of the host's tight ~2.65GiB remaining budget (Pitfall 3/4) | Real blast-radius risk if prefixing is ever misapplied (Pitfall 7); nonprod cannot safely run schema-breaking experiments against a shared registry | Acceptable if nonprod's Kafka-touching test scenarios are narrow and the topic/group prefix convention is enforced by config, not developer memory alone — revisit the moment nonprod needs to intentionally break schema compatibility |
| Reuse the existing `DOCKERHUB_REPOSITORY` for nonprod images instead of a separate repo | No new Docker Hub repo to create/manage | Directly re-exposes the already-twice-buggy `cleanup-old-images` job to a new failure mode (Pitfall 9) | Never — the separate-repository option costs nothing extra and sidesteps a job already proven fragile |
| Manually deploy nonprod once (mirroring how prod's first deploy was done by hand in plan 05-04) before automating CI for it | Faster first working nonprod environment | Same class of drift risk the project already flagged for prod docs ("this runbook is the checked-in description... update it in the same change") — a hand-deployed nonprod stack with no CI parity will silently diverge from what the eventual CI job assumes | Acceptable only as a short-lived bring-up step, immediately followed by CI automation in the same milestone — do not let it become the permanent deploy path the way it briefly risked doing for prod |
| Skip GitHub Environments migration and gate nonprod-vs-prod purely by which secret *names* each job happens to reference | Faster to ship the first nonprod CI job | Zero structural boundary — a future job (by anyone, including an automated agent) that references the wrong secret name by copy-paste has nothing stopping it (Pitfall 5) | Never, once a second real deploy target exists — the cost of the Environments migration is one-time and small relative to the blast radius of getting it wrong |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|-----------------|-------------------|
| Docker Compose (two stacks, one host) | Relying on Compose's default per-directory project-name derivation instead of an explicit `name:` per stack | Pin distinct `name:` values in each stack's own Compose file; verify with `docker compose ps`/`docker network ls`/`docker volume ls`, not by reading the YAML |
| GitHub Actions secrets | Adding a nonprod job that references an existing prod-named repo secret (`secrets.DB_HOST` etc.) instead of a new nonprod-scoped one | Migrate to GitHub Environments (`production`/`staging`) before adding the nonprod job; give nonprod secrets clearly distinct names |
| Neon branching | Assuming a "nonprod branch" filters the same connection as prod rather than being a fully separate credential/host pair | Create a real Neon branch, mint its own distinctly-named secrets, add a hostname guard mirroring the existing pooler-endpoint guard |
| Caddy / Let's Encrypt | Running a second Caddy container for the nonprod vhost, binding `80`/`443` again | One Caddy instance, two site blocks in one Caddyfile, both backed by the existing persistent `caddy-data`/`caddy-config` volumes |
| Docker Hub | Pushing nonprod images into the same repository the existing (twice-buggy) `cleanup-old-images` job already prunes unconditionally | Use a separate Docker Hub repository for nonprod images, or explicitly extend the cleanup job's exclusion logic first |
| Redpanda / Kafka | Reusing identical topic names, consumer group ids, and Avro subject names for nonprod against a shared broker | Prefix every topic and consumer group id by environment; accept that a shared registry means shared schema-compatibility constraints unless a second registry is provisioned |
| DuckDNS-scoped cookies/CORS | Widening a cookie `Domain` attribute or a CORS origin match to the shared `.duckdns.org` suffix to "cover both environments in one rule" | Enumerate each environment's full hostname explicitly; never pattern-match against a public-suffix-adjacent shared domain |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|-----------------|
| Sizing nonprod's Redpanda `--memory` by halving prod's value arithmetically | Container boots once, then fails every subsequent restart with `insufficient physical memory` (same signature as the already-documented prod incident) | Measure live with small decrements from prod's known-working `--memory 2G`, stop at the first value that survives a restart, not at a "nice" fraction | Any value chosen without a live restart test — this is not a scale threshold, it's a correctness threshold |
| Summing individual container `mem_limit` values as if they reserve rather than merely cap | Both stacks individually report healthy `docker stats`, host-level OOM killer still fires and takes down an unrelated process | Budget the *sum* of every service's cap across both stacks against the host's real 7.8GiB, with margin, and load-test both stacks simultaneously | Once combined caps exceed roughly 7.3–7.5GiB (leaving Caddy + OS their own share) — a number this project can compute exactly once nonprod's caps are chosen |
| Assuming nonprod's Playwright E2E traffic is "light" and needs no cap discipline of its own | A CI-triggered E2E run against nonprod produces a burst comparable to or larger than the 54-request burst that was prod's own measurement baseline, at a moment prod is also serving real traffic | Give nonprod's `app`/`redpanda` explicit caps sized against a realistic E2E burst, not left uncapped on the assumption "it's just staging" | The first time an E2E run and real prod traffic overlap in time on a colocated host |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| No GitHub Environments boundary between prod and nonprod secrets | A nonprod-scoped CI job (or a future automated change to one) has structurally unrestricted access to production SSH keys and database credentials | Migrate prod secrets into a `production` GitHub Environment (with required reviewers) before nonprod CI exists |
| Reusing the prod deploy user's SSH keypair for the nonprod deploy target | A compromised or misused nonprod deploy path has direct root-adjacent (docker group) access to prod's host | Generate a distinct, nonprod-scoped keypair, mirroring how the dedicated `deploy` user/keypair was already generated fresh for prod rather than reusing the personal admin key |
| Widening the session cookie or CORS scope to cover both environments' hostnames via a shared-suffix pattern | Leaks session/credentialed-request scope to every unrelated DuckDNS tenant under the shared public suffix, not just this project's own two environments | Enumerate full hostnames explicitly in both cookie and CORS config; never pattern-match a public suffix |
| Sharing one Avro schema registry/broker without topic or group prefixing | A nonprod producer/consumer can read, skip, or corrupt production's committed offsets and activity-log data (Pitfall 7) | Environment-prefix every topic/group id; treat a shared registry as sharing prod's compatibility contract by default |

## "Looks Done But Isn't" Checklist

- [ ] **Nonprod Compose stack reports `(healthy)`:** doesn't mean it's isolated from prod — verify `docker network ls`/`docker volume ls` show genuinely separate resource sets, not just that containers are running (Pitfall 1/2).
- [ ] **Nonprod deploy CI job goes green:** doesn't mean it deployed to the right target — verify via `docker inspect`'s `com.docker.compose.project` label and image digest on the actual host, the same way this project's own precedent (`INFRA_RUNBOOK.md`'s repeated "confirmed by direct inspection, not by trusting the green checkmark alone") already insists on for prod.
- [ ] **A GitHub Environment named `staging` exists:** doesn't mean prod secrets are actually inaccessible from it — verify by attempting (in a disposable test run) to reference a `production`-scoped secret from a job declaring `environment: staging` and confirming it fails to resolve.
- [ ] **Nonprod's Neon branch was created:** doesn't mean the CI job is actually pointed at it — verify the resolved hostname differs from prod's known hostname via a guard step, not by trusting the secret's name.
- [ ] **Redpanda topic/group names were prefixed in application config:** doesn't mean the Avro schema registry is also isolated — `RecordNameStrategy` subjects are derived from the Java class name, not the topic, and are shared unless a second registry exists or the strategy itself changes for nonprod.
- [ ] **A Let's Encrypt cert was issued for the nonprod subdomain:** doesn't mean the setup is rate-limit-safe under redeploys — verify the cert/account state actually persists across container recreation (a named volume, mounted, and confirmed to survive a `docker compose down && up`), not just that the first issuance succeeded.
- [ ] **`cleanup-old-images` ran green after a prod push:** doesn't mean nonprod's image tag survived — verify nonprod can still `docker compose pull` its currently-deployed tag immediately after (Pitfall 9).

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|----------------|------------------|
| Nonprod deploy accidentally converged onto prod's live containers (Pitfall 1) | HIGH | Immediately re-run the correct prod deploy job to restore the known-good image tag; audit `docker compose ps`/`docker inspect` on the VM to confirm which containers were actually mutated; treat this as a production incident requiring the same live-verification discipline `INFRA_RUNBOOK.md` already applies to every deploy |
| Redpanda `mem_limit`/`--memory` chosen too low for nonprod (Pitfall 3) | LOW | Raise the value incrementally and re-restart until `(healthy)` is reached within `start_period` — this project already has a proven, documented recovery path for exactly this failure shape |
| Host-level OOM kill takes down a prod container due to aggregate overcommit (Pitfall 4) | MEDIUM | `restart: unless-stopped` (already set on every service in this project's Compose files) brings the killed container back automatically; still requires re-budgeting the caps across both stacks afterward so it doesn't recur |
| A nonprod CI job ran Flyway migrations against the prod Neon branch (Pitfall 6) | HIGH | Neon branches support point-in-time restore; assess whether the migration was additive/reversible via a new down-migration, or whether a Neon restore-to-timestamp is needed — this is a data-integrity incident, not a config fix |
| Topic/consumer-group bleed corrupted prod's activity log (Pitfall 7) | MEDIUM–HIGH | The existing idempotent-consumer dedup constraint limits some damage (duplicate events are already rejected), but cross-environment offset commits are not covered by that dedup — may require replaying from a known-good offset or accepting a documented gap in the activity feed for the affected window |
| `cleanup-old-images` deleted nonprod's live tag (Pitfall 9) | LOW | Re-run `build-and-push-docker-image` for the affected commit (Docker Hub allows re-pushing an already-built tag if the image is still cached in CI, or a fresh rebuild if not) and redeploy nonprod |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|-------------------|----------------|
| Copy-pasted deploy job overwrites prod (P1) | Nonprod deploy target provisioning | `docker inspect` project label on nonprod containers is never `kanban-board-backend`; a deliberate CI-time assertion blocks it |
| Compose project/network/volume collisions (P2) | Nonprod deploy target provisioning | `docker network ls`/`docker volume ls` show fully distinct sets after both stacks are up |
| Redpanda sizing arithmetic without re-measurement (P3) | Nonprod deploy target provisioning (sizing sub-task) | A live restart-and-`(healthy)` check at the chosen `--memory` value, following the same method that found the original prod bug |
| Aggregate cgroup overcommit (P4) | Nonprod deploy target provisioning (sizing sub-task) | A simultaneous-burst load test against both stacks together, `docker stats` and `dmesg`/OOM-log checked, not just each stack alone |
| No GitHub Environments boundary (P5) | CI job deploying to nonprod | `gh api` confirms `production`/`staging` environments exist with disjoint secret sets; a deliberate cross-environment secret reference fails to resolve in a test run |
| Neon branch/connection-string mix-up (P6) | Nonprod deploy target provisioning (branch) + CI job deploying to nonprod (guard) | A hostname guard analogous to the existing pooler-endpoint guard, proven by a deliberate failing run then reverted (this project's own established pattern for proving CI guards) |
| Kafka/Redpanda topic and registry bleed (P7) | Nonprod deploy target provisioning | `rpk group describe`/`rpk registry subject list` show no cross-environment membership or shared subjects post-deploy |
| Caddy cert/CORS/cookie scope leakage (P8) | Nonprod deploy target provisioning (Caddy/vhost setup) | Cert/account state survives a `docker compose down && up` cycle for both vhosts under one Caddy instance; CORS/cookie config contains no `.duckdns.org` pattern match |
| Docker Hub tag-pruning cross-environment deletion (P9) | CI job deploying to nonprod | A prod push followed by a nonprod `docker compose pull` of its already-deployed tag still succeeds |

## Sources

- `docs/INFRA_RUNBOOK.md` (this repository) — HIGH confidence, primary source for this project's own already-documented incidents (Compose project-name collision, Redpanda `mem_limit`-equals-internal-request boot failure, Docker Hub `cleanup-old-images` two real bugs, Flyway pooled-endpoint guard pattern) and the measured resource baseline used throughout this document.
- `.github/workflows/deploy.yml` (this repository) — HIGH confidence, read directly to confirm the current absence of GitHub Environments and the exact deploy-job/cleanup-job mechanics referenced in Pitfalls 1, 5, and 9.
- `docker-compose.prod.yml` (this repository) — HIGH confidence, read directly for the current `name:`/`mem_limit`/logging/port-binding configuration referenced throughout.
- General web research (MEDIUM confidence — cross-checked against official docs; LOW confidence — single-source blog/forum posts, not independently cross-checked):
  - Docker Compose project-name/network/volume namespacing behavior and collision fixes (MEDIUM) — [KhueApps: Fix duplicate service names and project collisions in Docker Compose](https://www.khueapps.com/blog/article/how-to-fix-services-have-duplicate-names-or-project-name-collisions-in-compose), [Docker Community Forums: Two docker-compose.yml in the same network with COMPOSE_PROJECT_NAME](https://forums.docker.com/t/two-docker-compose-yml-in-the-same-network-with-compose-project-name/30992)
  - Docker cgroup memory/CPU enforcement mechanics, OOM-kill behavior (LOW) — [Baeldung: Setting Memory And CPU Limits In Docker](https://www.baeldung.com/ops/docker-memory-limit), [GnTech: Linux Cgroups v2 Guide — Docker Container Resource Management](https://blog.gntech.me/posts/2026-06-17-cgroups-v2-docker-container-resource-limits/)
  - GitHub Actions Environments secret scoping (LOW) — [GitHub Community Discussion #170113: Best practices for managing secrets in GitHub Actions across multiple environments](https://github.com/orgs/community/discussions/170113), [Doppler: Securing staging environments](https://www.doppler.com/blog/securing-staging-environments-secrets-management)
  - Let's Encrypt/Caddy rate limits and ACME account behavior (MEDIUM) — [Caddy Documentation: Automatic HTTPS](https://caddyserver.com/docs/automatic-https), [Let's Encrypt Community: Rate limit and accounts creation for 100K domains on caddy](https://community.letsencrypt.org/t/rate-limit-and-accounts-creation-for-100k-domains-on-caddy/215146)
  - Neon branching isolation and connection-string handling (MEDIUM) — [Neon Docs: Database branching workflow primer](https://neon.com/docs/get-started/workflow-primer), [Neon Docs: CLI connection-string command](https://neon.com/docs/cli/connection-string)
  - Kafka/Redpanda multi-tenant topic and consumer-group naming conventions (LOW) — [Conduktor: Multi-Tenancy in Kafka Environments](https://www.conduktor.io/glossary/multi-tenancy-in-kafka-environments), [Florian Courouge: Multi-Tenant Kafka — Sharing a Cluster Without the Chaos](https://floriancourouge.com/en/blog/kafka-multi-tenancy-guide)

---
*Pitfalls research for: adding a nonprod/staging environment to an existing, live, resource-capped single-VM production deployment*
*Researched: 2026-08-17*
