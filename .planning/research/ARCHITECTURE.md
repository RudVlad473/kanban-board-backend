# Architecture Research: Nonprod/Staging Environment Integration

**Domain:** Infrastructure integration — colocating a second deploy environment onto an existing
single-VM, single-pipeline production stack (Netcup + Docker Compose + Caddy + Neon + Redpanda +
GitHub Actions)
**Researched:** 2026-08-17
**Confidence:** HIGH for Docker Compose multi-project mechanics and Caddy multi-site mechanics
(verified against this repo's own documented incidents plus current Caddy/Docker docs); MEDIUM-HIGH
for Neon branching (verified against current Neon docs); MEDIUM for the GitHub Actions job-graph
placement and DuckDNS subdomain count (design judgment / single corroborating source respectively)
— see Sources.

Scope note: this file answers "how does nonprod integrate with what's already there," not "what
should the whole system look like." It assumes everything in `docs/INFRA_ARCHITECTURE.md` and
`docs/INFRA_RUNBOOK.md` as fixed, working, load-bearing production state, per this milestone's own
explicit framing.

## Target Topology (after this integration)

```
Netcup VM (159.195.114.230, 4 vCPU / 7.8GiB RAM, 251GB disk)
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                            │
│  ┌─── Compose project "kanban-board-backend" (existing, prod) ────────┐  │
│  │                                                                     │  │
│  │   caddy ──ports 80/443──> internet                                 │  │
│  │     │  reverse_proxy app:8080            (site: APP_DOMAIN)        │  │
│  │     │  reverse_proxy app-nonprod:8080     (site: APP_DOMAIN_NONPROD)│ │
│  │     ├── attached to: kanban-board-backend_default (own net)        │  │
│  │     └── attached to: edge  (NEW, external, shared)                 │  │
│  │                                                                     │  │
│  │   app:8080 ──kanban-board-backend_default── redpanda:19092/8081    │  │
│  │     └── DB_HOST -> Neon PROD branch (direct endpoint)               │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│  ┌─── Compose project "kanban-board-backend-nonprod" (NEW) ────────────┐ │
│  │                                                                     │  │
│  │   app-nonprod:8080 ── kanban-board-backend-nonprod_default ──       │  │
│  │        redpanda-nonprod:19092/8081  (own broker, own topics)        │  │
│  │     ├── attached to: kanban-board-backend-nonprod_default (own net) │  │
│  │     └── attached to: edge  (NEW, external, shared — for Caddy only) │  │
│  │     └── DB_HOST_NONPROD -> Neon NONPROD branch (direct endpoint)    │  │
│  │                                                                     │  │
│  │   NO caddy service in this project — would collide on 80/443        │  │
│  │   NO host ports published anywhere in this project                  │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│  edge (NEW, external Docker network, bootstrapped once via                │
│         `docker network create edge`) — the only thing the two projects   │
│         share; carries Caddy⟷app-nonprod traffic only.                    │
└──────────────────────────────────────────────────────────────────────────┘

External:
  Neon (Frankfurt)         : 2 branches — existing prod branch (unchanged),
                              new nonprod branch (own compute, scale-to-zero)
  DuckDNS                  : 2nd subdomain, same A record IP as prod's
  GitHub Actions           : 2 new sibling jobs in the EXISTING deploy.yml
                              (not a new workflow file), parallel to
                              deploy-to-netcup, sharing the same built image
```

### Component Responsibilities

| Component | Responsibility | New or existing |
|-----------|-----------------|------------------|
| `caddy` (existing container, modified config) | TLS termination + routing for **both** hostnames | Existing, modified |
| `edge` Docker network | The only channel by which the two Compose projects' containers can reach each other; carries exactly one cross-project link (Caddy → `app-nonprod`) | New |
| `app-nonprod` | Nonprod API container, same image/tag as prod for that commit | New |
| `redpanda-nonprod` | Nonprod's own Kafka-protocol broker + Schema Registry, isolated topics by construction (separate broker, not shared topics) | New (see trade-off below) |
| Neon nonprod branch | Nonprod's system of record, isolated schema/data from prod, own compute (scale-to-zero) | New |
| `flyway-verify-nonprod` (CI job) | Migration-apply gate for the nonprod Neon branch, mirrors existing `flyway-verify` | New |
| `deploy-to-nonprod` (CI job) | SSH/SCP deploy of `docker-compose.nonprod.yml` to the VM, sibling to `deploy-to-netcup` | New |

## Integration Point 1 — Colocating a second Docker Compose project without collision

The concrete collision surfaces and how each is closed:

**Project name.** `docker-compose.nonprod.yml` gets its own top-level `name: kanban-board-backend-nonprod`
pin, exactly mirroring the fix already made to `docker-compose.prod.yml` after the real incident
recorded in `docs/INFRA_RUNBOOK.md` (a directory-derived project name once started a second,
unrelated project against empty volumes and lost the registered Avro schemas). This is not optional
here — it is the same failure mode, and the fix is already proven in this exact codebase.

**Container names.** Compose derives container names as `<project>-<service>-<replica>` when no
explicit `container_name:` is set. Because the project names differ (`kanban-board-backend` vs.
`kanban-board-backend-nonprod`), the resulting names are automatically distinct
(`kanban-board-backend-app-1` vs. `kanban-board-backend-nonprod-app-1`) with **zero explicit
`container_name:` overrides needed** — and none should be added, since a literal explicit name is
exactly what would reintroduce a collision if someone later copy-pasted a value across the two files.

**Docker network.** Compose creates one default network per project
(`<project>_default`) unless told otherwise. With the project names pinned as above, the default
networks are automatically distinct: `kanban-board-backend_default` (prod's existing internal
network — unchanged) vs. `kanban-board-backend-nonprod_default` (new). No explicit network
declaration is required for `app-nonprod` ⟷ `redpanda-nonprod` traffic; it happens on this
per-project default automatically, exactly like prod's `app` ⟷ `redpanda` link today.

**Named volumes.** Same per-project-prefix mechanism protects volume names:
`kanban-board-backend-nonprod_redpanda-data` is automatically distinct from
`kanban-board-backend_redpanda-data` — the same class of bug the runbook's volume-loss incident
was, closed the same way, for free, once the project name is pinned.

**Cross-project reachability for Caddy (the one deliberate exception).** Two independent Compose
projects' default networks do **not** talk to each other by design — that isolation is the whole
point of a distinct project name. Caddy (living in the prod project) needs a path to
`app-nonprod` (living in the nonprod project). The correct, documented Compose mechanism for this
is a single **external** network both projects reference explicitly, with only the specific
services that need to cross the boundary attached to it:

```bash
# One-time bootstrap on the VM, before either compose file references it:
docker network create edge
```

```yaml
# docker-compose.prod.yml (modified) — add:
networks:
  edge:
    external: true

services:
  caddy:
    networks:
      - default   # existing prod network — reaches app, unchanged
      - edge      # NEW — reaches app-nonprod
```

```yaml
# docker-compose.nonprod.yml (new) — top of file:
name: kanban-board-backend-nonprod
networks:
  edge:
    external: true

services:
  app-nonprod:
    networks:
      - default   # own network — reaches redpanda-nonprod
      - edge      # NEW — reachable BY caddy
    # redpanda-nonprod stays on `default` only — Caddy has no reason to reach it directly.
```

This is deliberately narrow: only Caddy and `app-nonprod` ever touch `edge`. `redpanda-nonprod`,
prod's `app`, and prod's `redpanda` are untouched by this network and keep their existing
no-published-ports, internal-only posture.

**Exposed host ports.** This is the actual answer to "how does a second exposed port avoid
colliding": **it doesn't get one.** Only one process can bind host 80/443 on this VM. The nonprod
project defines **no `caddy` service of its own** and **publishes no host ports anywhere** — same
internal-only discipline `docker-compose.prod.yml` already documents for `app`/`redpanda`
(`INFRA-08`). The existing, single Caddy instance is extended with a second site block (Integration
Point 3) instead of standing up a second reverse proxy. This is the one deliberate way this
integration *does* touch a currently-working prod file (`docker-compose.prod.yml`'s `caddy`
service gains a network attachment and one new env var) — everything else about prod's compose file
is unchanged.

**Alternative considered and rejected:** extracting Caddy into its own third Compose
project ("gateway" project owning nothing but the reverse proxy) is architecturally cleaner
long-term, but it means recreating the currently-running, cert-holding Caddy container under a new
project identity — the exact cutover risk (orphaned volumes, a forced fresh Let's Encrypt request)
`docs/INFRA_RUNBOOK.md` already recorded happening once during the `deploy` user cutover. Given this
milestone's explicit instruction not to redesign what already works, keep Caddy inside
`docker-compose.prod.yml` and only add to it.

## Integration Point 2 — Extending the GitHub Actions job graph

**Recommendation: two new sibling jobs inside the existing `deploy.yml`, not a second workflow
file.** The alternative (a standalone `deploy-nonprod.yml`) was considered and rejected for a
concrete reason: nonprod should deploy the *same already-built, already-verified image tag* as
prod for that commit — that's what makes the nonprod target trustworthy as a frontend E2E target
("this is what will actually ship," not "a lookalike build"). A separate workflow file cannot
consume another workflow's job outputs (`needs.build-and-push-docker-image.outputs.image_tag`)
without either a second, duplicate build (wasted CI minutes, and a real risk of nonprod/prod image
drift if the two builds ever produce different artifacts) or `workflow_run`/artifact-passing
plumbing that adds real complexity for no benefit here. Staying in one workflow file keeps "build
once, deploy twice" trivial.

**Trigger:** keep `deploy.yml`'s existing `on: push: branches: [master]` unchanged. Do **not**
switch nonprod to a `pull_request` trigger for this milestone. Reasoning: this environment's stated
purpose is "a real, non-mocked target for [the future] frontend repo's Playwright E2E suite" — a
stable, continuously-current staging target, not an ephemeral per-PR preview. A `pull_request`
trigger would mean the target moves (or doesn't exist) depending on which PR most recently
synchronized, which is the wrong shape for an external repo's test suite to depend on. Mirroring
prod's `push: master` trigger means nonprod always reflects exactly what's about to (or just did)
reach prod — genuinely useful as a pre-prod canary, not just a side effect of trigger reuse.

**Where it sits in the job graph — decoupled by `needs:`, not by execution order.** The milestone
context's "ahead of/alongside" language is satisfiable two ways: a hard gate (nonprod must succeed
before prod deploys) or a parallel run (both fire from the same push, neither blocks the other).
**Recommend parallel, not gating** — this is the direct answer to "so it doesn't slow down or risk
the existing production deploy": a hard gate would mean any nonprod-specific failure (a Neon
nonprod-branch hiccup, a nonprod SSH blip) blocks the prod deploy too, which is exactly the coupling
the quality gate warns against.

```yaml
# .github/workflows/deploy.yml — new jobs, added alongside the existing ones:

  flyway-verify-nonprod:
    needs: [ setup, run-tests ]      # same shape as flyway-verify, own DB_*_NONPROD secrets
    runs-on: ubuntu-latest
    if: success()
    # identical body to flyway-verify, pointed at Neon's NONPROD branch direct endpoint

  deploy-to-nonprod:
    needs: [ build-and-push-docker-image, flyway-verify-nonprod ]   # NOT flyway-verify, NOT deploy-to-netcup
    runs-on: ubuntu-latest
    if: success()
    concurrency:
      group: deploy-to-netcup-nonprod-vm     # DISTINCT group from deploy-to-netcup-vm —
      cancel-in-progress: false               # a queued nonprod deploy never blocks a prod one or vice versa
    steps:
      # same appleboy/scp-action + appleboy/ssh-action pattern as deploy-to-netcup,
      # targeting /opt/deploy/kanban-board-backend-nonprod/ and docker-compose.nonprod.yml,
      # reusing the SAME NETCUP_HOST/NETCUP_DEPLOY_USER/NETCUP_SSH_KEY/NETCUP_HOST_FINGERPRINT
      # secrets (same VM, same deploy user — no new SSH identity needed for this alone).
```

Both `deploy-to-netcup` and `deploy-to-nonprod` now depend only on `build-and-push-docker-image`
plus their own respective Flyway-verify job — siblings, not a chain. A failure in
`flyway-verify-nonprod` or `deploy-to-nonprod` cannot fail `flyway-verify` or `deploy-to-netcup`
(disjoint `needs:` graphs), and the distinct `concurrency:` group means a slow/queued nonprod
deploy on a rapid double-push never makes a prod deploy wait.

**Secrets needed, net-new:** `DB_HOST_NONPROD` / `DB_NAME_NONPROD` / `DB_USER_NONPROD` /
`DB_PASS_NONPROD` (Neon nonprod branch's direct endpoint) and `APP_DOMAIN_NONPROD` (the second
DuckDNS hostname, if templated the same way `APP_DOMAIN` already is via `.env.nonprod` rather than
a workflow secret — matches the existing pattern where `APP_DOMAIN` lives in `.env.prod`, not in
repo secrets). No new SSH credential is needed — same VM, same `deploy` user, same fingerprint.

**Existing cleanup jobs — minor, optional extension, not required.** `cleanup-old-images` only
deletes tags other than the current commit's tag, and both stacks run the *same* tag for the same
commit, so no conflict exists as-is. Optionally add `deploy-to-nonprod` to `cleanup-old-images`'s
`needs:` so cleanup waits for both deploys to finish before pruning (belt-and-suspenders, not
required for correctness). Leave `cleanup-unused-image` scoped to `deploy-to-netcup`'s failure only
— deleting a tag because the *nonprod* deploy alone failed would remove an image that might still
be correctly running in prod.

**Cross-repo trigger (frontend repo) — explicitly deferred.** With the frontend repo not existing
yet, this milestone should **not** build a `repository_dispatch`/`workflow_call` cross-repo trigger
at all. Because nonprod now deploys continuously on every push to master (same as prod), the
resulting nonprod hostname is already a stable, always-current target — a future frontend repo's own
CI can simply point Playwright at that hostname directly, with zero backend-side plumbing. This is
the literal "stub/defer" the quality gate asks for: the *simplest* correct answer here is "build
nothing extra," not "build a placeholder."

## Integration Point 3 — Caddy, DNS, and TLS for the second hostname

**DNS.** DuckDNS's free tier supports up to 5 subdomains per account (not just one) — register a
second one (e.g. `kanban-board-rud-vlad-473-nonprod.duckdns.org`) with an A record pointed at the
**same** VM IP (`159.195.114.230`), since nonprod is colocated. No dynamic-update client is needed,
identical to prod's reasoning (`docs/INFRA_RUNBOOK.md`: the VM's IP is static for the deployment's
lifetime).

**Caddyfile — add a second site block, don't touch the first.**

```caddyfile
{$APP_DOMAIN} {
	reverse_proxy app:8080
}

{$APP_DOMAIN_NONPROD} {
	reverse_proxy app-nonprod:8080
}
```

Both site blocks live in the one Caddyfile Caddy already loads. Caddy multiplexes multiple hostnames
on the same 80/443 listeners via SNI/Host-header routing — this is standard, documented Caddy
behavior, not a workaround. `app-nonprod:8080` resolves because `app-nonprod` is now attached to the
shared `edge` network (Integration Point 1); no other Caddyfile change is needed, and the existing
site block's behavior (its cert, its routing) is untouched.

**TLS / certificate issuance.** No new certificate volume or issuance mechanism is needed. Caddy's
existing `caddy-data` named volume already stores certificate/state data for however many sites
Caddy fronts — adding a second site block means Caddy independently runs its own HTTP-01 challenge
for `APP_DOMAIN_NONPROD` the first time it starts with that site block present, obtaining a second,
separate Let's Encrypt certificate, stored in the same existing volume alongside the prod
certificate. This carries the same rate-limit caution already documented for prod (don't repeatedly
recreate the `caddy-data` volume), but is otherwise a one-time, low-risk event exactly like the
original prod cert issuance already proven to succeed on the first attempt.

**docker-compose.prod.yml changes (this is the modification list for this integration point):**
- `caddy.environment`: add `APP_DOMAIN_NONPROD: ${APP_DOMAIN_NONPROD}` (mirrors the existing
  `APP_DOMAIN` pattern exactly).
- `caddy.networks`: add `edge` (see Integration Point 1).
- `.env.prod`: add `APP_DOMAIN_NONPROD=<second-duckdns-subdomain>`.

**Firewall.** No change needed. Both firewall layers already allow inbound 80/443 and default-deny
everything else; the second hostname resolves to the same IP and the same two open ports — there is
no new port to open, and nonprod's `app-nonprod`/`redpanda-nonprod` still publish nothing to the
host, matching `INFRA-08`'s existing guarantee.

## Integration Point 4 — CORS for a deployed nonprod frontend origin

**No backend code change is required for this piece.** `CorsConfig.java` already externalizes the
allow-list via `@Value("${app.cors.allowed-origins:...}")`, bound from the
`app.cors.allowed-origins` property — which Spring's relaxed binding resolves from an
`APP_CORS_ALLOWED_ORIGINS` environment variable if one is supplied, exactly the same mechanism
already used for `DB_HOST`/`SCHEMA_REGISTRY_URL`/etc. This was built in v1.2 Phase 07.1
specifically so a deployment can widen the origin list without a code change — this milestone is
the first time that design gets exercised for a *deployed* (not local-dev) origin.

**Current state, worth flagging explicitly:** neither `docker-compose.prod.yml`'s `app` service nor
(by extension) production's `.env.prod` currently sets `APP_CORS_ALLOWED_ORIGINS` at all — the app
falls back to `CorsConfig.java`'s default (`http://localhost:5173,http://localhost:3000`), i.e.
**production today allows only localhost dev origins**, which is harmless only because no browser
running at those origins can reach the real production hostname's cookies/session in a way that
matters, and because no frontend is deployed yet. This is not a regression to fix in this milestone
(no deployed frontend exists to be blocked), but it means "CORS extends to nonprod" is additive, not
a fix to something broken.

**Concrete change for nonprod, once the frontend repo/deploy target exists:**
- `docker-compose.nonprod.yml`'s `app-nonprod.environment`: add
  `APP_CORS_ALLOWED_ORIGINS: ${APP_CORS_ALLOWED_ORIGINS_NONPROD}`.
- `.env.nonprod`: set that value to the deployed nonprod frontend's real origin (e.g.
  `https://kanban-frontend-nonprod.example.com`), plus optionally the existing localhost dev origins
  if local frontend development against the nonprod backend should keep working.
- This is purely a deployment-time configuration value — **defer setting the real value until the
  frontend repo exists and its nonprod deploy target/hostname is known**, consistent with the
  quality gate's stub/defer instruction. Until then, either leave `APP_CORS_ALLOWED_ORIGINS_NONPROD`
  unset (safe default, same posture as prod today) or set it to an empty string once the codebase
  supports that meaning "no cross-origin browser access" — worth a one-line check during
  implementation, not asserted here.
- Prod's own CORS allow-list (still defaulting to localhost) is a separate, later concern — out of
  this milestone's scope per `PROJECT.md`, noted only so it isn't mistaken for having been handled
  by this change.

## New vs. Modified Files — explicit list

| File | New or Modified | What changes |
|------|------------------|---------------|
| `docker-compose.nonprod.yml` | **New** | Own `name:` pin, `app-nonprod` + `redpanda-nonprod` services, `edge` external network, no `caddy` service, no published host ports, resource caps sized per the budget note below |
| `.env.nonprod.example` | **New** (committed) | Mirrors `.env.prod.example`'s shape: `DB_HOST_NONPROD`/etc. placeholders, `APP_DOMAIN_NONPROD`, `APP_CORS_ALLOWED_ORIGINS_NONPROD` |
| `.env.nonprod` | **New** (never committed, VM-only) | Real values, created on the VM the same way `.env.prod` was |
| `docker-compose.prod.yml` | **Modified** | `caddy.environment` gains `APP_DOMAIN_NONPROD`; `caddy.networks` gains `edge`; top-level `networks: { edge: { external: true } }` block added. No other service touched. |
| `Caddyfile` | **Modified** | Second site block added for `{$APP_DOMAIN_NONPROD}` |
| `.env.prod` | **Modified** (VM-only, never committed) | Add `APP_DOMAIN_NONPROD=<value>` |
| `.github/workflows/deploy.yml` | **Modified** | Two new sibling jobs: `flyway-verify-nonprod`, `deploy-to-nonprod`; optionally widen `cleanup-old-images`'s `needs:` |
| Repo secrets (GitHub) | **New entries** | `DB_HOST_NONPROD`, `DB_NAME_NONPROD`, `DB_USER_NONPROD`, `DB_PASS_NONPROD` — reuse existing `NETCUP_*` secrets for SSH, no new SSH identity |
| `docs/INFRA_RUNBOOK.md` | **Modified** | Record the new DuckDNS subdomain, Neon nonprod branch details, the one-time `docker network create edge` bootstrap, and the new secrets — this file's own maintenance note requires this |
| `docs/INFRA_ARCHITECTURE.md` (+ its `.mmd` diagram sources) | **Modified** | This file's own Maintenance Note explicitly requires an update whenever a job is added to `deploy.yml` or a fact about what runs where changes — both are true here |
| `src/main/java/.../CorsConfig.java` | **Unchanged** | Already externalized; zero code change needed |

## Build Order

1. **Decide the resource budget** (see below) — whether prod's existing `mem_limit`/`--memory` caps
   get tightened to make room, before sizing nonprod's caps. Blocks steps 6–7.
2. **Provision the Neon nonprod branch** (Neon dashboard/API) — independent of the VM, zero
   resource cost, can happen anytime, in parallel with step 3.
3. **Register the second DuckDNS subdomain**, A record to the same VM IP — independent, can happen
   in parallel with step 2.
4. **Bootstrap the shared `edge` Docker network on the VM** (`docker network create edge`) —
   one-time, must exist before either compose file that references it as `external: true` is
   brought up with that network attached. Blocks steps 5 and 7.
5. **Modify `docker-compose.prod.yml`** (edge network + `APP_DOMAIN_NONPROD` env) and
   **`Caddyfile`** (second site block) together, then `docker compose up -d caddy` on the VM to
   apply — this only recreates `caddy` (matches the existing "only what changed gets recreated"
   behavior already documented for this stack). Do this *before* `app-nonprod` needs to be
   reachable, but it's harmless to land even before nonprod exists (the site block will just 502
   until `app-nonprod` is up).
6. **Author `docker-compose.nonprod.yml`** — depends on step 2 (DB secrets to put in `.env.nonprod`)
   and step 4 (edge network must exist to reference).
7. **Manual first deploy of the nonprod stack to the VM**, mirroring this project's own proven
   pattern (prod was deployed by hand first — plan 05-04 — and automated second — plan 05-05).
   Verify HTTPS reachability through the new hostname, Flyway migration success against the Neon
   nonprod branch, before wiring CI at all.
8. **Extend `deploy.yml`** with `flyway-verify-nonprod` + `deploy-to-nonprod`, add the new repo
   secrets. Only do this after step 7 has proven the manual path works — same discipline this
   project already applied to prod, and the reason step 7 exists as a distinct step at all.
9. **Wire CORS** — deferred until the frontend repo exists and its nonprod deploy origin is known;
   not a blocking step for anything else in this list. Nothing here needs the frontend repo to exist
   first except this one item.
10. **Cross-repo E2E trigger** — explicitly not part of this build order (see Integration Point 2).
    When the frontend repo exists, its own CI targets the now-stable nonprod hostname directly.

## Resource Budget — a real constraint on this VM, not a formality

Measured (2026-08-16/17, `docs/INFRA_RUNBOOK.md` Task 3 and the log-rotation task): host has 7.8GiB
RAM total. Prod's worst-case configured ceiling is already `app` 3g + `redpanda` 2200m = ~5.15G,
leaving ~2.65GiB nominally free — but *measured* real usage under a real burst was far lower:
`app` ~471MiB (15.3% of its cap), `redpanda` ~347.8MiB (under 18% of its cap), `caddy` ~20MiB. The
caps were left unchanged in Task 3 specifically because no measurement justified tightening them —
but that finding is directly relevant here: **there is real, measured headroom between prod's
caps and prod's actual usage**, which is the honest source of budget for nonprod, not the nominal
"2.65GiB free" figure alone.

Two configuration options, not one:

| Option | Description | Resource cost | Isolation guarantee |
|--------|--------------|----------------|-----------------------|
| **A — separate `redpanda-nonprod` broker** (recommended) | Nonprod gets its own broker + Schema Registry, own topics by construction | Adds a second Redpanda footprint (idle-measured prod figure: ~350MiB actual, cap TBD — recommend measuring a shrunk `--smp 1 --memory 512M` config the same way Task 3 measured prod, don't assume a number works without a live check, since prod's own history shows `--memory` and `mem_limit` need real headroom between them or the broker refuses to start) | Genuine — separate broker, separate topics, matches PROJECT.md's "Kafka/topic isolation" phrasing directly |
| **B — shared broker, topic-prefixed** | Reuse prod's existing `redpanda`, give nonprod's topics an env-derived name prefix | Near-zero extra memory | Weaker — isolation is a naming convention enforced by app config, not physical separation; also requires an application code change (topic name construction becomes environment-aware) — out of pure-infra scope |

**Recommendation: Option A**, sized conservatively and *measured* before being trusted, exactly the
way prod's own caps were validated in Task 3 rather than asserted. If the combined worst-case
ceiling (`app` 3g + `redpanda` 2200m + `app-nonprod` ~1g + `redpanda-nonprod` ~700m ≈ 6.9G) leaves
too little headroom for Caddy + the OS against the measured 7.8GiB, tighten prod's `app`/`redpanda`
caps first, using the same live-measurement discipline (a real burst, `docker stats` before/after,
confirm the healthcheck still passes) rather than shrinking blind — this VM has already demonstrated
once that a cap set exactly equal to a service's internal memory request breaks startup rather than
degrading gracefully.

## Anti-Patterns to Avoid

### Anti-Pattern 1: A second Caddy container for nonprod
**What people do:** stand up a second reverse proxy in the nonprod compose project "for symmetry
with prod."
**Why it's wrong:** two processes cannot both bind host 80/443 on one VM — this is the actual
collision the milestone's own question is asking about, and there is no port remapping that
preserves genuine public HTTPS on the standard ports for both.
**Do this instead:** one shared Caddy instance (owned by the prod project, as it already is),
extended with a second site block reaching across the `edge` network.

### Anti-Pattern 2: Directory-derived Compose project names
**What people do:** rely on Compose's default (CWD-basename) project naming and just keep the two
compose files in different directories.
**Why it's wrong:** this project has already been bitten by exactly this failure mode once — see
`docs/INFRA_RUNBOOK.md`'s plan 05-05 incident, where a directory move silently started a second,
unrelated project and lost the registered Avro schemas.
**Do this instead:** an explicit `name:` pin at the top of every Compose file, independent of where
it happens to sit on disk.

### Anti-Pattern 3: Gating prod's deploy job on nonprod's success
**What people do:** make `deploy-to-netcup` depend on `deploy-to-nonprod` succeeding first, reasoning
"nonprod should be the canary."
**Why it's wrong:** this directly couples prod's deploy latency and risk to nonprod's health — a
transient nonprod-only failure (its own Neon branch hiccup, its own SSH blip) would now block
production, which the quality gate explicitly warns against.
**Do this instead:** disjoint `needs:` graphs and disjoint `concurrency:` groups, both deploys
depending only on the shared build/verify stage, running in parallel.

## Sources

- This repository's own `docker-compose.prod.yml`, `Caddyfile`, `.github/workflows/deploy.yml`,
  `docs/INFRA_ARCHITECTURE.md`, `docs/INFRA_RUNBOOK.md` — HIGH confidence, primary source, read in
  full for this research.
- [Caddyfile Tutorial — Caddy Documentation](https://caddyserver.com/docs/caddyfile-tutorial) —
  multiple site blocks in one Caddyfile, HIGH confidence, official docs.
- [Automatic HTTPS — Caddy Documentation](https://caddyserver.com/docs/automatic-https) — per-site
  independent certificate issuance, HIGH confidence, official docs.
- [Neon: Use database branches as environments](https://neon.com/branching/rethinking-the-database)
  and [Neon: Practical Guide to Database Branching](https://neon.com/blog/practical-guide-to-database-branching) —
  branch-per-environment, own compute, scale-to-zero — MEDIUM-HIGH confidence, official vendor docs.
- [DuckDNS subdomain limits — Medium writeup](https://medium.com/@4get.prakhar/connecting-your-local-network-to-the-internet-with-duckdns-f8179f7cc20e) —
  5 subdomains/account free tier — MEDIUM confidence, single corroborating secondary source, not
  DuckDNS's own docs page; low-risk claim to verify directly against the DuckDNS site when
  registering the second subdomain.
- [GitHub Docs — Deploying with GitHub Actions / control-deployments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/control-deployments) —
  environments, trigger-conditioning patterns — MEDIUM confidence, official docs, general guidance
  rather than this project's specific job-graph recommendation (that recommendation is this
  document's own design judgment, reasoned from this repo's actual constraints, not lifted from a
  source).

---
*Architecture research for: nonprod/staging environment integration onto an existing single-VM,
single-pipeline production stack*
*Researched: 2026-08-17*
