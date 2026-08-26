---
phase: 11-migrate-database-from-neon-to-self-hosted-postgres
reviewed: 2026-08-26T00:00:00Z
depth: standard
files_reviewed: 8
files_reviewed_list:
  - .env.nonprod.example
  - .env.prod.example
  - .github/workflows/deploy.yml
  - docker-compose.nonprod.yml
  - docker-compose.prod.yml
  - docker/postgres-init/01-create-databases-and-roles.sh
  - docs/INFRA_RUNBOOK.md
  - src/main/resources/application.properties
findings:
  critical: 2
  warning: 4
  info: 2
  total: 8
status: issues_found
---

# Phase 11: Code Review Report

**Reviewed:** 2026-08-26
**Depth:** standard
**Files Reviewed:** 8
**Status:** issues_found

## Summary

Reviewed the Neon-to-self-hosted-Postgres migration: the shared `postgres` container and its
init script, the HikariCP rewrite, the CI Flyway-over-SSH rewrite, and the two `.env.*.example`
templates. The overall shape is sound and unusually well-documented (every non-obvious decision
carries a dated rationale comment), but two issues meet the bar for BLOCKER: an unescaped SQL
string interpolation in the first-boot provisioning script, and a Postgres memory configuration
that is internally inconsistent (`shared_buffers` exceeds the container's own cgroup cap) and was
only validated under a light, sequential, single-connection workload. Four further issues degrade
robustness or maintainability without being immediately production-breaking.

## Critical Issues

### CR-01: Unescaped secret interpolation into SQL creates a SQL-injection-shaped hole in the first-boot provisioning script

**File:** `docker/postgres-init/01-create-databases-and-roles.sh:41-51`
**Issue:** `PROD_DB_PASS`, `NONPROD_DB_PASS` (and the corresponding user/db names) are interpolated
directly into a `psql` heredoc with no escaping:

```bash
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE "${PROD_DB_USER}" WITH LOGIN PASSWORD '${PROD_DB_PASS}';
    ...
EOSQL
```

The script's own comment acknowledges the hazard ("a password containing an apostrophe would
break first-boot provisioning") and the mitigation is purely operational convention — every
password must be generated with `openssl rand -hex 32` so it can never contain a `'`. There is no
enforcement of that convention anywhere in the script itself: nothing validates the incoming
values before they are spliced into a SQL literal run as the database superuser at first boot. Any
future change to how these values are generated (a password manager, a rotated-secret pipeline, an
operator typing a value by hand) that ever produces a value containing `'` breaks provisioning in
a way that's hard to read from the container log (per the script's own comment) — or, in the worst
case, allows arbitrary SQL to run as the Postgres superuser if the value is ever attacker-influenced
at any point in the credential-generation pipeline. This is the textbook SQL-injection anti-pattern
(interpolating untrusted/externally-sourced strings into a SQL statement) even though today's actual
inputs happen to be safe by convention, not by construction.
**Fix:** Escape single quotes before interpolation (double them, which is the SQL-standard escape
inside a single-quoted literal), or use `psql`'s `\set` + `:'var'` quoting which does this for you:

```bash
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v prod_user="$PROD_DB_USER" -v prod_pass="$PROD_DB_PASS" \
  -v nonprod_user="$NONPROD_DB_USER" -v nonprod_pass="$NONPROD_DB_PASS" <<-'EOSQL'
    CREATE ROLE :"prod_user" WITH LOGIN PASSWORD :'prod_pass';
    ...
EOSQL
```
`psql`'s `:'var'` syntax quotes the value as a SQL literal (escaping embedded quotes) and `:"var"`
quotes it as an identifier — this closes the hole regardless of what future credential-generation
tooling ever produces.

### CR-02: `shared_buffers=128MB` exceeds the `postgres` container's own `mem_limit: 64m` — real OOM risk under any workload heavier than the light burst test used

**File:** `docker-compose.prod.yml:130,155-162`
**Issue:** The `postgres` service is capped at `mem_limit: 64m` but is started with
`shared_buffers=128MB` — double the container's own cgroup ceiling. The measurement session that
adopted `64m` (documented in `docs/INFRA_RUNBOOK.md`, "Self-hosted Postgres resource measurement —
Plan 11-03") explicitly notes this and waves it off: "peak RSS never approached shared_buffers' own
128MB allocation at any rung." That is true only because the test workload was a **light, mostly-
sequential, 54-57-request burst against a personal-scale dataset** (~55 rows). Postgres allocates
`shared_buffers` as shared memory at postmaster startup, but Linux cgroup accounting only counts
pages once they are actually touched — so a workload that touches more of the buffer cache (more
concurrent connections each touching different pages, larger tables, index scans across a wider
working set, or `max_connections=25` actually being exercised concurrently with `work_mem=4MB`
sorts/hashes each) can legitimately push resident memory well past 64MiB, at which point the kernel
OOM-killer does exactly what it did at the tested `32m` rung — forcibly kills the postmaster,
forcing full crash recovery and producing live HTTP 500s (the failure signature is already captured
in the runbook for the adjacent `32m` rung, and nothing about the `64m` rung's *test methodology*
rules it out at a higher, more realistic load). A single-threaded sequential burst does not exercise
concurrent-backend memory pressure at all, so "never approached 128MB" is evidence about this one
synthetic test, not a bound on the config's actual safety envelope.
**Fix:** Either lower `shared_buffers` to a value that is a **fraction** of `mem_limit` (e.g. 24-32MB,
leaving headroom for per-backend `work_mem`/connection overhead under the cgroup cap), or raise
`mem_limit` to comfortably exceed `shared_buffers` plus realistic concurrent per-backend overhead
(e.g. `shared_buffers=128MB` + `max_connections=25 * work_mem=4MB` ≈ 228MB minimum, plus baseline
per-backend overhead and headroom — likely `mem_limit: 256m` or higher). Re-validate under a
**concurrent** multi-connection workload (all `app`/`app-nonprod` HikariCP connections issuing
queries simultaneously, not sequentially) before trusting a new floor.

## Warnings

### WR-01: No concurrency guard on `flyway-verify`/`flyway-verify-nonprod` — two rapid pushes can race on the shared VM staging directory

**File:** `.github/workflows/deploy.yml:168-233,239-285`
**Issue:** `deploy-to-netcup` and `deploy-to-nonprod` both declare a `concurrency:` group precisely
because two overlapping SCP+SSH sequences against the same VM directory would corrupt each other.
`flyway-verify`/`flyway-verify-nonprod` perform the identical pattern — `rm: true` SCP of
`src/main/resources/db/migration` into a shared per-environment path
(`/opt/deploy/kanban-board-backend/ci-flyway-verify/production/`,
`/opt/deploy/kanban-board-nonprod/ci-flyway-verify/staging/`), followed by an SSH step reading that
same path — with **no `concurrency:` block at all**. Two pushes to `main` in quick succession (e.g.
a second commit landing while the first run's `flyway-verify` is still staging files) can interleave
one run's `rm`+copy with the other's already-started `flyway migrate` read of that directory,
producing an unpredictable migration set (a false failure at best, a migration applied against a
inconsistent SQL file set at worst).
**Fix:** Add a `concurrency:` block to both jobs, scoped per environment (e.g.
`group: flyway-verify-production-vm` / `group: flyway-verify-staging-vm`), matching the pattern
already used by `deploy-to-netcup-vm`/`deploy-to-nonprod-vm`.

### WR-02: `NONPROD_DB_PASS` (`.env.prod`) and `DB_PASS` (`.env.nonprod`) must be byte-identical but nothing enforces or checks that

**File:** `.env.prod.example:29-38`, `.env.nonprod.example:19-27`, `docker/postgres-init/01-create-databases-and-roles.sh`
**Issue:** The nonprod application role's password is set once, at first container boot, from
`.env.prod`'s `NONPROD_DB_PASS` (consumed by the init script running inside production's own Compose
project). `app-nonprod` then authenticates against that same role using `.env.nonprod`'s own,
independently-maintained `DB_PASS`. Both files' comments state the values "MUST hold the identical
value" — but this is enforced by nothing beyond a comment. A future operator rotating one file
without the other (or a fresh `.env.nonprod` populated from a password manager without
cross-checking `.env.prod`) silently breaks `app-nonprod`'s ability to connect, and — compounding
this — the init script only runs on an empty data directory (first boot), so even *noticing* the
drift and "fixing" `.env.prod`'s value does not actually change the live role's password; only a
manual `ALTER ROLE ... PASSWORD` against the running database does.
**Fix:** At minimum, document the `ALTER ROLE` recovery/rotation path explicitly in
`docs/INFRA_RUNBOOK.md` (today's docs cover disaster recovery via full volume loss, not routine
password rotation or drift). Better: add a startup/deploy-time check (a one-line `psql -c "SELECT 1"`
smoke test against the target role from each app's own deploy job) that fails loudly on an auth
mismatch instead of surfacing only as a production incident.

### WR-03: No backup of `kanban_prod`/`kanban_nonprod` exists — total, unrecoverable data-loss exposure on any volume loss

**File:** `docs/INFRA_RUNBOOK.md:206-264` (Backups and restore section), `docker-compose.prod.yml:169-170`
**Issue:** The runbook is explicit and honest about this: "There is no backup of the production
database... A container loss or a volume loss on the Netcup VM means total, unrecoverable data loss."
This is a real, currently-live regression introduced by this migration — Neon supplied point-in-time
recovery as a platform feature at zero engineering cost; the self-hosted replacement has nothing.
The documented recovery procedure (`pg_dump`/`pg_restore`) is written but "has never been executed,
as of 2026-08-26. No test restore has been performed." Flagging this per the review's own Security
criteria (data loss risk) despite it being a knowingly-accepted, explicitly-approved trade-off
(D-12) — the risk is real regardless of whether it was consciously accepted, and a single `docker
volume rm`, disk failure, or `docker compose down -v` on the wrong project now permanently destroys
all production data with no recovery path.
**Fix:** At minimum, stand up the scheduled `pg_dump` + off-host copy the runbook itself already
scopes out ("What closing this gap properly would require") before this is treated as
production-stable — a cron/systemd-timer dump plus an off-VM copy (even a simple `scp` to a second
host or object storage) closes the single-point-of-failure risk the current state has with `.dump`
files that don't yet exist.

### WR-04: `postgres:16` (~400MB) pulled fresh on every CI run just to run `pg_isready`

**File:** `.github/workflows/deploy.yml:222-223,277-278`
**Issue:** Both Flyway-verification jobs run
`docker run --rm --network kanban-db postgres:16 pg_isready -h postgres -p 5432 -t 5` purely as a
reachability probe before the real `flyway/flyway:11.7.2 migrate` invocation. This pulls the full
`postgres:16` server image on the VM on every push (in addition to it already being present as the
running `postgres` service's own image, so in practice this is a cache hit locally — but it is still
an unnecessary dependency: the probe only needs a `pg_isready`-capable client, not the full server
image). Not flagged as the primary bug (out of scope: pure performance), but the design smell is
worth calling out for maintainability: a dedicated lightweight client image (or reusing the
`flyway/flyway` image's own JDBC connectivity as the readiness gate, since Flyway itself will fail
clearly if the target is unreachable) would remove the redundant image dependency entirely.
**Fix:** Consider dropping the separate `pg_isready` probe container and letting `flyway migrate`'s
own connection failure serve as the reachability signal (it already produces a clear,
non-ambiguous error), or replace `postgres:16` with a minimal `postgres:16-alpine`/`busybox`-class
image that only ships the client tools.

## Info

### IN-01: `flyway-verify` and `flyway-verify-nonprod` are near-byte-identical (~65 lines) duplicated inline shell

**File:** `.github/workflows/deploy.yml:168-233,239-285`
**Issue:** The two jobs differ only in their `environment:`, SCP target path, and log-message
wording — the SCP step and the entire SSH script body are otherwise copy-pasted. This is consistent
with the rest of the file's existing pattern (e.g. `cleanup-old-images`/`cleanup-old-images-nonprod`
are duplicated the same way), so it's not a new regression, but it compounds the maintenance cost
of this file every time one twin is touched without the other (already a recurring source of the
"deviation found live" incidents this same runbook documents for the cleanup jobs).
**Fix:** Consider extracting the shared script body into a reusable composite action or a script
file parameterized by environment/database name, reducing the chance of the two twins drifting out
of sync on a future edit.

### IN-02: No documented password-rotation procedure, only disaster-recovery (full volume loss)

**File:** `docs/INFRA_RUNBOOK.md` (Database section), `docker/postgres-init/01-create-databases-and-roles.sh`
**Issue:** The init script runs exactly once, on an empty data directory. `docs/INFRA_RUNBOOK.md`
covers what to do on total volume loss (restore from a — currently nonexistent — dump) but says
nothing about the much more routine case of rotating `POSTGRES_SUPERUSER_PASS`/`DB_PASS`/
`NONPROD_DB_PASS` on a healthy, already-provisioned instance. Editing the `.env.*` files alone does
not change the live role's password (see WR-02); a future operator following only the `.env.*.example`
files' own guidance could reasonably believe editing and redeploying is sufficient.
**Fix:** Add a short "rotating a database credential" subsection to `docs/INFRA_RUNBOOK.md` naming
the required `ALTER ROLE ... WITH PASSWORD '...'` step against the live instance, executed before
(or immediately after) updating the corresponding `.env.*` file.

---

_Reviewed: 2026-08-26_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
