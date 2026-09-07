# Phase 12: Self-hosted observability stack - Pattern Map

**Mapped:** 2026-09-07
**Files analyzed:** 12 (7 new compose services + 5 new config files, plus 2 existing-file edits)
**Analogs found:** 12 / 12 (all role-matched against existing infra files; no application-code
analogs apply — this phase is entirely Physical/Deployment tier)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `docker-compose.prod.yml` (new `prometheus`/`grafana`/`loki`/`promtail`/`cadvisor`/`node-exporter`/`postgres-exporter` service blocks) | config (compose service) | batch/pull-scrape | `docker-compose.prod.yml`'s `redpanda`/`postgres` service blocks | exact |
| `docker-compose.prod.yml` (`caddy` service — add `mem_limit`, D-02) | config (compose service edit) | request-response | `docker-compose.prod.yml`'s `postgres`/`redpanda` `mem_limit` comment style | exact |
| `docker-compose.nonprod.yml` (`redpanda-nonprod` — join new `kanban-metrics` network) | config (compose service edit) | event-driven (scrape target) | `docker-compose.nonprod.yml`'s existing `kanban-db`/`kanban-edge` cross-project network wiring on `app-nonprod` | exact |
| `Caddyfile` (new Grafana site block or path route) | config (reverse-proxy route) | request-response | `Caddyfile`'s `{$APP_DOMAIN_NONPROD}` site block | exact |
| `docker/postgres-init/` — NOT a new file; monitoring role via live `docker exec` | utility (one-off ops script/runbook entry) | request-response | `docker/postgres-init/01-create-databases-and-roles.sh` (pattern to READ, not copy as a new init script — Pitfall 2) | role-match (deliberately not structurally cloned) |
| `docker/prometheus/prometheus.yml` | config (scrape config) | pub-sub (pull) | no direct in-repo YAML-config analog; closest is `docker/postgres-init/01-create-databases-and-roles.sh`'s var-substitution discipline for structure/documentation tone | no strong analog (see below) |
| `docker/loki/loki-config.yaml` | config | batch (retention) | same as above | no strong analog |
| `docker/promtail/promtail-config.yaml` | config | streaming (log tail) | same as above | no strong analog |
| `docker/grafana/provisioning/datasources/datasources.yaml` | config | request-response | same as above | no strong analog |
| `docker/grafana/provisioning/dashboards/dashboards.yaml` | config | request-response | same as above | no strong analog |
| `docker/grafana/dashboards/json/*.json` | config (vendored, committed) | static asset | none — pre-fetched community JSON, not authored | no analog needed |
| `docs/INFRA_RUNBOOK.md` (new "resource measurement" section per new container) | test/doc (measurement record) | batch | `docs/INFRA_RUNBOOK.md`'s existing "Nonprod resource measurement — Plan 08-03" / "Self-hosted Postgres resource measurement — Plan 11-03" sections | exact |

## Pattern Assignments

### `docker-compose.prod.yml` — new service blocks (prometheus, grafana, loki, promtail, cadvisor, node-exporter, postgres-exporter)

**Analog:** `docker-compose.prod.yml`'s `redpanda` service block (lines 337-360+) and `postgres` service block (lines ~97-230)

**Structural pattern to copy** (every new service must have all of these, per the file's own established shape):
```yaml
  <service-name>:
    image: <pinned-org>/<image>:<exact-tag>   # never `latest` — every existing image here is pinned
    hostname: <service-name>                   # redpanda/postgres both set this explicitly
    restart: unless-stopped
    mem_limit: <measured>m                     # NEVER guessed — see Resource Sizing pattern below
    networks:
      - default                                # explicit once any `networks:` key is declared —
                                                 # dropping this silently severs default connectivity
                                                 # (both redpanda and postgres comment this exact gotcha)
      # - kanban-metrics  (only prometheus + redpanda-nonprod join this NEW network, per Pitfall 1)
    volumes:
      - <name>-data:/path/to/state             # named volume for anything with state to persist —
                                                 # mirrors postgres-data/redpanda-data/caddy-data
    # No `ports:` entry — mechanically enforced by scripts/verify-compose-ports.py (see below).
    healthcheck:
      test: [ "CMD-SHELL", "..." ]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    logging: *default-logging                  # reuse the file's existing x-logging anchor, do
                                                 # NOT define a new logging block per service
```

**Resource sizing / mem_limit comment discipline** (copy the FORM, not the numbers — every cap in
this file carries a dated, measured justification, never an arithmetic guess):
```yaml
    # MEASURED BASIS (Plan 12-0X, <date>) -- host: Netcup VPS Lite 2 G12s, 4 vCPU / 7.8GiB
    # measured. Workload: <what was fired at it>.
    # Ladder descended from <iteration-0> -> ... -> <adopted>. <rung> FAILED: <evidence, e.g.
    # dmesg OOM-kill or crash-loop RestartCount>.
    # Adopted: <value>, leaves ~<X>% headroom above measured peak of <Y>.
    mem_limit: <value>
```
Full evidence goes in `docs/INFRA_RUNBOOK.md` under a new "`<service>` resource measurement —
Plan 12-0X" section, mirroring the existing "Nonprod resource measurement" / "Self-hosted Postgres
resource measurement" sections exactly (headers: `### Iteration ladder`, `### Adopted floor`,
`### Step below the floor`).

**No-published-port pattern** (D-03/D-05 from Phase 11, reaffirmed for all 7 new services):
```
    # No `ports:` entry at all (D-03) -- mirrors redpanda's/postgres's own posture exactly.
    # Mechanically enforced: scripts/verify-compose-ports.py fails CI if this service ever gains one.
```
Copy verbatim from `docker-compose.prod.yml`'s `postgres` service (search string
`"Mechanically enforced: scripts/verify-compose-ports.py"` — appears identically on `caddy`... no,
on `app`, `postgres`, and both nonprod services).

---

### `docker-compose.prod.yml` — `caddy` service, add `mem_limit` (D-02)

**Analog:** the `postgres`/`redpanda` `mem_limit` measurement-comment pattern above — same
restart-ladder discipline applies to `caddy`, which currently has none. No shortcut: measure it
live (idle ~19.9-20MiB per the redpanda comment's own idle-baseline note, line ~377 of this file:
"caddy stayed flat (~20MiB, <0.2% CPU)"), then run the ladder, don't just adopt that idle number.

---

### `docker-compose.nonprod.yml` — `redpanda-nonprod`, join new `kanban-metrics` network

**Analog:** `docker-compose.nonprod.yml`'s existing `kanban-db`/`kanban-edge` `networks:` top-level
declarations (lines ~35-47) and `app-nonprod`'s per-network `aliases:` usage.

**Pattern to copy** (top-level network declaration, mirrors `kanban-db`'s exact shape):
```yaml
networks:
  ...
  kanban-metrics:
    # Third cross-project network (phase 12) -- created once via `docker network create
    # kanban-metrics`, joined by this file's redpanda-nonprod service and
    # docker-compose.prod.yml's prometheus service ONLY. Mirrors kanban-edge/kanban-db's own
    # "one purpose-scoped external network per cross-project concern" precedent (Pitfall 1,
    # 12-RESEARCH.md) -- neither existing network is the right scope for Prometheus scraping.
    external: true
    name: kanban-metrics
```
Then add `- kanban-metrics` to `redpanda-nonprod`'s `networks:` list (currently just `- default`,
with an explicit comment "Deliberately NOT on kanban-edge" that must be updated to reflect the new,
intentionally scoped exception).

---

### `Caddyfile` — new Grafana route (D-03)

**Analog:** `Caddyfile`'s `{$APP_DOMAIN_NONPROD}` site block (final ~6 lines) — the simplest
existing block, since D-03 explicitly wants NO `rate_limit` directive and NO extra gate on
Grafana, matching the nonprod block's own "deliberately no rate_limit directive" precedent.

**Core pattern** (subdomain form, the RESEARCH.md-recommended default):
```
# Third site block, added plan 12-0X (D-03). {$APP_DOMAIN_MONITORING} supplied by
# docker-compose.prod.yml's caddy.environment.APP_DOMAIN_MONITORING -- an enumerated DuckDNS
# subdomain, never a wildcard (same reasoning as {$APP_DOMAIN_NONPROD} above). Shares the one
# caddy-data volume, so recreating this container reuses this cert too.
#
# Deliberately no rate_limit directive here (mirrors {$APP_DOMAIN_NONPROD}'s own reasoning) --
# Grafana's own login is the access gate (D-03), not Caddy.
{$APP_DOMAIN_MONITORING} {
	reverse_proxy grafana:3000
}
```
Corresponding `docker-compose.prod.yml` `caddy.environment` addition — copy the exact shape of
the existing `APP_DOMAIN_NONPROD: ${APP_DOMAIN_NONPROD}` line.

---

### `docker/postgres-init/` — monitoring role (D-06) — READ this file, do NOT clone its structure

**Analog:** `docker/postgres-init/01-create-databases-and-roles.sh`

**What to copy:** the `set -eo pipefail` (no `-u`) convention, the `: "${VAR:?msg}"` guard style,
and the `-v var=value ... :"var"`/`:'var'` psql variable-substitution pattern (never string-built
SQL) — these are the project's SQL-safety conventions and apply equally to the one-off `docker exec`
command RESEARCH.md's Code Examples section already drafts.

**What NOT to copy:** do not add a `02-create-monitoring-role.sh` file to this directory — Pitfall 2
documents exactly why (`/docker-entrypoint-initdb.d` only runs on first boot against an empty data
directory, and this volume is already populated). Run the SQL live via `docker exec -i postgres
psql ...` instead, and document the exact command + expected output in
`docs/INFRA_RUNBOOK.md` as a runbook entry, not a repo script.

---

### `docs/INFRA_RUNBOOK.md` — new resource-measurement sections

**Analog:** `docs/INFRA_RUNBOOK.md`'s existing `"## Nonprod resource measurement -- Plan 08-03"`
and `"## Self-hosted Postgres resource measurement -- Plan 11-03"` sections (referenced from
`docker-compose.nonprod.yml`'s `redpanda-nonprod` comment and `docker-compose.prod.yml`'s
`postgres` comment respectively).

**Structure to replicate per new container** (subsections, exact headers):
```
## <Service> resource measurement -- Plan 12-0X
### Iteration ladder
### Adopted floor
### Step below the floor
```
Each rung entry states: cap value, workload fired, RestartCount, RSS/CPU sampled, pass/fail
verdict — same shape as the `postgres`/`redpanda-nonprod` comments quoted above.

---

## Shared Patterns

### Named volumes for stateful new services
**Source:** `docker-compose.prod.yml` — `postgres-data`, `redpanda-data`, `caddy-data`,
`caddy-config` volume declarations at file top-level.
**Apply to:** `prometheus` (TSDB, `prometheus-data:/prometheus`), `loki` (chunks,
`loki-data:/loki`), `grafana` (`grafana-data:/var/lib/grafana`). NOT needed for `cadvisor`,
`node-exporter`, `postgres-exporter`, `promtail` (stateless scrapers; Promtail's positions file
can be an anonymous/ephemeral volume or bind mount, not a durable named one — no existing analog
treats scrape-position bookkeeping as durable state worth naming).

### `x-logging: &default-logging` anchor reuse
**Source:** `docker-compose.prod.yml` lines 20-32.
**Apply to:** every one of the 7 new services — `logging: *default-logging`, never a bespoke
logging block. The anchor's own comment states the acceptance check explicitly: "no service lacks
a logging block."

### No-published-port discipline + its CI gate
**Source:** `docker-compose.prod.yml`/`docker-compose.nonprod.yml`, every service's `ports:`
omission comment, backed by `scripts/verify-compose-ports.py`.
**Apply to:** all 7 new services. Grafana in particular — easy to reach for a stray `- "3000:3000"`
during manual testing; the CI gate will catch it, but the plan should not introduce it even
temporarily as a "temporary" testing convenience.

### `.env.prod` / `.env.prod.example` secret sourcing
**Source:** existing `${DB_PASS}`, `${POSTGRES_SUPERUSER_PASS}`, `${APP_DOMAIN_NONPROD}`-style
`environment:` interpolation throughout `docker-compose.prod.yml` — no literal defaults ever
appear in the compose file itself.
**Apply to:** `GF_SECURITY_ADMIN_PASSWORD` (Grafana), `MONITORING_PASS`/`DATA_SOURCE_NAME`
(postgres-exporter), `APP_DOMAIN_MONITORING` (Caddy) — every one added as a new `${VAR}` reference
in the compose file and a corresponding new row in `.env.prod.example` (names only, no values —
do not read or write actual secret values into planning artifacts per this repo's gitleaks gate).

### Restart-ladder measurement methodology (not a code pattern, but a required process step)
**Source:** `docs/INFRA_RUNBOOK.md`'s two existing sections (see above); referenced from
`12-RESEARCH.md`'s Open Question 2.
**Apply to:** every one of the 7 new services' `mem_limit` — no numeric value should appear in
any PLAN.md or compose file without a corresponding ladder run recorded in INFRA_RUNBOOK.md,
exactly as Phase 8 (Redpanda-nonprod) and Phase 11 (Postgres) both did.

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `docker/prometheus/prometheus.yml` | config | pub-sub (pull scrape) | No existing Prometheus/YAML-scrape-config file in this repo; use RESEARCH.md's Pattern 3 code example (D-04 `env` labels) as the template instead of an in-repo analog. |
| `docker/loki/loki-config.yaml` | config | batch (retention) | No existing Loki config in this repo; use RESEARCH.md's Code Examples / config-shape citations (Context7 `/grafana/loki`) directly. |
| `docker/promtail/promtail-config.yaml` | config | streaming | No existing Promtail config; use RESEARCH.md's Pattern 2 code example verbatim (including the `docker: {}` pipeline stage per Pitfall 5, and the deliberate absence of a `filters:` block per Pitfall 6). |
| `docker/grafana/provisioning/datasources/datasources.yaml` | config | request-response | No existing Grafana provisioning file; use RESEARCH.md's Pattern 1 code example. |
| `docker/grafana/provisioning/dashboards/dashboards.yaml` | config | request-response | Same as above. |
| `docker/grafana/dashboards/json/*.json` | config (vendored) | static | Pre-fetched from grafana.com/api per RESEARCH.md's Pattern 1 `curl` command — not hand-authored, no in-repo analog applies or is needed. |

For all six of these, the planner should cite `12-RESEARCH.md`'s "Architecture Patterns" section
(Patterns 1-3) and "Code Examples" section directly as the authoritative shape, since this repo has
no prior file of this kind to pattern-match against — only the surrounding compose/network/logging
conventions (covered above) carry forward from existing code.

## Metadata

**Analog search scope:** `docker-compose.prod.yml`, `docker-compose.nonprod.yml`, `Caddyfile`,
`docker/postgres-init/01-create-databases-and-roles.sh`, `docs/INFRA_RUNBOOK.md` (referenced,
not fully read — its two named sections are the structural analog, contents not re-read here to
avoid duplicate context cost)
**Files scanned:** 5 read directly (4 in full/near-full, 1 grep-targeted)
**Pattern extraction date:** 2026-09-07
