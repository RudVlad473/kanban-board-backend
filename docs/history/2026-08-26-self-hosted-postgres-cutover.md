# Self-hosted Postgres cutover — Plan 11-02 (2026-08-26)

The managed provider (Neon) hit a hard, unrecoverable quota block mid-month — its Free plan's
100 CU-hour/month compute allowance is scoped per **project**, shared across both the production
and nonprod branches, and this repository's own pool settings (`minimum-idle=1` +
`keepalive-time=120000`, set deliberately to defeat scale-to-zero) kept both computes billing
continuously against that one shared allowance until it ran out. `quota_reset_at` was
`2026-09-01T00:00:00Z`; nothing in this repository could restore access before then without a
paid-plan upgrade. Plan 11-01 proved the self-hosted provisioning mechanism against a local Docker
tracer without touching this VM; this plan cuts both live environments onto it.

## Topology

| Axis | Value | Decision realized |
|---|---|---|
| Image | `postgres:16` | D-02 |
| Containers | one shared `postgres` container, production's Compose project | D-01, D-06 |
| Databases | `kanban_prod`, `kanban_nonprod` | D-01 |
| Login roles | `kanban_prod_app`, `kanban_nonprod_app` (one per database, least-privilege) | D-01 |
| Engine settings | `shared_buffers=128MB`, `work_mem=4MB`, `max_connections=25` | D-11 |
| Host port | none published — reachable only over Docker networks | D-03 |
| Volume | `kanban-board-backend_postgres-data` | — |
| Networks | `default` (production project) + external `kanban-db` (joined by `postgres` and `app-nonprod`, mirroring the existing `kanban-edge` pattern) | D-01, D-06 |

No credential or role password is recorded here — variable names only (`POSTGRES_SUPERUSER_PASS`,
`DB_PASS`, `NONPROD_DB_PASS`), matching this file's existing secret-inventory convention.

## Sequence, in order

The eleven steps from `11-02-PLAN.md` Task 2, executed via SSH as `deploy` / `deploy-nonprod` on
`netcup-prod`:

1. `docker network create kanban-db` — created (did not previously exist).
2. Three credentials generated ON the VM via `openssl rand -hex 32` (engine superuser, production
   role, nonprod role) — staged in root-only temp files, never displayed, never left the VM.
3. `/opt/deploy/kanban-board-backend/.env.prod` rewritten: `POSTGRES_SUPERUSER`/`POSTGRES_SUPERUSER_PASS`
   added, `DB_HOST=postgres`/`DB_PORT=5432`/`DB_NAME=kanban_prod`/`DB_USER=kanban_prod_app`/`DB_PASS`
   rewritten from the Neon-era values, `NONPROD_DB_NAME`/`NONPROD_DB_USER`/`NONPROD_DB_PASS`
   crossover block added, the retired `DB_JDBC_PARAMS` line deleted. `IMAGE_TAG`/`APP_DOMAIN`/
   `APP_DOMAIN_NONPROD` preserved unchanged. The Neon-era file was backed up (root-only,
   `/root/.env.prod.neon-era.bak`) before being overwritten.
4. `/opt/deploy/kanban-board-nonprod/.env.nonprod` rewritten symmetrically: `DB_HOST=postgres`/
   `DB_PORT=5432`/`DB_NAME=kanban_nonprod`/`DB_USER=kanban_nonprod_app`/`DB_PASS` (byte-identical to
   `.env.prod`'s `NONPROD_DB_PASS`, verified by direct comparison on the VM), retired
   `DB_JDBC_PARAMS` line deleted, all other keys preserved. Backed up the same way.
5. The plan-11-01 `docker-compose.prod.yml`, `docker-compose.nonprod.yml`, and
   `docker/postgres-init/01-create-databases-and-roles.sh` staged into place on the VM (script
   confirmed `755`/executable); both manifests confirmed to render cleanly (`docker compose ...
   config --quiet`) before any container was touched.
6. `docker compose --env-file ./.env.prod -f docker-compose.prod.yml up -d postgres` — reached
   `(healthy)` on the second health-check poll.
7. D-01 isolation proven live (verbatim output below).
8. Nonprod cut over: `docker compose --env-file ./.env.nonprod -f docker-compose.nonprod.yml
   --profile nonprod up -d` — `app-nonprod` reached `(healthy)`; `kanban_nonprod` showed 8
   successful Flyway rows.
9. Production cut over: `docker compose --env-file ./.env.prod -f docker-compose.prod.yml up -d`
   — `app` reached `(healthy)`; `kanban_prod` showed 8 successful Flyway rows. Bringing the full
   stack up recreated the already-running `postgres` container (Compose detected a config diff
   from the `up -d postgres`-only invocation in step 6); its named volume was unaffected —
   re-verified databases/roles/Flyway counts unchanged immediately after, before proceeding.
10. Both public HTTPS health endpoints proven `200` from off-VM; a real board created through the
    public production API (via signup -> signin -> `POST /api/boards`) confirmed readable, the
    `app` container restarted, and the board confirmed still readable afterward — proving a
    working datasource, not merely a working health endpoint. The verification board was deleted
    immediately after (`DELETE /api/boards/{id}`) to keep the fresh production database clean; the
    throwaway verification user account (`cutover-verify-<timestamp>@example.com`) was left in
    place — harmless, and not worth the added risk of building an account-deletion path under
    live-cutover time pressure.
11. GitHub Environment secrets repointed: `production`'s `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` to
    `postgres`/`kanban_prod`/`kanban_prod_app`/(generated), `staging`'s to
    `postgres`/`kanban_nonprod`/`kanban_nonprod_app`/(generated) — passwords piped directly from
    the VM into `gh secret set` via stdin, never displayed in any terminal, transcript, or file.
    Confirmed via `gh secret list` timestamps (names/timestamps only, values never read back).

## Deviations from the plan text, and why

- **Both `.env` files' preserved `IMAGE_TAG` values (`08feddb` prod, `777cb27` nonprod) turned out
  to be stale — neither image tag still exists on Docker Hub** (this repository's CI publishes one
  tag per commit, never `latest`, and old tags get pruned; see the "Decommission Record — Plan
  05-06 Task 3" section above for the pruning mechanism). Step 8's first attempt failed outright:
  `docker.io/rudenkovladimir/kanban-board-backend-nonprod:777cb27: not found`. Recovered the same
  way the "Nonprod bring-up — Plan 08-01" section above records for the identical class of failure:
  read the actually-running containers' real image tag (`docker ps` showed both `app` and
  `app-nonprod` already running — unhealthy, pre-dating this cutover — under tag `f835369`,
  matching `origin/HEAD`, and confirmed cached locally on the VM) and used that real tag for both
  `.env.prod` and `.env.nonprod` instead of the stale preserved values. Rule 1 (bug found during
  execution), auto-fixed, verified both manifests then pulled/started cleanly.

## Isolation proof (D-01)

Both databases exist:
```
kanban_nonprod
kanban_prod
```

Both login roles exist:
```
kanban_nonprod_app
kanban_prod_app
```

Nonprod role -> production database — REFUSED:
```
psql: error: connection to server at "127.0.0.1", port 5432 failed: FATAL:  permission denied for database "kanban_prod"
DETAIL:  User does not have CONNECT privilege.
```

Production role -> nonprod database — REFUSED, symmetrically:
```
psql: error: connection to server at "127.0.0.1", port 5432 failed: FATAL:  permission denied for database "kanban_nonprod"
DETAIL:  User does not have CONNECT privilege.
```

Each role reaches its own database (isolation did not overshoot into a lockout) — both
`SELECT 1` -> `1`, confirmed for `kanban_prod_app`/`kanban_prod` and `kanban_nonprod_app`/
`kanban_nonprod`.

`CREATE ROLE` plus `CREATE DATABASE ... OWNER` do **not** produce this isolation on their own —
PostgreSQL grants `CONNECT` on every database to `PUBLIC` by default. The mechanism realizing D-01
is the explicit `REVOKE CONNECT ... FROM PUBLIC` (paired with `GRANT CONNECT ... TO` the owning
role) in `docker/postgres-init/01-create-databases-and-roles.sh` — a future reader "tidying up"
those two `REVOKE` lines would silently remove the entire isolation guarantee this section proves.

`docker port` on the `postgres` container returned nothing, both immediately after step 6 and
again after step 9's recreation — D-03 holds on the real deployment, not only in plan 11-01's
local tracer.

## Schema proof (D-04)

`SELECT count(*) FROM flyway_schema_history WHERE success` returned **8** for `kanban_nonprod` and
**8** for `kanban_prod` — this repository's real V1..V8 migration set, built from scratch by
Flyway against an empty engine. No row, table, or schema object was carried over from the managed
provider; every board, task, subtask, user, and activity-log entry the managed provider held is
abandoned, per the operator's explicit D-04 go-ahead recorded in `11-02-SUMMARY.md`.

## Sessions are not carried over

`spring_session` and `spring_session_attributes` started empty on the self-hosted instance. Any
browser holding a session cookie from before this cutover was silently logged out — the cookie
names a session id the new database has never seen. This is expected behavior under D-05 (the
cutover was taken without a downtime window because production was already unreachable), not a
defect to investigate if reported.

## Known red window in CI

From the moment the GitHub Environment secrets were repointed (2026-08-26, step 11 above), the
existing `flyway-verify` / `flyway-verify-nonprod` jobs in `.github/workflows/deploy.yml` fail on
every push — a GitHub-hosted runner has no network route to a container that publishes no host
port (D-03). This is a known, bounded window, not a regression to debug. Plan 11-05 rewrites both
jobs to reach the self-hosted database through the VM instead, closing the window. The alternative
— leaving the secrets pointing at the quota-blocked managed provider — would fail identically
while also being wrong, so this window was accepted rather than avoided.

**Closed 2026-08-26** — see "CI Flyway verification over SSH — Plan 11-05" below for the
mechanism, the decision record, and the live green run that closed this window on the same day it
was opened.

## Operator note — routine restarts

Two consequences of D-01's single-shared-instance topology a future operator cannot infer from the
Compose files alone:

- **Restarting production's Compose project now restarts the database, and therefore interrupts
  nonprod too.** `postgres` lives in production's Compose project; any `docker compose ... down`/
  `up`/`restart` that touches it (even indirectly, as step 9 above did to recreate `app`) briefly
  takes nonprod down with it. Accepted under D-01's memory-budget trade-off (see `11-01-PLAN.md`'s
  `design_alternatives`).
- **`app-nonprod` cannot express a health dependency on `postgres`** — the shared instance lives in
  the *other* Compose project, and Compose `depends_on` cannot cross project boundaries. On a VM
  reboot, `app-nonprod` may start before the database accepts connections and restart-loop briefly;
  `restart: unless-stopped` plus the app's own healthcheck retries converge it without operator
  intervention.

## Cutover date

2026-08-26.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
