# Stack Research

**Domain:** Nonprod/staging environment for a single-VPS Spring Boot + Redpanda + Neon production stack
**Researched:** 2026-08-17
**Confidence:** MEDIUM-HIGH (official docs for Neon/Redpanda/Caddy/Netcup pricing are HIGH; the exact nonprod memory-cap numbers are a reasoned estimate extrapolated from this project's own measured prod figures, not an official spec, so treat those as MEDIUM and re-measure after first deploy per the Task 3 precedent already set in `docs/INFRA_RUNBOOK.md`)

## Recommended Stack

### Core Technologies

No new frameworks, languages, or cloud providers are needed — every addition below is either a second, deliberately-shrunk instance of a technology already in the production stack, or a config-only feature of a service already provisioned (Neon). This matches the project's own "no new frameworks introduced for this scope" constraint and its Netcup/Neon/Redpanda cost-guarded stack.

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Docker Compose `profiles:` | Compose Spec v2+ (already on `docker-compose-plugin v5.4.0`) | Gate a second `app-nonprod` + `redpanda-nonprod` pair inside the *same* `docker-compose.prod.yml`, off by default | A profile-gated second service pair means a bare `docker compose up -d` (prod's existing deploy command) never touches nonprod, and nonprod's containers automatically join prod's Compose network — so the existing single `caddy` container can resolve `app-nonprod` by service name without any cross-project `external: true` network wiring. Avoids the two riskiest failure modes already hit once in this exact repo: an implicit directory-derived project name creating a second, disconnected project (see 05-05's "Compose project name was directory-derived" incident), and a container that can't reach a sibling service because it's on the wrong network. |
| Redpanda `docker.redpanda.com/redpandadata/redpanda:v26.2.1` (second instance, same pinned tag as prod) | v26.2.1 | A second, deliberately shrunk broker for nonprod, not a shared broker with topic-prefix | See "Redpanda/Kafka isolation" below — a second broker instance is the recommended isolation mechanism, not topic-prefixing on the shared prod broker. |
| Neon branch (not a new Neon project) | N/A — feature of the already-provisioned `kanban-board-db` project | Give nonprod its own isolated Postgres endpoint, connection string, and (optionally) schema-only dataset, without a second Neon project or a second monthly compute/storage allowance to track | See "Neon branching mechanics" below. |
| Caddy (reuse the existing single container, add a second `Caddyfile` site block) | `caddy:2` (unchanged) | TLS + reverse-proxy for the nonprod subdomain | Ports 80/443 can only be bound by one process on this VM — a second Caddy container is not an option (see "Caddy/DNS implications" below). Caddy's automatic-HTTPS model already handles multiple independent site blocks natively; this is a config addition, not a new dependency. |
| DuckDNS (second free subdomain on the existing account) | N/A | DNS name for the nonprod vhost | The account already used for prod's `kanban-board-rud-vlad-473.duckdns.org` supports up to 5 free subdomains per account — no new DNS provider needed. |

### Supporting Libraries

None. The recommended design (separate broker instance, separate Neon branch, separate Compose services gated by `profiles:`) requires **zero `src/main` code changes** — `application.properties` already reads `KAFKA_BOOTSTRAP_SERVERS`, `SCHEMA_REGISTRY_URL`, and `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASS` from the environment, exactly the same way `docker-compose.prod.yml` already injects them for prod. Standing up nonprod is an infra/CI change, not an application change — worth stating explicitly because the topic-prefix alternative (rejected below) would *not* have had this property.

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| `rpk` (already inside the `redpanda` image) | Verify the nonprod broker's health/registry state the same way `docs/INFRA_RUNBOOK.md` already does for prod (`rpk cluster health`, `rpk registry subject list`) | No new tool — already used against prod via `docker compose exec`. |
| Neon CLI or the Neon MCP/GitHub Action (`neondatabase/reset-branch-action` etc.) | Optional: reset the nonprod branch to a clean state before an E2E run | Not required for the base setup — a single, persistent nonprod branch that Flyway migrates on each nonprod deploy (exactly like prod's `flyway-verify` CI job already does) is sufficient for this milestone's scope. Only reach for scripted branch reset if E2E test pollution across runs becomes a real, observed problem — see "What NOT to Use" below. |

## Installation / Provisioning Steps

```bash
# 1. Neon: create one persistent branch off the existing "production" branch
#    (Neon Console -> Branches -> Create branch -> parent: production; or `neonctl branches create`)
#    Recommended: a schema-only branch if you do not want prod's real rows visible to
#    Playwright E2E runs; a full copy-on-write branch if fixture parity with prod data matters more.
#    Either way this is a few-second, effectively-free operation on the Free plan (10 branches/project included).

# 2. Compose: add profile-gated services to the existing docker-compose.prod.yml
#    (illustrative — final service names/ports are an implementation decision, not this file's job)
#    services:
#      app-nonprod:
#        profiles: ["nonprod"]
#        image: rudenkovladimir/kanban-board-backend:${IMAGE_TAG}
#        mem_limit: 1g
#        environment:
#          DB_HOST: ${NONPROD_DB_HOST}   # the Neon branch's direct endpoint
#          KAFKA_BOOTSTRAP_SERVERS: redpanda-nonprod:19092
#          SCHEMA_REGISTRY_URL: http://redpanda-nonprod:8081
#      redpanda-nonprod:
#        profiles: ["nonprod"]
#        image: docker.redpanda.com/redpandadata/redpanda:v26.2.1
#        mem_limit: 900m
#        command: [redpanda, start, --overprovisioned, --smp, "1", --memory, 700M, ...]

# 3. Bring nonprod up alongside prod, without touching prod's own deploy command:
docker compose -f docker-compose.prod.yml --env-file ./.env.prod --profile nonprod up -d

# 4. Caddy: add a second site block to the existing Caddyfile (same container, same 80/443 bind)
#    nonprod.kanban-board-rud-vlad-473.duckdns.org {   # or a second DuckDNS subdomain
#        reverse_proxy app-nonprod:8080
#    }
```

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|--------------------------|
| Colocate nonprod on the existing Netcup VPS Lite 2 G12s, resource-shrunk | A second, separate small VPS (e.g. Netcup VPS Lite 1 G12s, 2 vCPU/4GB, ~€4/month) | Use this only if colocated `docker stats`/`free -h` monitoring (see "Resource sizing" below) shows real memory pressure under simultaneous prod-traffic + nonprod-E2E-burst — not preemptively. This project already pivoted providers once (Oracle -> Netcup) purely on capacity grounds, not cost avoidance for its own sake, so a second VPS is a legitimate fallback, not a taboo — but there is no measured evidence yet that it's needed, and paying for idle capacity "just in case" contradicts the project's own cost-guarded stack decisions. |
| A second, separate Redpanda broker instance for nonprod | Topic-name prefix (e.g. `nonprod.kanban.activity`) on the *same* shared prod broker | Only if the VM were memory-constrained enough that a second broker's baseline footprint (measured ~350MB idle RSS in prod) genuinely couldn't fit — not the case here (see "Redpanda/Kafka isolation" below for why prefixing is actively worse for this specific codebase, independent of resource cost). |
| A single, persistent nonprod Neon branch, Flyway-migrated on deploy like prod | Per-PR ephemeral branches via Neon's GitHub Actions (`create-branch-action`/`delete-branch-action`) | Use ephemeral per-PR branches only if/when this becomes a multi-contributor project running parallel PR-triggered E2E suites that would otherwise stomp on each other's nonprod data. For a single persistent staging target hit by one frontend repo's Playwright suite, a static branch is simpler and has nothing to orchestrate. |
| A schema-only Neon branch (or a full copy-on-write branch, operator's choice) | An entirely separate Neon *project* for nonprod | A second project would be a second 0.5GB storage / 100 CU-hour monthly allowance to track on top of the existing Free-plan project, for no isolation benefit branching doesn't already provide — branches are fully isolated Postgres instances with their own connection string and compute endpoint; only the project-level CU-hour/storage budget is shared, and nonprod's CI-triggered, scale-to-zero traffic pattern is far too light to meaningfully compete with prod for that shared 100 CU-hour/month budget. |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|--------------|
| Topic-name prefixing on the shared production Redpanda broker | Two independent problems, not one: (1) this codebase's Kafka topic names are compile-time Java constants (`KafkaTopics.ACTIVITY = "kanban.activity"`, `KafkaTopics.ACTIVITY_DLT`), not environment-parameterized — making them env-driven is itself a `src/main` code change this milestone's "no new frameworks/minimal footprint" spirit doesn't need to pay for when a zero-code-change alternative exists. (2) Even with prefixed topic names, the app's Avro subject-naming strategy is `RecordNameStrategy` (subject = the Avro record's fully-qualified Java class name, confirmed in `application.properties` and `docs/INFRA_RUNBOOK.md`'s live registry checks) — subjects are keyed by *class name*, not topic. A shared broker's single built-in Schema Registry would register the *same* subjects for prod and nonprod regardless of topic prefix, so a nonprod schema-evolution test (the whole point of Phase 4's BACKWARD-compatibility machinery) would mutate the production registry's compatibility history. That is a correctness risk, not just an isolation nicety. | A second broker instance (own Compose service, own `redpanda-data` volume, own built-in registry). Topic names, subject names, and consumer group IDs all stay byte-identical to prod's — nonprod is a scaled-down clone of prod's exact Kafka wiring, not a differently-namespaced tenant on prod's broker. |
| A second Neon *project* per environment | Needless duplication of a monthly compute/storage budget that branching already avoids; also a second project means a second set of secrets/connection strings to manage in CI with no isolation benefit over a branch | A branch within the existing `kanban-board-db` project |
| A second Caddy container bound to 80/443 | Cannot coexist with the running prod Caddy container on the same host — both would race for the same two ports; only one process may bind them | One Caddy container, two (or more) site blocks in the same `Caddyfile` |
| Kubernetes / a container-orchestration platform for "proper" environment separation | Wildly disproportionate to a two-container-pair nonprod addition on an 8GB VPS; the project's own Out of Scope list already excludes Kubernetes as an epic | Docker Compose `profiles:`, already available in the exact Compose plugin version this VM runs |
| A separate cloud provider/region for nonprod "for realism" | No requirement in the milestone context calls for multi-region or multi-provider realism, and it reintroduces exactly the cross-provider complexity (DNS, firewall model, secrets surface) this project spent Phase 5 collapsing down to one host | Same Netcup VM (colocated) or, if measurement forces the fallback, the *same* Netcup account's smallest VPS Lite tier — keeps the operational model (SSH access pattern, firewall approach, Docker Compose deploy flow) identical to what's already proven, rather than introducing a second one |

## Stack Patterns by Variant

**If colocated monitoring later shows real memory pressure (OOM kills, or `free -h` "available" dropping into low hundreds of MB during a simultaneous prod-burst + nonprod-E2E-run):**
- Move nonprod to a second Netcup VPS Lite 1 G12s (2 vCPU/4GB RAM, ~€4.05/month at time of research)
- Because the app+redpanda pair's *measured* footprint (prod: app ~460-470MB RSS, redpanda ~348MB RSS, both far under their caps) would comfortably fit a 4GB box on its own, and this keeps the same Docker Compose deploy pattern, SSH key model, and two-layer firewall approach already proven for prod — only the DNS A record and a second `NETCUP_HOST`-equivalent CI secret change.

**If the frontend repo's Playwright suite ever needs a genuinely clean database per run (not just per environment):**
- Add Neon's `reset-branch-action` (or an API call to the same effect) as a pre-test step in the *frontend* repo's CI, resetting the nonprod branch to its parent (`production`) immediately before the E2E run
- Because "reset from parent" is an instant, built-in Neon operation — no custom teardown/seed scripting needed, and it keeps the reset logic in the frontend CI (where the E2E suite already lives) rather than adding orchestration surface to this backend repo for a concern that isn't this repo's to own.

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|------------------|-------|
| `docker-compose-plugin v5.4.0` (already installed on the Netcup VM) | Compose Spec `profiles:` key | Supported since Compose V2; no plugin upgrade needed — this VM's plugin version already postdates `profiles:`' introduction. |
| Redpanda `v26.2.1` (nonprod instance) | Same tag as prod's `redpanda` service | Deliberately identical to prod's pinned version — the project's own compose-file comment already states the rationale for this ("production and the Phase 4-verified local environment must not become two undertested broker versions"); the same logic applies to nonprod. |
| Neon branch (any) | Same Postgres major version as its parent (currently Postgres 18) | Branches inherit the parent's Postgres version at branch time; not an independent choice. |
| Flyway `11.7.2` (CI's `flyway-verify` job image) | Nonprod's Neon branch direct endpoint | No change needed — the existing `flyway-verify` job pattern (official Flyway CLI image against a direct, non-pooled connection string) is reusable verbatim against the nonprod branch's own direct endpoint, just with different `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` secrets. |

## Resource Sizing Detail (Question 1)

**Host baseline (measured, `docs/INFRA_RUNBOOK.md`, 2026-08-16/17):** Netcup VPS Lite 2 G12s, 4 vCPU / 7.8GiB RAM measured. Prod's worst-case *reserved* memory (sum of `mem_limit`s, not actual usage) is `app` 3g + `redpanda` 2200m ≈ 5.15GB, leaving **~2.65GB of the 7.8GB host unreserved** by any cgroup cap. Actual measured usage under a real 54-request burst was far below those caps the whole time (app ~15%, redpanda ~17% of their respective limits; host "available" memory unchanged at 6.4GB before and after the burst) — the caps are a safety ceiling, not a working-set floor.

**Recommended nonprod caps**, sized down from prod using the same headroom pattern this project already discovered the hard way (Redpanda's Seastar allocator refuses to start if the cgroup `mem_limit` is set numerically equal to its own `--memory` value — it needs real headroom above that number, not just equal to it):

| Service | `mem_limit` | Internal setting | Rationale |
|---------|-------------|-------------------|-----------|
| `app-nonprod` | `1g` | JVM's `MaxRAMPercentage=25%` default (unchanged, no `-Xmx` set anywhere in this repo) sizes heap off this limit automatically, same mechanism as prod | A third of prod's cap; E2E smoke/regression traffic from one Playwright suite is not the sustained load prod's 3g was sized for. Prod's own actual RSS (~460-470MB) suggests real headroom even at 1g, since JVM baseline overhead (metaspace, thread stacks, code cache) is largely limit-independent, not heap-proportional. |
| `redpanda-nonprod` | `900m` | `--overprovisioned --smp 1 --memory 700M` | `700M`/`--smp 1` is a documented, commonly-used low-memory dev/CI Redpanda pattern (below Redpanda's own stated *production* minimum of 2GB/core — deliberately, since this broker only needs to carry CI-triggered E2E traffic, not production throughput). `900m` gives ~200MB (~28%) of cgroup headroom above the internal `--memory` request, proportionally more generous than prod's ~10% headroom (`2200m`/`2G`) because headroom needs don't scale linearly down with the base value. |

**Combined worst-case reservation if every cap were simultaneously maxed out:** prod (5.15GB) + nonprod (1.9GB) + Caddy (recommend adding an explicit `mem_limit: 256m` backstop — it currently has none, the same gap this project already found and fixed for `redpanda` in Task 3) ≈ **7.3-7.4GB of the 7.8GB host**, leaving only ~400-500MB for the OS. That is genuinely tight *as an arithmetic ceiling* — worth stating plainly rather than glossing over. But it is a ceiling on a scenario (prod at 100% of its cap at the exact moment nonprod's E2E suite also pins its cap) that prod's own measurement data shows is far from prod's real operating envelope (~15-17% of cap under real burst traffic). **Recommendation: colocate, set the caps above, and watch `docker stats`/`free -h` during the first several nonprod E2E CI runs** (the same measure-then-correct discipline this project's own Task 3 already established for redpanda) rather than pre-provisioning a second VPS against a risk that hasn't been observed. If real pressure shows up, the "Stack Patterns by Variant" fallback above is the documented next step, not a redesign.

**CPU is not the binding constraint.** Neither `mem_limit` nor Redpanda's `--smp` reserves CPU exclusively — Docker's default CPU scheduling is fair-share, not a hard partition, unless an explicit `--cpus`/`cpuset` limit is added (prod doesn't use one today). Four vCPUs shared fairly across two lightly-loaded broker instances (`--smp 1` each) and two lightly-loaded JVMs is not a realistic contention point for this traffic shape.

## Neon Branching Mechanics (Question 2)

- **Mechanism:** a Neon branch is a copy-on-write, metadata-only operation — it does not copy the parent's data pages, only records a pointer into the parent's existing storage history. Creating a branch is near-instant (well under a second for the metadata; the associated compute endpoint provisions within seconds) regardless of the parent database's size.
- **Isolation:** every branch is a **fully independent Postgres instance** with its own compute endpoint and its own connection string (both a direct and a pooled/PgBouncer variant, exactly like prod's existing `ep-delicate-bird-...` endpoint). Nonprod would get its own `ep-<name>.c-6.eu-central-1.aws.neon.tech` direct host and its own `-pooler` host, structurally identical to how prod's `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASS` secrets are already split in this repo's CI.
- **Cost/plan tier:** this project's Neon project (`kanban-board-db`) is on the **Free plan** — confirmed by the runbook's recorded compute shape (`autoscaling 0.25-2 CU`, `suspend_timeout_seconds: 0`) matching the Free plan's documented ceiling of 2 CU max/scale-to-zero exactly. The Free plan includes **10 branches per project**, **100 CU-hours/month** and 0.5GB storage, all **shared at the project level across every branch**, not per-branch. One additional nonprod branch is nowhere close to that 10-branch ceiling, and its CU-hour draw (short-lived, CI-triggered, scale-to-zero between runs) will not meaningfully compete with prod's own light, personal-portfolio traffic for the shared 100 CU-hour/month budget. No plan upgrade is needed for this milestone.
- **Two relevant built-in features, both zero-cost on the same plan:**
  - **Schema-only branches** — replicate schema (tables, roles, etc.) without copying prod's actual row data. Worth using for nonprod if the operator doesn't want real (even if low-stakes, portfolio-scale) prod rows visible to a Playwright suite; otherwise a normal copy-on-write branch is equally valid and gives fixture parity with prod.
  - **Reset from parent** — an instant, built-in operation to reset a branch back to its parent's current schema+data. Root/production branches can't be reset (no parent), but a nonprod *child* branch of `production` can be — useful later if E2E test-data pollution across runs becomes a real, observed problem (see "Stack Patterns by Variant" above); not needed for the initial setup.
- **Connection-string/secrets changes needed:** mirror the existing prod pattern exactly — a new set of CI secrets (e.g. `NONPROD_DB_HOST`/`NONPROD_DB_NAME`/`NONPROD_DB_USER`/`NONPROD_DB_PASS`, or a differently-scoped GitHub Environment) pointing at the nonprod branch's **direct** (non-pooler) endpoint, for the same reason prod uses the direct endpoint today: Flyway's session-scoped advisory lock at boot is unsupported under Neon's transaction-mode pooler, and this app's small HikariCP pool (max 5) gets nothing from pooler multiplexing anyway (both facts already recorded in `docs/INFRA_RUNBOOK.md` for prod and equally true for nonprod).

## Redpanda/Kafka Isolation Options (Question 3)

| Option | Verdict | Why |
|--------|---------|-----|
| **Second broker instance** (recommended) | Use this | Zero `src/main` code changes (topic/subject names stay byte-identical to prod); genuinely isolates the Schema Registry (each Redpanda instance ships its own built-in registry, so nonprod schema-evolution testing can never mutate prod's registered subjects/compatibility history); genuinely isolates consumer groups and the dead-letter topic; measured footprint is small (~350MB idle RSS in prod) so the added resource cost is modest and directly measurable. Matches this project's own architecture note that "the app and Caddy reach [redpanda] only over the internal Compose network" — a second instance just means a second such internal-only network name, no new exposure surface. |
| **Topic-name prefix on the shared prod broker** | Do not use | Requires making `KafkaTopics.ACTIVITY`/`ACTIVITY_DLT` environment-parameterized (a real `src/main` change for a feature this milestone doesn't otherwise need), and even then does **not** isolate the Schema Registry, because this codebase's `RecordNameStrategy` subject naming is keyed by Avro record class name, not topic name — a shared broker means a shared, single set of registered subjects and compatibility rules regardless of topic prefixing. Industry guidance itself (Confluent's own naming-convention guidance) treats topic-prefixing as a *defense-in-depth addition on top of* separate clusters/brokers per environment, not a substitute for one — this project doesn't even get the defense-in-depth benefit cheaply here, since the registry collision remains. |

## Caddy/DNS Implications (Question 4)

- **One Caddy container, two site blocks — not two Caddy containers.** Ports 80/443 can only be bound by a single process per host; the existing `caddy` service already owns both. Caddy natively supports any number of independent site blocks (one per hostname) in a single `Caddyfile`, each obtaining and renewing its own Let's Encrypt certificate automatically and independently — this is a config addition (a few lines), not a new dependency or container.
- **Network reachability:** for Caddy's new site block to `reverse_proxy app-nonprod:8080`, the nonprod `app`/`redpanda` services must be on the *same* Compose network Caddy is already on. The `profiles:`-gated, same-file approach recommended above gets this for free (Compose's default network already spans every service in the file); a *separate* Compose project for nonprod would require explicitly marking that network `external: true` and naming it correctly — an extra, easy-to-drift step this repo has already been burned by once (the 05-05 "Compose project name was directory-derived" incident, where an implicit default silently created a second, disconnected project). Prefer the same-file `profiles:` approach specifically to avoid repeating that failure mode.
- **DNS:** a second hostname is needed (Caddy's automatic HTTPS keys each certificate to its site block's hostname). The existing DuckDNS account used for prod's `kanban-board-rud-vlad-473.duckdns.org` supports up to 5 free subdomains — no new DNS provider or paid domain needed; add e.g. `kanban-board-nonprod-rud-vlad-473.duckdns.org` (or similar) as a second A record pointing at the same VM's public IPv4, exactly like prod's existing record.
- **Certificate rate limits:** not a shared-budget concern — Let's Encrypt's rate limits are scoped per registrable domain, and DuckDNS is on the Public Suffix List, so each `*.duckdns.org` subdomain is its own independent registrable domain for rate-limiting purposes. A second subdomain's certificate request draws from its own fresh budget, not prod's. (Prod's own runbook documents its rate-limit caution as being about *repeated re-requests for the same domain*, e.g. from container-recreation churn without a persisted cert volume — not a cross-domain shared limit — so this doesn't apply to adding a genuinely new hostname.) Use the same named-volume pattern (`caddy-data`/`caddy-config`) prod already uses so container recreation doesn't trigger unnecessary re-requests for either domain.

## Sources

- [Neon Docs — Manage branches](https://neon.com/docs/manage/branches) — branching mechanics, copy-on-write, per-branch connection strings (HIGH)
- [Neon Docs — Reset from parent](https://neon.com/docs/guides/reset-from-parent) — reset-branch semantics, root-branch exception (HIGH)
- [Neon Docs — Automate branching with GitHub Actions](https://neon.com/docs/guides/branching-github-actions) — ephemeral per-PR branch pattern, considered and deferred (HIGH)
- [Neon — Announcing Branch Reset](https://neon.com/blog/announcing-branch-reset) — schema-only branch feature (HIGH)
- Web search aggregation of Neon 2026 pricing/limits pages (AgentDeals, SaaSPricePulse, Vela, CompareTiers) — Free-plan figures (10 branches/project, 100 CU-hours/month, 0.5GB storage, 2 CU max autoscale) cross-checked against this project's own measured `autoscaling 0.25-2 CU` figure in `docs/INFRA_RUNBOOK.md` (MEDIUM-HIGH — aggregator sources, but internally consistent with this project's own already-verified compute shape)
- [Redpanda Docs — Requirements and Recommendations (production)](https://docs.redpanda.com/current/deploy/redpanda/manual/production/requirements/) — official minimum-memory-per-core guidance, used as the baseline the recommended nonprod config deliberately undercuts (HIGH, for what it documents — it does not cover dev/CI-scale deployments)
- [GitHub — redpanda-data/redpanda issue #30172](https://github.com/redpanda-data/redpanda/issues/30172) — confirms the "below recommended" memory check is actually a hard, fatal startup failure, corroborating this project's own already-observed Seastar startup failure in `docker-compose.prod.yml`'s comments (HIGH — primary source, upstream maintainers' own issue thread)
- Web search aggregation on low-memory Redpanda Docker patterns (`--smp 1 --memory 512M/1G --overprovisioned`) — corroborates the recommended nonprod config as a real, commonly-used community pattern, distinct from and below Redpanda's own stated production minimum (MEDIUM — community/blog sources, not official docs, hence the nonprod sizing table above is flagged MEDIUM confidence overall)
- [Caddy Docs — Common Caddyfile Patterns](https://caddyserver.com/docs/caddyfile/patterns) and community examples (jdheyburn.co.uk, Caddy community forum) — multi-site-block-per-Caddyfile pattern (HIGH for the docs page, MEDIUM for the community corroboration)
- Web search on DuckDNS subdomain limits — up to 5 free subdomains per account (MEDIUM — no single canonical DuckDNS pricing/limits page surfaced, cross-corroborated across multiple independent write-ups)
- Web search on Netcup VPS Lite pricing (2026) — VPS 1000 G12 (the existing prod box) ≈€10.37/month incl. VAT; VPS Lite 1 G12s (2 vCPU/4GB, smallest tier, fallback option) ≈€4.05/month incl. VAT, 12-month minimum term (MEDIUM-HIGH — multiple independent voucher/comparison sites converge on the same figures, though netcup.com's own live pricing page was not directly scraped in this pass)
- This project's own `docs/INFRA_RUNBOOK.md` (2026-08-16/17 entries) and `docker-compose.prod.yml` — measured host/container resource figures, existing Kafka topic/subject-naming implementation, existing Compose project-naming and Caddy volume-persistence lessons (HIGH — primary, first-party, already-verified-live source)
- `src/main/java/com/vrudenko/kanban_board/constant/KafkaTopics.java` and `application.properties` (subject.name.strategy) — confirmed directly by reading the source, not inferred (HIGH)

---
*Stack research for: Nonprod/staging environment addition to an existing single-VPS production Spring Boot/Redpanda/Neon stack*
*Researched: 2026-08-17*
