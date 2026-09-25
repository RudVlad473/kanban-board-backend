# Nonprod bring-up — Plan 08-01 (2026-08-18)

A second, production-isolated deployment (`app-nonprod` + `redpanda-nonprod`) is live on this same
Netcup VM, reachable over real Let's Encrypt HTTPS at its own hostname, backed by its own empty
Neon branch and its own Redpanda broker/schema registry, gated behind a `nonprod` Compose profile
in its own Compose project. This section records the identity choices, the exact live sequence
executed, the isolation-audit output, and the CORS proof — every claim below is a reproducible
command output captured during this bring-up, not an inference from the checked-in config.

## Nonprod identities

| Axis | Value |
|---|---|
| Compose file | `docker-compose.nonprod.yml` (repo root, standalone file — see Deviations below) |
| Compose project name | `kanban-board-nonprod` |
| VM directory | `/opt/deploy/kanban-board-nonprod/` (owned by `deploy`, sibling to `/opt/deploy/kanban-board-backend/`) |
| Compose profile | `nonprod` (both services gated; neither exists without `--profile nonprod`) |
| Container names | `kanban-nonprod-app`, `kanban-nonprod-redpanda` |
| Network names | project default `kanban-board-nonprod_default`; shared external `kanban-edge` (joined only by `kanban-nonprod-app` and production's `caddy`) |
| Volume name | `kanban-board-nonprod_redpanda-nonprod-data` |
| Public hostname | `kanban-board-rud-vlad-473-nonprod.duckdns.org` -> `159.195.114.230` (same VM as production) |
| Neon branch | `nonprod`, project `kanban-board-db` (`floral-union-23715140`), created via the Neon Console with `init_source: parent-schema` (schema-only, no production data), direct host `ep-wild-mode-b2atsqpx.c-6.eu-central-1.aws.neon.tech` |
| Image tag | `6755c84` — Docker Hub does **not** publish a `latest` tag for this repository; see Deviations below |

## Sequence, in order

1. **Shared network:** `docker network create kanban-edge` on the VM (did not previously exist).
2. **Caddy joined to it:** edited `docker-compose.prod.yml` (additive-only: new top-level
   `networks:` block, `caddy.networks: [default, kanban-edge]`, `caddy.environment.APP_DOMAIN_NONPROD`)
   and `Caddyfile` (second site block, `reverse_proxy app-nonprod:8080`), `scp`'d both to
   `/opt/deploy/kanban-board-backend/`, appended `APP_DOMAIN_NONPROD=kanban-board-rud-vlad-473-nonprod.duckdns.org`
   to `.env.prod` on the VM, then `docker compose -f docker-compose.prod.yml --env-file ./.env.prod up -d caddy`
   (service named explicitly, not a bare `up -d`). Production's health endpoint and `app`/`redpanda`
   container ids were confirmed unchanged immediately after — the only production container this
   plan recreates is `caddy` itself, once, for the network join.
3. **Neon branch:** the `nonprod` branch already existed (created via the Console before this
   session), schema-only. **Emptied before Flyway ever ran** — a schema-only branch arrives
   carrying the parent's full DDL plus an already-populated `flyway_schema_history`, which makes
   Flyway attempt `V1__init.sql` against tables that already exist and fail. Connected with `psql`
   to the direct (non-pooler) host and ran `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`;
   `\dt` confirmed zero relations before continuing.
4. **`/opt/deploy/kanban-board-nonprod/` created**, owned by `deploy` — required a one-time root
   action (`mkdir` + `chown`) since `/opt/deploy` itself is root-owned and `deploy` cannot `sudo`,
   the same precedent already recorded in "Deploy user setup — Plan 05-05 Task 1" for the sibling
   production directory. `docker-compose.nonprod.yml` copied in; `.env.nonprod` created directly on
   the VM (never pasted into a local file, never written under `.planning/`), mode `600`, owned by
   `deploy`, with `APP_RESET_TOKEN` generated on the VM via `openssl rand -hex 32`.
5. **`redpanda-nonprod` brought up alone:**
   `docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod up -d redpanda-nonprod`.
   Before registering anything, `rpk registry subject list` inside `kanban-nonprod-redpanda`
   returned zero subjects; the same command inside production's `redpanda` returned its existing 14
   — the nonprod broker genuinely started from an empty registry.
6. **14 Avro schemas registered** against the nonprod registry, mirroring the production runbook's
   `PropertiesLauncher` technique:
   ```
   docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod run --rm \
     --entrypoint java app-nonprod -Dloader.main=com.vrudenko.kanban_board.config.AvroSchemaRegistrar \
     -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher http://redpanda-nonprod:8081
   ```
   Log: `Registered 14 Avro schemas against http://redpanda-nonprod:8081`. Verified independently via
   `rpk registry subject list` — 14 subjects, matching production's set of Avro class names exactly
   (an independent registration, not a copy).

   **Superseded by Plan 09-03 (2026-08-19):** this step now runs automatically on every push to
   `master`, as a step inside the `deploy-to-nonprod` job in `.github/workflows/deploy.yml` —
   positioned between this step and the next (broker up, then register, then `app-nonprod` up),
   the identical invocation shown above, run by CI instead of by hand. See "## Automated Avro
   schema registration — Plan 09-03" below for the full detail. The command above remains
   documented (not deleted) because it is still the correct procedure for a manual bring-up or a
   recovery from a wiped registry volume.
7. **`app-nonprod` brought up:**
   `docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod up -d app-nonprod`.
   Reached `(healthy)`. Container log confirmed Flyway migrated the empty branch fresh:
   `Migrating schema "public" to version "1 - init"` through `"7 - add board optimistic locking
   version column"`, `Successfully applied 7 migrations ... now at version v7`.
   `SELECT count(*) FROM flyway_schema_history WHERE success = true;` against the branch returned
   `7`.
8. **Verified end-to-end from off-VM:**
   `curl https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health` -> `200`
   `{"status":"UP"}`; plain `http://` -> `308` redirect to the `https://` URL; production's own
   health endpoint still `200` throughout.

## Isolation-audit output (Task 2)

**Identity axes** — `docker ps -a --format '{{.Names}}\t{{.Label "com.docker.compose.project"}}'`:
```
kanban-nonprod-app               kanban-board-nonprod
kanban-nonprod-redpanda          kanban-board-nonprod
kanban-board-backend-caddy-1     kanban-board-backend
kanban-board-backend-app-1       kanban-board-backend
kanban-board-backend-redpanda-1  kanban-board-backend
```
Exactly two project labels, no container name shared between them.

`docker volume ls --format '{{.Name}}'`:
```
kanban-board-backend_caddy-config
kanban-board-backend_caddy-data
kanban-board-backend_redpanda-data
kanban-board-nonprod_redpanda-nonprod-data
root_caddy-config      <- orphaned leftover from the 05-05 cutover incident, unrelated to this audit
root_caddy-data
root_redpanda-data
```
`kanban-board-backend_*` and `kanban-board-nonprod_*` sets have an empty intersection.

`docker network inspect kanban-edge --format '{{range .Containers}}{{.Name}} {{end}}'`:
```
kanban-nonprod-app kanban-board-backend-caddy-1
```
Exactly the two containers meant to share it — production's `app` and `redpanda` are absent.

**Profile-gate (empty case):** `docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod config --services`
(no `--profile` flag) returned no output — neither service is listed. A bare
`docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod up -d --dry-run` (still no
`--profile`) refused outright: `no service selected`. Stronger than "creates zero containers" — the
command will not even attempt to select a service without the profile flag.

**Cross-project non-interference:** production's `app`/`redpanda`/`caddy` container ids were
identical before and after a full nonprod cycle (`--profile nonprod stop` then `up -d`); production's
health endpoint answered `200` throughout the cycle.

**Database isolation:** nonprod branch `users`/`boards` counts went `0/0` -> `1/1` after signing up
`nonprod-isolation-260818@example.com` and creating one board through the nonprod public API.
Production's branch stayed at `3/2` across that same write, queried via a one-off `postgres:17-alpine`
container on the VM reading each stack's own `--env-file` (`.env.nonprod` / `.env.prod`) so the
Neon passwords were never typed or displayed outside the VM's own environment substitution.
`.env.nonprod` (`/opt/deploy/kanban-board-nonprod/`) and `.env.prod` (`/opt/deploy/kanban-board-backend/`)
are separate files in separate directories; `DB_HOST` values name different Neon compute endpoints
(`ep-wild-mode-b2atsqpx...` vs. `ep-delicate-bird-b2lni8pr...`).

**Broker/registry isolation:** `kanban.activity` high-watermark on nonprod advanced `0 -> 1 -> 2`
across two bracketing nonprod writes (the board create, then a column create), while production's
own `kanban.activity` watermark stayed at `74` across both — confirmed by reading production's
watermark before and after the second nonprod write (the first write's "before" reading was not
captured in time; the second write brackets cleanly and demonstrates the same non-interference).
`rpk registry subject list` returned 14 subjects on both brokers; a spot-check of
`AvroTaskMovedEvent`'s registered version count (`GET /subjects/.../versions`) returned `[1]` on
both — identical shape, independent histories (`RecordNameStrategy` keys compatibility by Avro
record full name, so registering the same 14 class names on a second broker does not touch
production's own compatibility history).

**CORS proof:** a real credentialed preflight (`OPTIONS /api/boards`, `Origin:
http://localhost:5173`, `Access-Control-Request-Method: GET`) against nonprod returned `200` with
`Access-Control-Allow-Origin: http://localhost:5173` and `Access-Control-Allow-Credentials: true`.
The identical preflight with `Origin: https://evil.example` returned `403` with **no**
`Access-Control-Allow-Origin` header at all — the allow-list is exact-string matching, proven from
real response headers, not inferred from the config file.

**TLS scope proof:** `openssl s_client ... -servername kanban-board-rud-vlad-473-nonprod.duckdns.org`
piped to `openssl x509 -noout -issuer -subject -ext subjectAltName` returned issuer `O=Let's
Encrypt, CN=YE2`, subject `CN=kanban-board-rud-vlad-473-nonprod.duckdns.org`, SAN
`DNS:kanban-board-rud-vlad-473-nonprod.duckdns.org` only (no wildcard). The same check against
production's hostname returned its own distinct certificate (`CN=kanban-board-rud-vlad-473.duckdns.org`)
— both site blocks share the one `caddy-data` volume but obtained separate, independently-scoped
certificates as designed.

## Deviations from the plan text, and why

- **Separate `docker-compose.nonprod.yml` instead of RESEARCH.md's in-place extension of
  `docker-compose.prod.yml`.** Two reasons: (1) NONPROD-01 requires a distinct Compose project
  name, and Compose supports exactly one project name per file — a shared file could not provide
  that identity axis. (2) A shared-project invocation (`docker compose -f docker-compose.prod.yml
  --env-file .env.nonprod --profile nonprod up -d`, the pattern RESEARCH.md's primary
  recommendation implies) targets every enabled service in the project, so Compose would
  re-resolve production's own `app` service against nonprod's `DB_*` values and recreate the live
  production container pointed at the nonprod database unless every invocation named services
  explicitly. A separate file makes that failure mode structurally impossible instead of relying on
  operator discipline. Recorded in `08-01-PLAN.md`'s own `design_alternatives` table before
  execution, not decided ad hoc during this bring-up.
- **`IMAGE_TAG=latest` does not exist on Docker Hub.** The first schema-registration attempt
  (`docker compose ... run --rm --entrypoint java app-nonprod ...`) failed outright —
  `docker.io/rudenkovladimir/kanban-board-backend:latest: not found` — because this repository's
  CI publishes one tag per commit (the short SHA), never a floating `latest` tag. Recovered by
  reading production's own currently-running image (`docker inspect --format '{{.Config.Image}}'
  kanban-board-backend-app-1` -> `rudenkovladimir/kanban-board-backend:6755c84`) and using that
  real tag for nonprod instead. `.env.nonprod.example`'s `IMAGE_TAG` comment was corrected in the
  same commit so a future operator does not hit the same 404 (Rule 1 — a bug in the plan's own
  documented default, fixed inline).
- **Database isolation proof's "before" reading for production was not captured strictly before
  step D's write** — the nonprod signup/board-create happened first, and production's watermark
  was read only afterward. Recovered by bracketing a *second* nonprod write (a column create)
  between two production readings instead: production's `kanban.activity` watermark was read
  immediately before and immediately after that second write and stayed flat at `74` in both
  readings, while nonprod's own watermark advanced `1 -> 2` across the same window — this
  demonstrates the same non-interference claim with a clean before/after bracket, just shifted to
  the second write rather than the first.
- **`/opt/deploy/kanban-board-nonprod/` required a one-time root action** (`mkdir` + `chown deploy:deploy`)
  before `deploy` could `scp` or write into it, since `/opt/deploy` itself is root-owned. This
  mirrors the exact precedent already recorded above in "Deploy user setup — Plan 05-05 Task 1" for
  `/opt/deploy/kanban-board-backend/` — not a new pattern, the same one-time setup step applied to
  a second directory.

## Operator note — deploying nonprod

**Updated by "Nonprod CI deploy identity and environment-scoped secrets — Plan 09-01" below:** as
of that plan, `/opt/deploy/kanban-board-nonprod/` is owned by the new `deploy-nonprod` identity, not
`deploy` — `deploy` no longer has read or write access to this directory. Nonprod Compose commands
now run as `deploy-nonprod`.

From `/opt/deploy/kanban-board-nonprod/` on the VM, as `deploy-nonprod`:
```
docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod up -d
```
Both `--env-file ./.env.nonprod` and `--profile nonprod` must be passed explicitly on every
invocation — Compose only auto-loads a file literally named `.env` (the same gotcha
"Manual deploy — Plan 05-04 Task 1" already documents for `.env.prod`), and the profile flag is
what keeps a bare `up -d` from creating anything at all (see the profile-gate proof above).

**Updated again by "Automated Avro schema registration — Plan 09-03" below:** schema registration
against the nonprod registry is no longer a manual follow-up step for this deploy sequence — CI
(the `deploy-to-nonprod` job) registers all 14 schemas automatically on every push to `master`,
between bringing up `redpanda-nonprod` and bringing up `app-nonprod`. An operator only needs to
run the registrar command by hand (see the superseded step 6 above) for a manual bring-up of this
kind, or to recover a registry that was wiped (e.g. by a volume deletion) outside of CI's own
push-triggered run.

## Bring-up date

2026-08-18.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
