# Phase 11: Migrate database from Neon to self-hosted Postgres - Research

**Researched:** 2026-08-26
**Domain:** Docker Compose infra migration (managed Postgres → self-hosted Postgres 16), HikariCP/JDBC pool tuning, GitHub Actions CI verification against a private network
**Confidence:** MEDIUM

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Topology**
- **D-01:** One shared Postgres 16 instance serving both production and nonprod, as two
  separate databases with separate least-privilege Postgres roles (the nonprod role cannot
  connect to the production database) — not two fully separate containers mirroring the
  existing `redpanda`/`redpanda-nonprod` split. — **Reversibility:** costly.
- **D-02:** Postgres version 16, matching what `docker-compose.yml` (local dev) and the
  Testcontainers-backed test suite already run — not Postgres 18 (what Neon ran). —
  **Reversibility:** costly.
- **D-03:** No host port published for Postgres — internal-only on the Compose network,
  mirroring Redpanda's existing INFRA-08 rule (no Kafka port ever internet-facing). Admin
  access (backups, ad-hoc `psql`) happens via SSH + `docker exec` on the VM.

**Data migration & cutover**
- **D-04:** Fresh start — do NOT carry over existing data from Neon via `pg_dump`/`pg_restore`.
  Flyway (V1–V8) builds the schema from scratch against the new empty self-hosted instance. —
  **Reversibility:** one-way.
- **D-05:** No downtime/timing constraint on the cutover window — production is already
  unreachable (Neon's quota block), so there is no live traffic to protect during migration.
- **D-06:** Production and nonprod migrate together, in this same phase — not production
  first with nonprod deferred to a follow-up.
- **D-07:** Delete the Neon project once the self-hosted cutover is verified working — not
  kept around dormant as a rollback path. — **Reversibility:** one-way. Do NOT delete the Neon
  project until the self-hosted instance is confirmed working end-to-end.

**VPS resource budget & Hikari/JDBC pool re-tuning**
- **D-08:** Measure the new Postgres container's real memory floor live — the same
  restart-ladder measurement discipline already used for both Redpanda containers (NONPROD-06,
  05-04 Task 3) — rather than assigning a `mem_limit` by arithmetic guess.
- **D-09:** Revisit the Neon-specific Hikari/JDBC tuning in `application.properties` as part of
  this phase: `connection-timeout=30000`, `minimum-idle=0`/`keepalive-time=0`,
  `sslmode=require&channel_binding=require`, and `prepareThreshold=0`. A local same-host
  Postgres has no cold start, no autosuspend, and no PgBouncer in front of it, so this
  rationale no longer applies as-is — don't carry it forward unexamined.
- **D-10:** Also re-measure the app container's own `mem_limit` (currently `3g`) alongside the
  Postgres measurement pass in D-08, since DB latency drops from a network hop to
  local/Unix-socket.
- **D-11:** Default Postgres's own configuration to a conservative profile (small
  `shared_buffers`, small `work_mem`, low `max_connections`) rather than generic
  dedicated-server defaults (e.g. the commonly-cited 25%-of-RAM `shared_buffers` guidance) —
  this is a shared 4-vCPU/7.8G VPS already running a JVM app and a Kafka broker.

**Backups & CI Flyway-verify**
- **D-12:** Document the loss of Neon's built-in point-in-time recovery in
  `docs/INFRA_RUNBOOK.md`, but do NOT build automated backup tooling as part of this phase. —
  **Reversibility:** reversible.
- **D-13:** Replace deploy.yml's `flyway-verify`/`flyway-verify-nonprod` jobs (which currently
  reach Neon's public direct endpoint straight from GitHub Actions runners) with an SSH tunnel
  from the runner into the VM's internal Docker network, reusing the existing SSH deploy
  credentials — not dropping the pre-merge check, and not spinning up a throwaway ephemeral
  Postgres in CI instead. — **Reversibility:** costly.

### Claude's Discretion
None — every gray area surfaced during discussion had an explicit user decision (no "you
decide" was selected on any final answer).

### Deferred Ideas (OUT OF SCOPE)
- Automated backup tooling for the self-hosted Postgres instance (scheduled `pg_dump`, tested
  restore procedure, retention policy) — explicitly deferred by D-12 to a future phase/todo;
  only documentation of the gap ships in this phase.
- Re-splitting the shared Postgres instance into two fully separate containers (full parity
  with the Redpanda prod/nonprod split) — not ruled out permanently, just not chosen now (D-01)
  given the VPS's current memory budget; worth revisiting if nonprod's workload ever grows
  enough to risk starving production's connections.
</user_constraints>

## Summary

This phase replaces Neon (managed, autoscaling Postgres) with a single self-hosted Postgres 16
container on the existing Netcup VPS, serving both production and nonprod as two databases with
two least-privilege roles. Unlike the Redpanda precedent (two fully separate broker instances, one
per Compose project), D-01 deliberately chose ONE shared instance — which means, unlike Redpanda,
the new `postgres` service cannot simply live inside each Compose project's own private default
network. It must sit on a new externally-created Docker network (mirroring the already-proven
`kanban-edge` pattern that lets production's Caddy reach nonprod's `app-nonprod` container across
Compose-project boundaries today) so both `docker-compose.prod.yml`'s `app` and
`docker-compose.nonprod.yml`'s `app-nonprod` can resolve and reach it by service name.

Provisioning two databases and two roles automatically on first boot is a solved problem via the
official `postgres` image's `/docker-entrypoint-initdb.d/` init-script mechanism — but a real,
easy-to-miss gotcha applies: PostgreSQL grants `CONNECT` on every database to `PUBLIC` by default,
so creating two roles and two databases does **not** by itself stop the nonprod role from
connecting to the production database (D-01's explicit requirement) — the init script must
explicitly `REVOKE CONNECT ... FROM PUBLIC` per database. The Hikari/JDBC re-tuning (D-09) has four
genuinely separable pieces, each with an independent expiry date now that the DB is local: the
`sslmode=require`/`channel_binding=require` pair should be **dropped entirely** (the target has no
TLS listener, matching local dev's own Postgres — leaving it in place would break every connection
attempt at SSL negotiation, not just be redundant); `prepareThreshold=0`'s entire rationale (PgBouncer
transaction-mode pooling) disappears with no pooler in front of the DB at all; `connection-timeout`
and `max-lifetime` were both explicitly reasoned around Neon's cold-start/reaping windows and should
be revisited on their own merits for a local socket-speed connection. For D-13's CI-side Flyway
verification, this repo already has a proven, lower-risk template for "run a one-off container
against the internal Compose network via SSH" — the `register-schemas-production` job — which is a
safer adaptation than a literal `ssh -L` port-forward, since Postgres has no published host port
(D-03) for a tunnel to target without either exposing a loopback-only port (a narrow exception to
D-03) or resolving the container's internal bridge IP dynamically on every run.

**Primary recommendation:** Add the shared `postgres` service to `docker-compose.prod.yml` on a new
external network (e.g. `kanban-db`) joined by both Compose projects' app services; provision both
databases/roles via a `/docker-entrypoint-initdb.d/*.sh` script that explicitly revokes default
`PUBLIC` `CONNECT` privileges; drop `sslmode`/`channel_binding`/`prepareThreshold=0` from
`DB_JDBC_PARAMS` entirely (falling back to the local-safe application.properties default); replace
`flyway-verify`/`flyway-verify-nonprod` with jobs that run the existing `flyway/flyway:11.7.2` CLI
as a one-off container over SSH, attached to the shared network, rather than a literal port-forward;
and default Postgres's own tuning to its stock conservative values (`shared_buffers`/`work_mem` left
at their small built-in defaults, `max_connections` capped well below the default 100) rather than
any dedicated-server-oriented guidance.

## Project Constraints (from CLAUDE.md)

Extracted from `.claude/CLAUDE.md` (project instructions) — the planner must not recommend an
approach that contradicts these:

- **Tech stack**: Spring Boot 3.5.16, Java 21, Spring Data JPA/Hibernate, PostgreSQL for both
  production and tests — **no new frameworks introduced for this scope**. This phase is
  Docker Compose/CI/config-only and introduces no new Gradle dependency, consistent with this.
- **Format/test gates**: `./gradlew spotlessCheck` and `./gradlew test` must pass before any
  commit — applies to the `application.properties` edit (D-09) even though the rest of this
  phase is infra-config, not Java source.
- **PR discipline**: This work should remain reviewable as its own unit.
- **Secret scanning**: `.githooks/pre-commit` runs a pinned `gitleaks` scan of the staged diff
  first — a real credential-shaped value (a generated Postgres role password, an SSH key
  fragment, etc.) pasted anywhere in a staged file, including `.planning/` prose or a
  `docker-compose.*.yml` example, will be refused before formatting/test checks run. Generated
  role passwords (Pitfall 5) must go into the never-committed `.env.*` files only, never
  into a committed Compose file, migration script, or planning doc.
- **GSD workflow enforcement**: file-changing work for this phase should go through
  `/gsd-execute-phase`, not direct ad hoc edits.
- **GSD execution directives** (this project's own addendum, applies to the eventual PLAN.md,
  not to this RESEARCH.md): before any PLAN.md is approved, it must document 2 alternate
  technical approaches considered, a 3-column trade-off matrix (Approach | Pros/Cons | Why
  Picked), and any non-obvious performance/memory/security trade-offs — this research's
  "Alternatives Considered" table and the Pitfalls above are written to directly feed that
  requirement (e.g. Pitfall 4's one-off-container-vs-literal-tunnel choice is exactly the shape
  of trade-off that table needs to capture).
- **Local dev server bring-up** (`.claude/CLAUDE.md`'s own documented pattern): for any live
  inspection of the new Postgres bring-up (confirming roles/databases exist, testing the
  `REVOKE CONNECT` isolation), prefer bringing up the real Compose stack and using `psql`/`curl`
  directly over writing a throwaway JUnit probe class — matches this repo's own stated
  preference against paying the full Gradle/Spotless/coverage pipeline cost for one-off manual
  verification.
- **Docs conventions**: any new `docs/INFRA_RUNBOOK.md` section should follow the file's
  existing "one dated section per plan/task, measured evidence inline" structure (D-08/D-10's
  measurement results, D-12's backup-gap documentation) rather than a rewritten summary.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Postgres data persistence (2 DBs, 2 roles) | Database / Storage | — | Self-hosted Postgres 16 container replacing Neon; owns all domain + session data for both environments |
| Cross-Compose-project network reachability | Database / Storage (network topology) | API/Backend (`app`/`app-nonprod` clients) | A new external Docker network is infrastructure the DB tier must expose; the app tier only consumes it, same relationship `kanban-edge` already establishes for Caddy↔app-nonprod |
| Connection pooling / JDBC tuning | API / Backend | — | HikariCP lives inside the Spring Boot app process; re-tuning is entirely an `application.properties`/env-var change, no DB-side config needed beyond `max_connections` |
| Pre-merge schema verification | CI (GitHub Actions) | Database / Storage (target) | Flyway-verify jobs run entirely in CI; they only need network line-of-sight into the VM's internal Docker network, not a code change on the DB side |
| Resource/memory budgeting | Database / Storage (own `mem_limit`) | Deployment / VPS host | Postgres's own cgroup cap and `postgresql.conf` profile are DB-tier; the *measurement methodology* (restart ladder) is a deployment/ops concern shared with the existing Redpanda precedent |
| Backup/PITR (explicitly deferred, D-12) | Database / Storage | Deployment / VPS host (cron, if built later) | Documented as a gap in this phase, not built — still correctly scoped to the DB tier for any future work |

## Standard Stack

### Core

| Component | Version | Purpose | Why Standard |
|-----------|---------|---------|---------------|
| `postgres` (official Docker image) | `16` | Self-hosted database engine, both prod and nonprod databases | [VERIFIED: docker-compose.yml:3] — already the exact image/tag this repo's local dev and Testcontainers-backed test suite run (`postgres:16`), matching D-02's explicit "match what local dev/tests already run" decision |
| `flyway/flyway` (official Docker image) | `11.7.2` | CI-side pre-merge migration verification (D-13 target) | [VERIFIED: .github/workflows/deploy.yml:196] — already pinned and proven working in `flyway-verify`/`flyway-verify-nonprod`; reused as-is, only its target endpoint changes |

No new application-level (Gradle/Java) dependencies are introduced by this phase — it is exclusively
Docker Compose, `application.properties`, and CI-workflow changes.

### Supporting

| Tool | Version | Purpose | When to Use |
|------|---------|---------|-------------|
| `docker-entrypoint-initdb.d/*.sh` init script | n/a (built into the official image) | Provision both databases + both least-privilege roles on first container boot | Runs automatically once, only when the Postgres data directory is empty — see Pitfall 1 |
| `pg_isready` | ships with the `postgres` image | Compose healthcheck for the new `postgres` service | [CITED: github.com/docker-library/postgres] — the image's own documented healthcheck pattern is `pg_isready -U <user>` |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| One shared Postgres instance (D-01, locked) | Two fully separate Postgres containers (prod/nonprod), mirroring Redpanda | Rejected in CONTEXT.md already — costs more VPS memory the box doesn't have budget for, and Neon's failure mode (shared-quota billing) has no self-hosted analog that would justify the extra isolation cost |
| Literal `ssh -L` port-forward tunnel for CI Flyway verify (D-13's literal wording) | One-off Flyway container run inside an `appleboy/ssh-action` script, attached to the shared Compose network | The one-off-container approach is already proven in this exact repo (`register-schemas-production`) and needs zero new port exposure; a literal tunnel needs either a loopback-only port bind (a narrow exception to D-03) or a dynamically-resolved container IP on every CI run — see Pitfall 4 |
| `sslmode=require&channel_binding=require` kept "just in case" | Drop entirely, matching local dev's own Postgres (no TLS listener) | Keeping it would not merely be redundant — PgJDBC would attempt SSL negotiation against a server with no SSL listener configured and fail the connection outright (see Pitfall 2) |

**Installation:** No new package installs. Compose/YAML and `application.properties` changes only;
`postgres:16` and `flyway/flyway:11.7.2` are already pulled by this repo's existing local-dev and CI
workflows respectively.

## Package Legitimacy Audit

**Not applicable — no new external packages are installed by this phase.** Both Docker images this
phase relies on (`postgres:16`, `flyway/flyway:11.7.2`) are already in active use elsewhere in this
repository (`docker-compose.yml`'s local-dev `postgres` service; `.github/workflows/deploy.yml`'s
existing `flyway-verify`/`flyway-verify-nonprod` jobs) — both are official, first-party Docker Hub
images with no legitimacy signal to check beyond what this repo has already relied on for months.
The Package Legitimacy Gate protocol (npm/PyPI/crates registry checks) does not apply to Docker
base images and there is no Gradle/npm dependency added.

## Architecture Patterns

### System Architecture Diagram

```text
                         ┌─────────────────────────────────────────┐
                         │        External network: kanban-db       │
                         │   (created once via `docker network      │
                         │    create kanban-db`, mirrors the        │
                         │    already-proven kanban-edge pattern)   │
                         └───────────┬───────────────┬─────────────┘
                                     │               │
   docker-compose.prod.yml          │               │   docker-compose.nonprod.yml
   (Compose project:                │               │   (Compose project:
    kanban-board-backend)           │               │    kanban-board-nonprod)
   ┌───────────────────────┐        │               │        ┌───────────────────────────┐
   │  postgres (NEW)        │◄──────┘               └───────►│  (no new service here —    │
   │  - db: kanban_prod     │  joins kanban-db as owner,       │   app-nonprod just joins   │
   │  - db: kanban_nonprod  │  registers service-name DNS      │   kanban-db as a consumer, │
   │  - role: prod_role     │  alias "postgres" on it          │   same pattern as its      │
   │    (CONNECT only to    │                                   │   existing kanban-edge     │
   │     kanban_prod)       │                                   │   alias for Caddy)         │
   │  - role: nonprod_role  │                                   └──────────────┬─────────────┘
   │    (CONNECT only to    │                                                  │
   │     kanban_nonprod)    │                                                  │
   │  mem_limit: <measured, │                                                  │
   │   D-08 restart ladder> │                                                  ▼
   │  NO ports: published   │                                    app-nonprod → postgres:5432/
   │  (D-03)                │                                    kanban_nonprod (own role)
   └───────────┬────────────┘
               │  also on the project's own `default`
               │  network (unchanged)
               ▼
   app → postgres:5432/kanban_prod (own role)
   redpanda → (unchanged, still project-local, no kanban-db membership)
   caddy → (unchanged, still reaches app via `default`; kanban-edge unaffected)

   ─────────────────────────── CI (GitHub Actions) ───────────────────────────
   flyway-verify(-nonprod) job
     └─ appleboy/ssh-action script on the VM (existing SSH deploy identity)
          └─ `docker run --rm --network <postgres's network> \
                flyway/flyway:11.7.2 migrate` against `postgres:5432/kanban_{prod,nonprod}`
          (mirrors register-schemas-production's already-proven "one-off container attached
           to the live Compose network" pattern — no port ever forwarded to the runner)
```

### Recommended Project Structure

No new source directories — this phase's surface area is entirely infra-as-config:

```text
docker-compose.prod.yml          # add `postgres` service + `kanban-db` external network
docker-compose.nonprod.yml       # add `kanban-db` external network; app-nonprod joins it
docker-compose.yml                # unchanged (local dev keeps its own postgres:16, port 5433)
docker/                           # (new, optional) init scripts if not inlined as a Compose volume mount
  └── postgres-init/
      └── 01-create-databases-and-roles.sh
src/main/resources/application.properties   # Hikari block + DB_JDBC_PARAMS default revisited (D-09)
.github/workflows/deploy.yml     # flyway-verify / flyway-verify-nonprod jobs rewritten (D-13)
docs/INFRA_RUNBOOK.md            # new dated section(s): Postgres bring-up, D-08/D-10 measurement, D-12 backup-gap note
.env.prod.example / .env.nonprod.example   # DB_* variable meanings updated (local container, not Neon)
```

### Pattern 1: Cross-Compose-project shared service via an external network

**What:** A service defined in one Compose project (here: `postgres` in `docker-compose.prod.yml`)
is reached by a service in a *different* Compose project (`app-nonprod` in
`docker-compose.nonprod.yml`) by both joining one externally-created Docker network. Compose
auto-registers each attached service's own name as a DNS alias on every network it joins, so no
extra `aliases:` block is needed on the `postgres` side (only the consumer side, `app-nonprod`,
optionally needs one if it wants a different hostname than nonprod already uses for something else
— not the case here).
**When to use:** Exactly D-01's situation — one physical resource, two isolated Compose projects
that both need to reach it, where merging the projects or duplicating the resource are both
rejected.
**Example (already proven working in this exact repo for Caddy↔app-nonprod):**
```yaml
# Source: docker-compose.prod.yml:52-56 (VERIFIED — read this session)
networks:
  default:
  kanban-edge:
    external: true
    name: kanban-edge

# Source: docker-compose.nonprod.yml:126,135-141 (VERIFIED — read this session)
services:
  app-nonprod:
    networks:
      default:
      kanban-edge:
        aliases:
          - app-nonprod
```
Apply the identical shape for `kanban-db`: `docker-compose.prod.yml` declares `kanban-db` as
`external: true` and joins it from the new `postgres` service (no explicit `aliases:` needed — the
service name `postgres` becomes the DNS name automatically); `docker-compose.nonprod.yml` declares
the same external network and joins it from `app-nonprod` only (not from `redpanda-nonprod`, which
has no reason to reach Postgres).

### Pattern 2: Automatic multi-database, multi-role provisioning via `docker-entrypoint-initdb.d`

**What:** The official `postgres` image runs every script under
`/docker-entrypoint-initdb.d/` (in lexical order) exactly once — only when the data directory is
empty on first boot. [CITED: github.com/docker-library/postgres] "Users can extend database setup
by placing SQL and shell scripts into the `/docker-entrypoint-initdb.d/` directory. These files are
executed in lexical order during the initial container startup."
**When to use:** Any time an image's single built-in `POSTGRES_DB`/`POSTGRES_USER` triple isn't
enough — here, two databases and two roles are needed from one container.
**Example (skeleton, mount as a read-only volume):**
```bash
#!/usr/bin/env bash
# Source: pattern derived from github.com/docker-library/postgres docs (CITED) —
# CREATE DATABASE / CREATE ROLE / GRANT are standard PostgreSQL DDL, not image-specific.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE ROLE "${PROD_DB_USER}" WITH LOGIN PASSWORD '${PROD_DB_PASS}';
    CREATE DATABASE "${PROD_DB_NAME}" OWNER "${PROD_DB_USER}";
    REVOKE CONNECT ON DATABASE "${PROD_DB_NAME}" FROM PUBLIC;
    GRANT CONNECT ON DATABASE "${PROD_DB_NAME}" TO "${PROD_DB_USER}";

    CREATE ROLE "${NONPROD_DB_USER}" WITH LOGIN PASSWORD '${NONPROD_DB_PASS}';
    CREATE DATABASE "${NONPROD_DB_NAME}" OWNER "${NONPROD_DB_USER}";
    REVOKE CONNECT ON DATABASE "${NONPROD_DB_NAME}" FROM PUBLIC;
    GRANT CONNECT ON DATABASE "${NONPROD_DB_NAME}" TO "${NONPROD_DB_USER}";
EOSQL
```
The `REVOKE CONNECT ... FROM PUBLIC` lines are not optional decoration — see Pitfall 3.

### Anti-Patterns to Avoid

- **Relying on `POSTGRES_DB`/`POSTGRES_USER` alone for two databases:** the official image only
  provisions one database/user pair from its built-in env vars; a second database/role always
  needs an explicit init script (Pattern 2) — there is no `POSTGRES_MULTIPLE_DATABASES` env var
  built into the official image (that pattern comes from an unofficial third-party gist, not the
  image itself, and this phase's own two-role/GRANT-scoped requirement needs custom SQL regardless).
- **Assuming two roles + two databases alone achieves isolation:** without the explicit `REVOKE
  CONNECT ... FROM PUBLIC`, both roles retain `PUBLIC`-granted `CONNECT` to both databases by
  default (Pitfall 3).
- **Publishing a loopback-only port "just for CI to tunnel through":** appears harmless (not
  internet-facing) but is a real, if narrow, exception to D-03's "no host port published" — worth a
  human-confirmed checkpoint before doing it, not a silent addition (Pitfall 4).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|--------------|-----|
| Multi-database/role provisioning on container first-boot | A custom entrypoint wrapper script that replaces the image's own `docker-entrypoint.sh` | The image's own `/docker-entrypoint-initdb.d/` hook (Pattern 2) | The official entrypoint already handles first-boot detection, `gosu`-based privilege drop, and ordered script execution — reimplementing any of that risks silently breaking on the next `postgres:16` patch release |
| CI-side migration verification tooling | A hand-rolled `psql`-based schema-diff script | The already-pinned `flyway/flyway:11.7.2` CLI image, redirected at the new target | This repo already proved this exact image/invocation works for `flyway-verify`; only the network path changes |
| Restart-ladder memory measurement | A one-off ad hoc "just try a number" `mem_limit` | The existing NONPROD-06/05-04-Task-3 restart-ladder methodology (`docs/INFRA_RUNBOOK.md`) | Already twice-proven in this exact repo (production Redpanda, nonprod Redpanda) — D-08/D-10 both explicitly require reusing it verbatim, not inventing a new methodology |

**Key insight:** Every piece of this phase already has a directly-analogous, already-proven pattern
somewhere in this same repository (Redpanda's cross-project network sharing precedent doesn't quite
apply since Redpanda is NOT shared — but Caddy↔app-nonprod's `kanban-edge` network sharing does;
the schema-registration one-off-container-over-SSH pattern; the restart-ladder measurement
methodology). The research finding most worth internalizing is less "here is new information" and
more "map each D-0x decision to the specific existing precedent in this repo that already solves
its mechanical half," since departing from those precedents without a stated reason is where risk
concentrates.

## Runtime State Inventory

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | Per D-04, no data is carried over from Neon — nothing to migrate. One real consequence worth naming explicitly: existing Spring Session JDBC rows (`spring_session`/`spring_session_attributes`) in Neon are abandoned along with everything else, so any user with an active session at cutover time is silently logged out (session cookie now points at a session id the new, empty DB has never heard of). Expected under D-05 ("no downtime/timing constraint... production is already unreachable"), but worth stating in `docs/INFRA_RUNBOOK.md` rather than leaving implicit. | Document the expected re-login as a stated consequence, not a bug, in the cutover section of `docs/INFRA_RUNBOOK.md` |
| Live service config | GitHub Environment secrets (`production`/`staging` environments) currently hold `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` pointed at Neon's direct endpoints — read by `flyway-verify`, `flyway-verify-nonprod`, and indirectly by `deploy-to-netcup`/`deploy-to-nonprod` (which pass them through to `.env.prod`/`.env.nonprod` on the VM, itself populated by the human, not by CI). The `flyway-verify` jobs' pooler guard (`if [[ "$DB_HOST" == *"-pooler"* ]]`) becomes dead/misleading code once D-13's replacement job targets a local hostname that will never carry a `-pooler` substring. | Update both GitHub Environment secret sets to the new local values (human action, off-repo); remove or repurpose the now-meaningless pooler guard when D-13's jobs are rewritten, rather than leaving it as inert but confusing code |
| OS-registered state | None found — this stack is entirely Docker-Compose-managed (no Task Scheduler/systemd/pm2/launchd registrations reference Neon or a Postgres hostname by name anywhere in this repo's tracked files). | None — verified by inspecting `docker-compose.prod.yml`, `docker-compose.nonprod.yml`, and `.github/workflows/deploy.yml` for any non-Compose process-registration step; none exists |
| Secrets/env vars | `.env.prod`/`.env.nonprod` on the VM (never committed) currently hold Neon's `DB_HOST`/`DB_USER`/`DB_PASS`/`DB_JDBC_PARAMS`. Two **new** secrets must be generated (not just edited) — passwords for the two new least-privilege Postgres roles created by the init script (Pattern 2) — and those same values must additionally exist in whichever `.env.*` file backs the Compose project that defines the `postgres` service, for the init script to consume at first boot (see Pitfall 5 — this is the one place D-01's "shared instance" choice pushes back against the two projects' otherwise-strict secret isolation). | Generate two new role passwords; write them into both (a) the init-script-consuming `.env.*` file and (b) the app-side `.env.*` file(s) that reference them as `DB_PASS`; update the GitHub Environment secrets in parallel |
| Build artifacts | None — no installed CLI, egg-info, or compiled binary in this repo carries a Neon-specific name or path that would go stale. | None — verified; this is a pure Java/Gradle backend with no Neon-specific tooling installed anywhere in the build |

## Common Pitfalls

### Pitfall 1: The init script only runs once, on a genuinely empty data directory

**What goes wrong:** A failed or partial first bring-up attempt (wrong env var, typo in the SQL)
leaves the `postgres-data` volume non-empty; every subsequent `docker compose up` silently skips
`/docker-entrypoint-initdb.d/` entirely, so the "fix" never actually reprovisions the missing
database/role — the operator sees the same failure indefinitely and may misdiagnose it as a script
bug rather than a stale volume.
**Why it happens:** [CITED: github.com/docker-library/postgres] the init sequence is gated on
"when the data directory is empty" — a documented, intentional one-shot design, not a bug.
**How to avoid:** On any first-bring-up failure, explicitly `docker compose down -v` (or remove
just the named `postgres-data` volume) before retrying, matching how this repo already treats a
first-boot failure for Redpanda (fresh named volumes, not reused ones, per `docker-compose.yml`'s
own comment about the Redpanda volume rename).
**Warning signs:** The same "role does not exist" / "database does not exist" error recurring
identically across multiple `docker compose up` attempts despite the init script clearly looking
correct.

### Pitfall 2: `sslmode=require` against a server with no SSL listener fails outright, not gracefully

**What goes wrong:** If production/nonprod's `DB_JDBC_PARAMS` override
(`docker-compose.prod.yml`/`docker-compose.nonprod.yml` `app`/`app-nonprod` environment block) is
left at its current Neon-era value (`sslmode=require&channel_binding=require&prepareThreshold=0`)
after cutover, PgJDBC will attempt an SSL negotiation the new self-hosted Postgres container has no
listener configured to answer — this is a hard connection failure at boot, not a silent
downgrade, and it will present as the exact same `total=0, active=0, idle=0` Hikari
connection-timeout signature the 2026-08-26 incident already diagnosed once (a fresh, self-inflicted
recurrence of the same symptom, different root cause).
**Why it happens:** [VERIFIED: docker-compose.yml:2-17] local dev's own `postgres:16` service
(the version/image D-02 explicitly says to match) has no TLS configuration anywhere in its
definition — self-hosted Postgres on the VPS will be provisioned the same way unless the plan
explicitly adds TLS (out of scope per CONTEXT.md, not mentioned in any D-0x decision).
**How to avoid:** Drop `sslmode=require&channel_binding=require` entirely from both
`docker-compose.prod.yml` and `docker-compose.nonprod.yml`'s `DB_JDBC_PARAMS` value — do not merely
soften it, remove it, falling back to `application.properties`' own local-safe default
(`prepareThreshold=0` alone, itself also revisited — Pitfall below).
**Warning signs:** App container reports `(unhealthy)` at boot with the identical
30000ms-connection-timeout Hikari message this repo's 2026-08-26 incident record already documents
verbatim.

### Pitfall 3: Two roles + two databases does not, by itself, achieve D-01's connect-isolation requirement

**What goes wrong:** The nonprod role can still open a connection to the production database (and
vice versa) even after `CREATE ROLE`/`CREATE DATABASE ... OWNER` — defeating the specific
requirement D-01 states ("the nonprod role cannot connect to the production database").
**Why it happens:** [CITED: postgresql.org/docs/17/ddl-priv.html] "PUBLIC is granted CONNECT and
TEMPORARY privileges on databases" by default — every role, including a newly-created one with no
explicit grants, inherits `CONNECT` on every database via the implicit `PUBLIC` pseudo-role unless
that default is explicitly revoked.
**How to avoid:** The init script must run `REVOKE CONNECT ON DATABASE <name> FROM PUBLIC;` for
each database, immediately followed by `GRANT CONNECT ON DATABASE <name> TO <its own role>;` (shown
in Pattern 2's skeleton). Verify by attempting a connection as the nonprod role against the
production database's name and confirming it is rejected — a live check the planner should turn
into a task, not just a code-review read-through.
**Warning signs:** A connection succeeds where it should be refused during the D-01 verification
step, or `\du`/`\l` in `psql` shows no per-database `ACL` override for either database (an
unmodified `=Tc/postgres`-only ACL means the revoke never ran).

### Pitfall 4: A literal SSH tunnel has no clean target without touching D-03's "no published port" rule

**What goes wrong:** Following D-13's literal "SSH tunnel" wording with a real `ssh -L
5432:<target>:5432` from the GitHub Actions runner has no simple, stable target: Postgres publishes
no host port at all (D-03), so `-L`'s remote target must be either (a) the container's internal
Docker bridge IP — resolved by the SSH *server* (the VM), not the local client, and it changes on
every container recreate, requiring a fresh `docker inspect` lookup per CI run — or (b) a
loopback-only host bind (`127.0.0.1:5432:5432`), which is not internet-facing but is still a literal
exception to D-03's stated "no host port published" rule.
**Why it happens:** [ASSUMED — derived from generic SSH/Docker networking behavior, not verified
against this repo's live VM this session] `ssh -L` always resolves its target `host:port` in the
SSH server's own network namespace, and Docker's embedded per-network DNS only resolves container
names for processes running *inside* that Docker network's namespace — the VM's own host resolver
does not know Compose service names.
**How to avoid:** Prefer the already-proven alternative this repo already uses for an equivalent
problem (`register-schemas-production`): run the Flyway CLI as a one-off container *inside* an
`appleboy/ssh-action` script, attached to the same Docker network the `postgres` service is on
(`docker run --rm --network kanban-db flyway/flyway:11.7.2 migrate ...`), so nothing is ever
forwarded to the runner and D-03 needs no exception at all. If a literal tunnel is genuinely
preferred, flag the loopback-only-port question as an explicit `checkpoint:human-verify` rather than
silently adding it.
**Warning signs:** A CI job that intermittently fails to resolve its tunnel target after a Postgres
container recreate (symptomatic of the dynamic-IP approach going stale) — or, if the loopback-port
route was taken without confirmation, someone later asking "why does Postgres have a `ports:` entry
when D-03 said none."

### Pitfall 5: D-01's single shared instance quietly weakens the two Compose projects' secret isolation

**What goes wrong:** `docker-compose.prod.yml` and `docker-compose.nonprod.yml` are deliberately
separate Compose projects with separate `.env.*` files specifically so neither environment's
secrets are visible to the other's deploy path (see that pair of files' own header comments on
identity isolation). A single shared Postgres container's init script, however, needs *both* the
prod and the nonprod role's credentials at once, at container first-boot — and that container is
defined in only one of the two files. Whichever `.env.*` file backs that Compose project now must
carry the *other* environment's DB credentials too, a real (if narrow and already implicitly
accepted by D-01) crack in the isolation the two-project split otherwise maintains.
**Why it happens:** A direct, structural consequence of D-01 ("one shared instance") — not a
planning oversight, but not called out explicitly in CONTEXT.md's decision list either.
**How to avoid:** Name the crossing-over variables distinctly (e.g. `POSTGRES_NONPROD_DB_PASS`
inside `.env.prod`, if `postgres` lives in `docker-compose.prod.yml` as recommended) so it is
visually obvious in the file itself that this one file now holds both environments' DB credentials,
rather than letting it look like an accidental leak. Surface this explicitly to the user/planner as
worth a one-line acknowledgment (not a re-litigation of D-01), since it is exactly the kind of thing
a security-conscious reviewer would otherwise flag as a regression.
**Warning signs:** A future secret-scanning or access-review pass treating this as a new finding,
when it is in fact D-01's already-accepted tradeoff, undocumented.

## Code Examples

### Postgres service skeleton for `docker-compose.prod.yml` (D-01/D-02/D-03/D-08)

```yaml
# Pattern derived from this repo's own Redpanda service shape (docker-compose.prod.yml:142-231,
# VERIFIED — read this session) and the official postgres image's documented healthcheck
# (CITED: github.com/docker-library/postgres). mem_limit below is a placeholder — D-08 requires
# a measured value via the restart-ladder methodology, not an arithmetic guess.
services:
  postgres:
    image: postgres:16
    hostname: postgres
    restart: unless-stopped
    mem_limit: <TBD — measured via D-08 restart ladder>
    environment:
      POSTGRES_USER: ${POSTGRES_SUPERUSER}
      POSTGRES_PASSWORD: ${POSTGRES_SUPERUSER_PASS}
      PROD_DB_NAME: ${DB_NAME}
      PROD_DB_USER: ${DB_USER}
      PROD_DB_PASS: ${DB_PASS}
      NONPROD_DB_NAME: ${NONPROD_DB_NAME}
      NONPROD_DB_USER: ${NONPROD_DB_USER}
      NONPROD_DB_PASS: ${NONPROD_DB_PASS}
    networks:
      - default
      - kanban-db
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./docker/postgres-init:/docker-entrypoint-initdb.d:ro
    # No `ports:` entry -- matches D-03 exactly (Redpanda's own comment: "neither the Kafka
    # listener nor the Schema Registry is published to the host").
    healthcheck:
      # Source: github.com/docker-library/postgres docs (CITED) — image's own documented pattern.
      test: [ "CMD-SHELL", "pg_isready -U ${DB_USER}" ]
      interval: 5s
      timeout: 5s
      retries: 8
      start_period: 15s
    logging: *default-logging

networks:
  kanban-db:
    external: true
    name: kanban-db
```

### Nonprod app service joining the shared network (`docker-compose.nonprod.yml`)

```yaml
# Source pattern: docker-compose.nonprod.yml:135-141 (VERIFIED — read this session), the existing
# kanban-edge join, applied identically to the new kanban-db network.
services:
  app-nonprod:
    networks:
      default:
      kanban-edge:
        aliases:
          - app-nonprod
      kanban-db:   # NEW — reaches the shared `postgres` service by its Compose-registered name
    environment:
      DB_HOST: postgres
      DB_PORT: ${DB_PORT:-5432}
      DB_NAME: ${NONPROD_DB_NAME}
      DB_USER: ${NONPROD_DB_USER}
      DB_PASS: ${NONPROD_DB_PASS}
      # DB_JDBC_PARAMS dropped entirely (Pitfall 2) — falls back to application.properties'
      # local-safe default.

networks:
  kanban-db:
    external: true
    name: kanban-db
```

### CI Flyway verification without a published port (D-13)

```yaml
# Pattern: adapted from the already-proven register-schemas-production job
# (.github/workflows/deploy.yml:337-370, VERIFIED — read this session), same SSH identity and
# script style, retargeted at Flyway instead of the schema registrar.
- name: Verify Flyway migrations apply cleanly (self-hosted Postgres, via SSH)
  uses: appleboy/ssh-action@0ff4204d59e8e51228ff73bce53f80d53301dee2  # v1.2.5, digest-pinned per D-05
  with:
    host: ${{ secrets.NETCUP_HOST }}
    username: ${{ secrets.NETCUP_DEPLOY_USER }}
    key: ${{ secrets.NETCUP_SSH_KEY }}
    fingerprint: ${{ secrets.NETCUP_HOST_FINGERPRINT }}
    script: |
      set -e
      docker run --rm --network kanban-db \
        -e FLYWAY_URL="jdbc:postgresql://postgres:5432/${{ secrets.DB_NAME }}" \
        -e FLYWAY_USER="${{ secrets.DB_USER }}" \
        -e FLYWAY_PASSWORD="${{ secrets.DB_PASS }}" \
        -v /opt/deploy/kanban-board-backend/db/migration:/flyway/sql:ro \
        flyway/flyway:11.7.2 migrate
```
This needs the migration `.sql` files present on the VM (copied by the existing SCP step, or an
extra one) since `docker run --network kanban-db` runs *on the VM*, not on the GitHub runner —
unlike today's `flyway-verify`, which runs the Flyway container directly on the runner against a
public Neon endpoint. This is the real cost of the SSH-based approach versus the runner-side
approach: the checked-out migration directory must additionally be copied to the VM (or the
`db/migration` directory bind-mounted from the deploy checkout the SCP step already manages).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|-------------------|---------------|--------|
| Neon managed Postgres, autoscaling 0.25-2 CU, `suspend_timeout_seconds: 0` | Self-hosted Postgres 16, single fixed-size container | This phase | Removes the per-project shared compute-hour quota mechanism entirely (no analogous limit on self-hosted); trades scale-to-zero cost savings for a fixed, measured `mem_limit` |
| `sslmode=require&channel_binding=require` JDBC params (public-internet hop to Neon) | No TLS params (internal Docker bridge network hop) | This phase | Removes SCRAM-SHA-256-PLUS hardening — acceptable because the hop no longer crosses the public internet, matching local dev's own already-accepted no-TLS posture |
| `prepareThreshold=0` (PgBouncer transaction-pooling workaround) | No pooler in front of the DB at all | This phase | [ASSUMED — LOW confidence, not verified against this repo's live behavior this session] Removing `prepareThreshold=0` may re-enable PgJDBC's normal server-side prepared-statement reuse (default threshold 5), a potential minor performance win worth verifying, not assuming |
| CI Flyway verification against a public Neon endpoint from the runner | CI Flyway verification via SSH into the VM's internal Docker network | This phase (D-13) | Removes the last reason `deploy.yml` needs a public, non-VPN-gated database endpoint reachable from GitHub-hosted runners |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|-----------------|
| A1 | Generic HikariCP tuning advice (minimum-idle=5, idle-timeout=600000, max-lifetime=1800000, keepalive-time=300000) surfaced by a plain web search reflects a reasonable *starting point*, not a validated recommendation for this repo's own low-traffic shape. | Not directly used in the final skeleton above — flagged here specifically so it is NOT copied into a plan uncritically. | A planner copying these numbers without re-deriving them against this repo's own maximum-pool-size=5 and near-zero-traffic reality would repeat the exact "trust the doc/blog, don't verify against source" mistake this repo's own incident record explicitly warns against (see application.properties:118-120's "Do not trust HikariCP's own Javadoc on this" note). |
| A2 | `ssh -L`'s remote target is resolved by the SSH server, not the local client, and Docker's embedded per-network DNS does not resolve container/service names from the host's own network namespace. | Pitfall 4 | If wrong, a literal SSH-tunnel approach might be simpler than researched (e.g. if the VM's host resolver can be configured to see Compose DNS) — worth a quick live check on the actual VM before committing to either approach in the plan, rather than assuming Pitfall 4's analysis is the final word. |
| A3 | Removing `prepareThreshold=0` (no pooler in front of Postgres) is safe and potentially beneficial for PgJDBC prepared-statement reuse. | State of the Art table | If some other component in this stack still expects `prepareThreshold=0`'s behavior (unverified), removing it could surface an unexpected `PSQLException` — should be smoke-tested against a real self-hosted instance before being treated as settled, not assumed safe purely from the PgBouncer-workaround reasoning. |
| A4 | The recommended compose-file ownership (put the shared `postgres` service in `docker-compose.prod.yml`, not `docker-compose.nonprod.yml` or a new third file) is the right call. | Architecture Patterns, Pattern 1 | This specific placement was not one of CONTEXT.md's 13 locked decisions — it is this research's own recommendation, grounded in the existing `kanban-edge` precedent (production already "owns" cross-project shared infra). A planner or user could reasonably choose a dedicated third Compose file instead; worth a one-line confirmation at plan time rather than treated as pre-locked. |

## Open Questions

1. **Which Compose file should physically define the `postgres` service?**
   - What we know: D-01 requires one shared instance; the existing `kanban-edge` precedent shows
     production's Compose file already hosts cross-project shared infrastructure (Caddy).
   - What's unclear: Whether the user has a preference for a dedicated third `docker-compose.db.yml`
     file instead, to avoid conflating "production's own stack" with "shared cross-environment
     infra" in one file.
   - Recommendation: Default to `docker-compose.prod.yml` (Assumption A4) unless the planner or user
     raises a preference for a dedicated file during plan review.

2. **Literal SSH tunnel vs. one-off-container-over-SSH for D-13.**
   - What we know: Both are technically viable; the one-off-container approach has a directly-proven
     precedent in this exact repo (`register-schemas-production`) and needs no exception to D-03.
   - What's unclear: Whether D-13's literal "SSH tunnel" wording was meant prescriptively (the user
     specifically wants port-forwarding semantics) or descriptively (the user meant "reach it via
     SSH, however that's best implemented").
   - Recommendation: Treat the one-off-container approach as the default recommendation (Pitfall 4)
     but flag this choice explicitly for confirmation during planning rather than silently deciding
     it, since CONTEXT.md's own D-13 wording is genuinely ambiguous on this specific mechanism.

3. **Exact `mem_limit`/`postgresql.conf` values for the new `postgres` service.**
   - What we know: D-08 requires a live restart-ladder measurement (not arithmetic); D-11 requires a
     conservative profile relative to a 4-vCPU/7.8G VPS already running a JVM app and Kafka broker.
   - What's unclear: The actual measured floor — this can only be determined by executing the
     measurement, not researched in advance.
   - Recommendation: The plan should include an explicit measurement task reusing the exact
     methodology in `docs/INFRA_RUNBOOK.md`'s "Nonprod resource measurement — Plan 08-03" section,
     starting the ladder from a conservative initial guess (e.g. `256M`–`512M` internal Postgres
     `shared_buffers`-driven footprint is typically far smaller than Redpanda's Seastar-based
     footprint, so the ladder likely bottoms out lower, but this is not asserted as fact here).

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|--------------|-----------|----------|-----------|
| Docker / Docker Compose on the Netcup VM | New `postgres` service bring-up | ✓ (already running the full prod+nonprod stack) | Unconfirmed exact version this session — not re-probed, already proven functional by every prior phase's deploy | — |
| `postgres:16` image pullable from Docker Hub | New service | ✓ (already pulled and running in local dev's `docker-compose.yml`) | `16` | — |
| `flyway/flyway:11.7.2` image pullable from Docker Hub | D-13's CI verification job | ✓ (already pulled and running in existing CI jobs) | `11.7.2` | — |
| SSH access to the VM (existing `NETCUP_*` secrets) | D-13's CI job, D-08 measurement work | ✓ (already used by every existing deploy job) | — | — |
| `docker network create kanban-db` executed once on the VM | Pattern 1's cross-project sharing | Not yet done — a one-time manual/CI-bootstrap step, same as `kanban-edge`'s own one-time creation | — | Plan must include this as an explicit task, mirroring however `kanban-edge` was originally created (not documented in this session's reading — worth confirming during planning whether it was a manual SSH command or scripted) |

**Missing dependencies with no fallback:** None identified — every dependency this phase needs is
already present and proven elsewhere in this repository's own infrastructure.

**Missing dependencies with fallback:** `kanban-db` network creation is a one-time setup step, not
a missing tool — flagged so the plan doesn't silently assume it already exists.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|----------------|---------|--------------------|
| V1 Architecture | yes | Network segmentation via Docker Compose networks (no host port published for Postgres, per D-03); cross-project sharing scoped to exactly the two consumers that need it (`kanban-db`), not a blanket shared network |
| V4 Access Control | yes | Least-privilege Postgres roles, one per environment, with `PUBLIC` `CONNECT` explicitly revoked per database (Pitfall 3) — this is the concrete mechanism realizing D-01's "nonprod role cannot connect to production database" requirement |
| V6 Cryptography | yes (scoped down) | TLS on the JDBC connection is deliberately dropped (Pitfall 2) because the hop no longer crosses the public internet — an explicit, documented trade-off matching local dev's own existing posture, not an oversight; passwords for the two new roles must still be generated with real entropy (not left as example/placeholder values) and stored only in the never-committed `.env.*` files, consistent with this repo's existing secrets convention |
| V14 Configuration | yes | Conservative `postgresql.conf` defaults (D-11) instead of dedicated-server 25%-of-RAM guidance, matching the shared-host reality; `docker-entrypoint-initdb.d` scripts must not hardcode credentials — read them from env vars sourced from the never-committed `.env.*` files (Pattern 2) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|------------------------|
| A compromised or misconfigured nonprod app instance reading/writing production data | Elevation of Privilege | Per-database `REVOKE CONNECT ... FROM PUBLIC` (Pitfall 3) — the nonprod role has no `CONNECT` privilege on the production database at the Postgres access-control layer, independent of any application-level bug |
| A future CI job or contributor accidentally publishing a Postgres host port for convenience | Tampering / Information Disclosure | D-03's explicit "no host port" rule; any exception (e.g. a loopback-only bind for tunnel convenience) should require an explicit `checkpoint:human-verify`, not be added silently (Pitfall 4) |
| Init-script credentials leaking into image layers or shell history | Information Disclosure | Pass role passwords via Compose `environment:` sourced from `.env.*` files (never committed), consistent with this repo's existing `POSTGRES_PASSWORD`/`DB_PASS` convention — never bake a password literal into the init `.sh` script file itself |

## Sources

### Primary (MEDIUM confidence — Context7-sourced official docs)
- `/docker-library/postgres` (Context7) — `docker-entrypoint-initdb.d` initialization flow, healthcheck pattern, `POSTGRES_PASSWORD_FILE` secrets pattern
- `/websites/postgresql_17` (Context7, docs.postgresql.org) — `shared_buffers`/`work_mem` resource-consumption defaults and dedicated-server-scoped 25%-of-RAM guidance; `kernel-resources.html`'s explicit "lower shared_buffers/work_mem/max_connections on constrained hosts" recommendation; `ddl-priv.html`'s default `PUBLIC` `CONNECT`/`TEMPORARY` grant on every database

### Secondary (LOW confidence — WebSearch, not independently cross-checked against an authoritative source this session)
- HikariCP generic tuning-advice blog aggregation (multiple blog/doc-site results) — see Assumption A1, deliberately not adopted into the recommended skeleton without further verification
- `appleboy/ssh-action` proxy/tunnel capability summary (its own README plus secondary blog coverage) — confirms `proxy_host`/`proxy_key` jump-host support exists but no built-in `-L` local-forward primitive; the dynamic-IP/no-published-port reasoning in Pitfall 4 is this session's own derivation (tagged `[ASSUMED]`), not sourced from an authoritative reference
- PgBouncer `prepareThreshold=0` history (PgBouncer's own FAQ, a Crunchy Data blog post, a Hibernate forum thread) — consistent across all sources on the core mechanism (transaction-mode pooling breaks server-side prepared-statement locality; fixed only in PgBouncer ≥1.21 with `max_prepared_statements`)

### Tertiary (project-internal, VERIFIED this session by direct file read)
- `docker-compose.prod.yml`, `docker-compose.nonprod.yml`, `docker-compose.yml`, `src/main/resources/application.properties`, `.github/workflows/deploy.yml`, `docs/INFRA_RUNBOOK.md` (Redpanda restart-ladder measurement sections), `.planning/debug/admin-reset-500-nonprod.md` — all read directly this session, quoted verbatim where used as the basis for a concrete value or pattern

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — both images already in active, proven use elsewhere in this exact repo, no new dependency introduced
- Architecture (cross-project network sharing, init-script provisioning): MEDIUM — the `kanban-edge` precedent is directly verified in-repo, but the specific `kanban-db` application of it, and the SSH-tunnel-alternative reasoning (Pitfall 4), are this session's own derivation, not independently verified against the live VM
- Pitfalls: MEDIUM-HIGH — Pitfalls 1-3 are grounded in official docs read this session (CITED/VERIFIED); Pitfall 4 is reasoned from generalized SSH/Docker networking behavior and tagged `[ASSUMED]`; Pitfall 5 is a direct logical consequence of a locked decision (D-01), not externally sourced

**Research date:** 2026-08-26
**Valid until:** 30 days (stable infra/Docker-ecosystem domain; the HikariCP/PgJDBC LOW-confidence findings specifically should be re-verified against source, not this research, before being trusted in a plan — matching this repo's own stated convention)
