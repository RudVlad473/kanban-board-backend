# Phase 11: Migrate database from Neon to self-hosted Postgres - Pattern Map

**Mapped:** 2026-08-26
**Files analyzed:** 6
**Analogs found:** 6 / 6

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `docker-compose.prod.yml` (add `postgres` service + `kanban-db` network) | config (infra service definition) | CRUD (data persistence host) | `docker-compose.prod.yml`'s own `redpanda` service (same file, same shape: resource-capped, healthchecked, internal-only, self-hosted) | exact |
| `docker-compose.nonprod.yml` (join `kanban-db`, retarget `app-nonprod` DB env) | config (network join + env) | request-response (client config) | `docker-compose.nonprod.yml`'s own `app-nonprod` → `kanban-edge` join (same file, cross-project network pattern) | exact |
| `docker/postgres-init/01-create-databases-and-roles.sh` (new) | config (init/provisioning script) | batch (one-shot DDL at container first-boot) | No existing init-script analog in this repo — closest conceptual sibling is Redpanda's `command:` block (declarative one-shot bring-up config), but the shell+psql shape has no direct precedent | none (see below) |
| `src/main/resources/application.properties` (Hikari block + `DB_JDBC_PARAMS` default, lines 55-139) | config (datasource/connection pool tuning) | request-response (connection lifecycle) | Same file, same block — this is a revision of the existing Neon-era tuning, not a new file | exact (self-modification) |
| `.github/workflows/deploy.yml` (`flyway-verify` / `flyway-verify-nonprod` jobs, lines 159-238) | config (CI job) | batch (one-off migration verification) | `register-schemas-production` job (same file, lines 337-370) — the proven "one-off container over SSH on the shared Docker network" pattern | exact |
| `docs/INFRA_RUNBOOK.md` (new dated sections: Postgres bring-up, D-08/D-10 measurement, D-12 backup-gap) | docs | n/a | Existing "Nonprod resource measurement — Plan 08-03" and production Redpanda Task 3 sections (same file) | exact |

## Pattern Assignments

### `docker-compose.prod.yml` — new `postgres` service (config, infra)

**Analog:** this file's own `redpanda` service (lines 142-231) and the local-dev `postgres` service in `docker-compose.yml` (lines 2-17)

**Cross-project network join pattern** (`docker-compose.prod.yml` lines 52-56, the proven `kanban-edge` shape to mirror for `kanban-db`):
```yaml
networks:
  default:
  kanban-edge:
    external: true
    name: kanban-edge
```
Apply identically for `kanban-db`, added alongside (not replacing) `kanban-edge`.

**Resource-capped, healthchecked, internal-only service shape** (`docker-compose.prod.yml` lines 142-231, `redpanda`):
```yaml
  redpanda:
    image: docker.redpanda.com/redpandadata/redpanda:v26.2.1
    hostname: redpanda
    restart: unless-stopped
    mem_limit: 2200m   # <-- MEASURED (restart-ladder), not arithmetic — see comment above it
    command: [ ... explicit resource caps, no --mode dev-container ... ]
    # No `ports:` entry at all -- neither the Kafka listener nor the Schema Registry is published
    # to the host. The app and Caddy reach this service only over the internal Compose network.
    volumes:
      - redpanda-data:/var/lib/redpanda/data
    healthcheck:
      test: [ "CMD-SHELL", "rpk cluster health | grep -E 'Healthy:.+true' || exit 1" ]
      interval: 5s
      timeout: 5s
      retries: 8
      start_period: 15s
    logging: *default-logging
```
Copy this shape for `postgres`: `mem_limit` set only after the D-08 restart-ladder measurement (see the comment directly above `redpanda`'s `mem_limit: 2200m` for the exact measurement-discipline wording to mirror — "Deliberately NOT set equal to..." reasoning), no `ports:` key (D-03), `pg_isready` healthcheck (per RESEARCH.md's Pattern), `logging: *default-logging`.

**Local-dev `postgres` service to adapt** (`docker-compose.yml` lines 2-17):
```yaml
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: ${DB_NAME}
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASS}
    ports:
      - "5433:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
```
This is the single-DB/single-role shape; the new prod service needs the multi-DB/multi-role init-script extension (Pattern 2 in RESEARCH.md) and must drop the `ports:` entry entirely (D-03 — this local-dev version is the one case where publishing a port is intentional and should NOT be copied).

**`app` service env var wiring to update** (`docker-compose.prod.yml` lines 111-126):
```yaml
    environment:
      DB_HOST: ${DB_HOST}
      DB_PORT: ${DB_PORT:-5432}
      DB_NAME: ${DB_NAME}
      DB_USER: ${DB_USER}
      DB_PASS: ${DB_PASS}
      DB_JDBC_PARAMS: ${DB_JDBC_PARAMS}
```
Variable *names* stay identical (per RESEARCH.md's Integration Points note) — only the `.env.prod` *values* change (`DB_HOST` becomes the new `postgres` service name, `DB_JDBC_PARAMS` becomes empty/absent per Pitfall 2).

**Mem_limit measurement-discipline comment to mirror** (`docker-compose.prod.yml` lines 93-107, `app`'s own `mem_limit: 3g` comment) — same "explicit ceiling because the JVM/engine defaults against the full host, not a bounded slice" reasoning shape should back whatever `mem_limit` is chosen for `postgres` and any revised `app` `mem_limit` (D-10).

---

### `docker-compose.nonprod.yml` — join `kanban-db`, retarget `app-nonprod` (config, network + env)

**Analog:** this file's own `app-nonprod` → `kanban-edge` join (lines 126-153)

**Cross-project network join with alias** (lines 135-141):
```yaml
  app-nonprod:
    networks:
      default:
      kanban-edge:
        aliases:
          - app-nonprod
```
Add `kanban-db:` as a third network key here (no `aliases:` needed on the consumer side per RESEARCH.md Pattern 1 — only `postgres`'s own external network declaration is required, matching how `kanban-edge`'s top-level `external: true` block (lines 37-42) is declared once per file).

**Env var block to retarget** (lines 142-153):
```yaml
    environment:
      DB_HOST: ${DB_HOST}
      DB_PORT: ${DB_PORT:-5432}
      DB_NAME: ${DB_NAME}
      DB_USER: ${DB_USER}
      DB_PASS: ${DB_PASS}
      DB_JDBC_PARAMS: ${DB_JDBC_PARAMS}
```
`DB_HOST` becomes `postgres` (the shared service's Compose-registered name); `DB_NAME`/`DB_USER`/`DB_PASS` become the nonprod-specific role/database values; `DB_JDBC_PARAMS` dropped (Pitfall 2).

---

### `docker/postgres-init/01-create-databases-and-roles.sh` (new, no direct in-repo analog)

**No close analog found.** RESEARCH.md's Pattern 2 supplies the canonical skeleton (CITED from the official `postgres` image docs), reproduced here as the pattern to implement against:
```bash
#!/usr/bin/env bash
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
The `REVOKE CONNECT ... FROM PUBLIC` lines are load-bearing (Pitfall 3), not optional — omitting them silently defeats D-01's isolation requirement. Mount read-only at `/docker-entrypoint-initdb.d` on the new `postgres` service (see the `volumes:` block in the service skeleton above).

---

### `src/main/resources/application.properties` — Hikari/JDBC block revision (config, self-modification)

**Analog:** the file's own current block (lines 55-139), which already carries a dated "Decisions" comment-record convention this repo uses for exactly this kind of revisit.

**Current datasource URL + Hikari block to revise** (lines 63-139):
```properties
spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT:5432}/${DB_NAME}?${DB_JDBC_PARAMS:prepareThreshold=0}
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASS}

spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=0
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.idle-timeout=300000
spring.datasource.hikari.max-lifetime=600000
spring.datasource.hikari.keepalive-time=0
```
Each value's existing comment (lines 55-133) documents *why* it was set for Neon — D-09 requires revisiting each one on its own merits for local Postgres, not blanket-copying. Follow this file's own established "decision record" comment convention (see lines 92-133's dated `# Decisions ---` block, itself a model of the "falsifiable, dated, cites the incident record" shape `docs/CODE_COMMENTS.md`'s "legitimate long comment" rule describes) when writing the new dated rationale for whatever D-09 lands on. `${DB_JDBC_PARAMS:prepareThreshold=0}` fallback default itself does not need to change shape — only whether `DB_JDBC_PARAMS` is populated at the Compose-file layer (Pitfall 2: must become empty/unset in both prod and nonprod Compose files, not merely "softened").

---

### `.github/workflows/deploy.yml` — `flyway-verify` / `flyway-verify-nonprod` (config, CI job)

**Analog:** `register-schemas-production` (lines 337-370, same file) — the proven "one-off container run via SSH on the shared internal Docker network" pattern D-13/RESEARCH.md's Pitfall 4 recommends over a literal tunnel.

**Current job to replace** (lines 159-202, `flyway-verify`; `flyway-verify-nonprod` at 209-238 is a literal near-duplicate against the `staging` environment):
```yaml
  flyway-verify:
    needs: [ setup, run-tests ]
    runs-on: ubuntu-latest
    if: success()
    environment: production
    steps:
      - name: Checkout code
        uses: actions/checkout@v5
      - name: Guard against Neon's pooled (transaction-mode) endpoint
        env:
          DB_HOST: ${{ secrets.DB_HOST }}
        run: |
          if [[ "$DB_HOST" == *"-pooler"* ]]; then
            echo "::error::..."
            exit 1
          fi
      - name: Verify Flyway migrations apply cleanly (Neon direct endpoint)
        env:
          FLYWAY_URL: jdbc:postgresql://${{ secrets.DB_HOST }}:5432/${{ secrets.DB_NAME }}?sslmode=require
          FLYWAY_USER: ${{ secrets.DB_USER }}
          FLYWAY_PASSWORD: ${{ secrets.DB_PASS }}
        run: |
          docker run --rm \
            -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD \
            -v "${{ github.workspace }}/src/main/resources/db/migration:/flyway/sql:ro" \
            flyway/flyway:11.7.2 migrate
```
The pooler guard step must be removed entirely (dead/misleading code once the target has no `-pooler` hostname, per RESEARCH.md's Runtime State Inventory). The runner-side `docker run ... -v github.workspace:/flyway/sql` mount cannot work once the target is SSH-only (no direct network line-of-sight from the runner) — that's what the `register-schemas-production` analog below solves.

**SSH one-off-container analog to copy** (lines 337-370):
```yaml
  register-schemas-production:
    needs: [ deploy-to-netcup, build-and-push-docker-image ]
    runs-on: ubuntu-latest
    if: success()
    environment: production
    steps:
      - name: Register Avro schemas (production registry)
        uses: appleboy/ssh-action@0ff4204d59e8e51228ff73bce53f80d53301dee2  # v1.2.5
        with:
          host: ${{ secrets.NETCUP_HOST }}
          username: ${{ secrets.NETCUP_DEPLOY_USER }}
          key: ${{ secrets.NETCUP_SSH_KEY }}
          fingerprint: ${{ secrets.NETCUP_HOST_FINGERPRINT }}
          script: |
            set -e
            cd /opt/deploy/kanban-board-backend
            export IMAGE_TAG=${{ needs.build-and-push-docker-image.outputs.image_tag }}
            docker compose --env-file ./.env.prod -f docker-compose.prod.yml run --rm --entrypoint java app \
              -Dloader.main=... -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher http://redpanda:8081
```
The digest-pinned `appleboy/ssh-action@...` version, the `NETCUP_*` secret set, and the `set -e` defense-in-depth comment convention should all be copied verbatim. RESEARCH.md's Code Examples section already supplies the retargeted Flyway version of this script (`docker run --rm --network kanban-db ... flyway/flyway:11.7.2 migrate`) — note it additionally requires the migration `.sql` directory to exist on the VM (copied by an SCP step, unlike today's runner-local checkout), a real structural difference from the `register-schemas-production` analog worth flagging as its own plan task.

---

### `docs/INFRA_RUNBOOK.md` — new dated sections (docs)

**Analog:** this file's own existing "Nonprod resource measurement — Plan 08-03" section and the production Redpanda Task 3 measurement narrative embedded in `docker-compose.prod.yml`'s `redpanda` comment (lines 174-202) — both already establish the "one dated section per plan/task, measured evidence inline, not summarized" structure this phase's new sections must follow.

**Structure to mirror** (inferred from `docker-compose.nonprod.yml`'s own reference to it, lines 89-116):
- A dated `## <Section title> — Plan/Task N` heading
- "Shape" (hardware), "Workload" (what traffic was fired), an "Iteration ladder" subsection listing each rung tried
- An "Adopted floor" subsection with the final chosen value and independent re-verification evidence
- A "Step below the floor" subsection documenting the failure mode at the rejected next-lower value

Apply this exact shape for D-08's Postgres `mem_limit` measurement, D-10's re-measured `app`/`app-nonprod` `mem_limit`, and a new backup-gap section for D-12 (the latter is prose documentation, not a measurement ladder — model it instead on how the "Database — Neon" section this phase supersedes is likely structured, i.e. one clearly-scoped subsection stating what exists today (nothing) and what a manual restore would require).

## Shared Patterns

### No host port published (D-03)
**Source:** `docker-compose.prod.yml`'s `redpanda` service (no `ports:` key at all, lines 142-231) and its comment: "No `ports:` entry at all -- neither the Kafka listener nor the Schema Registry is published to the host."
**Apply to:** the new `postgres` service in `docker-compose.prod.yml` — omit `ports:` entirely, unlike the local-dev `docker-compose.yml` version which intentionally publishes `5433:5432`.

### Measured-not-guessed `mem_limit`, documented inline with a dated comment
**Source:** `docker-compose.prod.yml`'s `redpanda.mem_limit: 2200m` comment (lines 148-202) and `docker-compose.nonprod.yml`'s `redpanda-nonprod.mem_limit: 300m` comment (lines 83-116), both citing the exact restart-ladder methodology and linking to `docs/INFRA_RUNBOOK.md`.
**Apply to:** the new `postgres` service's `mem_limit` (D-08) and any revised `app`/`app-nonprod` `mem_limit` (D-10) — every cap in this repo carries a dated, evidence-backed comment, not an arithmetic guess.

### Cross-Compose-project network sharing via an externally-created network
**Source:** `kanban-edge`, declared identically in both `docker-compose.prod.yml` (lines 52-56) and `docker-compose.nonprod.yml` (lines 37-42), joined by `caddy` (prod) and `app-nonprod` (nonprod) only — never by every service in either file.
**Apply to:** the new `kanban-db` network — declare `external: true` in both files, join only from `postgres` (prod) and `app-nonprod` (nonprod), not from `redpanda`/`redpanda-nonprod` or `caddy`.

### Logging anchor discipline
**Source:** `docker-compose.prod.yml`'s `x-logging: &default-logging` (lines 22-35) and `docker-compose.nonprod.yml`'s `x-logging: &nonprod-logging` (lines 19-26) — identical rotation limits, one anchor per file (Compose anchors don't span files), applied to every service so "no service lacks a logging block" holds.
**Apply to:** the new `postgres` service must carry `logging: *default-logging` (prod) — there is no nonprod twin since D-01 puts the shared instance in the prod file only.

### Dated "Decisions" comment-record convention for revisited config
**Source:** `application.properties` lines 92-133, the existing 2026-08-26 dated block explaining the Neon-specific Hikari reversal, itself following `CODE_COMMENTS.md`'s "legitimate long comment" rule (falsifiable, dated, cites the incident record, segregated from surrounding narration).
**Apply to:** whatever new dated rationale D-09's revisit lands on — must state what changed, why (local vs. remote DB), and be falsifiable, not a rewritten summary that discards the prior record's own discipline.

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `docker/postgres-init/01-create-databases-and-roles.sh` | config (init script) | batch (one-shot DDL) | No existing shell/psql init-script exists anywhere in this repo — RESEARCH.md's Pattern 2 (CITED from the official `postgres` image docs) is the correct source to implement against instead of an in-repo analog |

## Metadata

**Analog search scope:** `docker-compose.prod.yml`, `docker-compose.nonprod.yml`, `docker-compose.yml`, `src/main/resources/application.properties`, `.github/workflows/deploy.yml`, `docs/INFRA_RUNBOOK.md` (referenced, not fully re-read — sections already quoted verbatim in RESEARCH.md)
**Files scanned:** 6 (all directly read this session)
**Pattern extraction date:** 2026-08-26
