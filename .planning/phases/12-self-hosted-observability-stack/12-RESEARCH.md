# Phase 12: Self-hosted observability stack - Research

**Researched:** 2026-09-07
**Domain:** Self-hosted metrics/logs stack on a resource-capped single-VM Docker Compose deployment (Prometheus + Grafana + Loki/Promtail + cAdvisor + node_exporter + postgres_exporter)
**Confidence:** MEDIUM-HIGH — image tags/config shapes verified against official docs/registries this session; resource caps and network topology are reasoned recommendations for the planner to validate against this project's own measurement discipline, not yet measured on the real VM.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Fold `2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md` into
  this phase's scope — this is the todo the phase was originally scoped from. Loki/Promtail
  closes the log-shipping half; the todo's alerting half stays open (this phase explicitly
  excludes alerting).
- **D-02:** Fold `2026-09-03-caddy-service-has-no-mem-limit.md` into this phase — opportunistic,
  since this phase already edits the `caddy` service block in `docker-compose.prod.yml` to add
  a Grafana route. Add the missing `mem_limit` (measured, not guessed, per this project's
  established documentation discipline) while that file is already open.
- **D-03:** Grafana sits behind Caddy's `reverse_proxy` with no additional Caddy-layer gate
  (no `basic_auth`, no IP allowlist) — relies on Grafana's own login (admin/password or OAuth)
  the same way the app's own endpoints rely on Spring Security alone rather than a Caddy-level
  gate. Reversible — a `basic_auth` or IP-allowlist directive can be added later with no migration.
- **D-04:** One shared Prometheus/Loki/Grafana stack monitors BOTH production and nonprod
  (containers distinguished by label, e.g. `env=prod` / `env=nonprod`) — not two separate
  stacks. Mirrors Phase 11's D-01 shared-Postgres-instance precedent. Costly to reverse.
- **D-05:** Include cAdvisor for per-container CPU/mem/network breakdown, with its
  `/var/run/docker.sock`, `/sys`, `/proc` mounts **read-only**. Accepted as a scoped, known
  exception — tracked separately from the existing open todo about the app container running
  as root. Reversible — cAdvisor can be removed as a standalone service.
- **D-06:** Include `postgres_exporter` (new container/service) and Redpanda's own native
  `/metrics` Prometheus endpoint in this phase — not deferred. Reversible — each is an
  independent scrape target/service.
- **D-07:** 30 days of retention for both Prometheus (`--storage.tsdb.retention.time=30d`) and
  Loki, sized against the 222 GB disk headroom rather than Prometheus's 15-day default.
  Reversible — a config value change.
- **D-08:** Promtail ships logs from ALL containers (app, nonprod-app, postgres, redpanda,
  redpanda-nonprod, caddy) via Docker's `json-file` log driver — not app-containers-only.
  Reversible — a Promtail scrape-config change.

### Claude's Discretion

- Compose file topology (whether the new services live in `docker-compose.prod.yml` alongside
  the existing shared services, or a separate `docker-compose.monitoring.yml`) — an
  implementation detail for research/planning to resolve against this project's existing
  multi-compose-file conventions (`docker-compose.yml` / `.prod.yml` / `.nonprod.yml`).
- Dashboard provisioning mechanism (Grafana dashboards-as-code JSON vs. UI-created) and which
  specific community dashboards to import for node_exporter/cAdvisor/Postgres/Redpanda.
- Exact container/service naming, network placement (which of `kanban-edge`/`kanban-db`/a new
  network each new service joins), and `mem_limit` values — sized via the same live
  restart-ladder measurement method Phase 11 used, not arithmetic guesses.

### Deferred Ideas (OUT OF SCOPE)

- Alerting (Alertmanager, notification channels) — explicitly out of scope for this phase; the
  natural next phase once metrics/logs exist to alert on.
- Structured/UTC logging format standard across the application's own log statements — the
  other open half of the folded log-shipping todo; an application-level change, not infra.
- Container-hardening (non-root `USER` directive in the Dockerfile) — pre-existing, unrelated
  todo; not touched by this phase even though cAdvisor's host-level mounts (D-05) sit in
  similar territory.
</user_constraints>

<phase_requirements>
## Phase Requirements

No `REQUIREMENTS.md` exists — this is a not-yet-scoped milestone whose acceptance surface is the
8 locked decisions in `12-CONTEXT.md` (D-01..D-08 above), not a numbered requirement list.

| ID | Description | Research Support |
|----|-------------|------------------|
| D-01/D-08 | Loki/Promtail log aggregation across all 6 named containers | Promtail `docker_sd_configs` example below discovers every container on the host regardless of Compose project — one config satisfies "ALL containers" with no per-container edits (see Code Examples, Pitfall 6) |
| D-02 | `mem_limit` added to `caddy` service | Same restart-ladder measurement methodology as every other cap in this file (see Resource Sizing Methodology) |
| D-03 | Grafana behind Caddy, no extra gate | Caddy routing pattern researched below (new subdomain vs. path route) |
| D-04 | One shared stack, `env` label distinguishes prod/nonprod | `prometheus.yml` example below labels each static target `env: prod` / `env: nonprod` |
| D-05 | cAdvisor read-only host mounts | Exact mount set + Docker Engine (non-K8s) gotchas researched below (Pitfall 3) |
| D-06 | postgres_exporter + Redpanda native `/metrics`/`/public_metrics` | DSN/role requirements and Redpanda scrape target researched below |
| D-07 | 30d retention, Prometheus + Loki | Flag + `limits_config`/`compactor` shape below |
| D-08 | Promtail scrapes ALL containers | `docker_sd_configs` with no `filters:` block (see above) |
</phase_requirements>

## Summary

This phase adds seven new containers to a VM that already runs `app`, `postgres`, `redpanda`,
and `caddy` under tight, measured `mem_limit`s. The domain is well-trodden (this is the
textbook Prometheus+Grafana+Loki self-hosted stack, not a novel design), so the standard stack
and config shapes below are low-risk. The two things that are NOT boilerplate and need real
planning attention are (1) **cross-Compose-project network topology** — Prometheus must reach
`redpanda-nonprod`, which lives in a different Compose project that today deliberately does
NOT share a network with anything except `app-nonprod` — and (2) **Promtail is end-of-life**
(EOL as of 2026-03-02 per Grafana's own docs, superseded by Grafana Alloy) even though it is
the tool CONTEXT.md's D-01/D-08 name explicitly; it still works and is still on Docker Hub, but
the planner should carry this as a known, accepted trade-off rather than an unexamined choice.

**Primary recommendation:** Add all seven services to `docker-compose.prod.yml` (not a new
`docker-compose.monitoring.yml`) — this reuses the implicit `default` network for every prod-side
scrape target and Grafana's Caddy proxy for free, mirroring Phase 11's own precedent of placing
the shared `postgres` service in `docker-compose.prod.yml` rather than a third file "following
the kanban-edge cross-project precedent" (STATE.md decision log, Phase 11 plan 01). Create exactly
one new external network (e.g. `kanban-metrics`) shared between `docker-compose.prod.yml`
(Prometheus) and `docker-compose.nonprod.yml` (`redpanda-nonprod`) — the single cross-project
concern this phase introduces — rather than reusing `kanban-edge` (wrong scope: edge/Caddy
traffic) or `kanban-db` (wrong scope: Postgres access only).

## Architectural Responsibility Map

This phase's capabilities don't map cleanly onto the project's usual Browser/Frontend/API/DB
tiers (`.claude/CLAUDE.md`'s table) — everything here is infrastructure/deployment tier. Mapped
against the closest-fit tiers and Kruchten's Physical/Deployment view instead:

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Host/container metrics collection | Physical/Deployment (new: node_exporter, cAdvisor) | — | Reads `/proc`, `/sys`, `docker.sock` directly on the VM; no application code involved |
| Postgres/Redpanda internal metrics | Database/Storage (postgres_exporter) + existing Redpanda process | — | postgres_exporter is a new sidecar process reading `pg_stat_*`; Redpanda already exposes `/public_metrics` natively — zero app change |
| Metrics storage/query | Physical/Deployment (new: Prometheus) | — | Pull-based scraper + TSDB, config-file driven |
| Log aggregation | Physical/Deployment (new: Loki + Promtail) | — | Promtail reads Docker's `json-file` driver output directly; no application logging-format change (deferred) |
| Dashboarding/visualization | Physical/Deployment (new: Grafana) | — | Reads from Prometheus/Loki; provisioned as code |
| Public HTTPS routing to Grafana | CDN/Edge (existing `caddy`) | — | Same `reverse_proxy` mechanism already routing to `app`/`app-nonprod` |
| Access control for Grafana | Physical/Deployment (Grafana's own auth) | CDN/Edge (deliberately absent, D-03) | Grafana's built-in login only, mirroring the app's own Spring-Security-only posture — no Caddy-layer gate added |

## Standard Stack

### Core

| Image | Tag | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `prom/prometheus` | `v3.14.0` | Metrics scrape + TSDB + query engine | The reference Prometheus implementation; `prom/prometheus` is the official image [CITED: hub.docker.com/r/prom/prometheus/tags] |
| `grafana/grafana` | `13.2.1` | Dashboards, alerting UI (alerting itself out of scope), datasource query layer | Official Grafana Labs image [CITED: hub.docker.com/r/grafana/grafana/tags] |
| `grafana/loki` | `3.7.7` | Log aggregation, single-binary mode | Official Loki image, the de facto pairing with Promtail/Grafana [CITED: hub.docker.com/r/grafana/loki/tags] |
| `grafana/promtail` | `3.6.11` | Log shipping agent, discovers/tails Docker container logs | Official Promtail image — **see State of the Art: Promtail is EOL, see below** [CITED: grafana.com/docs/loki/latest/send-data/promtail/] |
| `ghcr.io/google/cadvisor` | `v0.60.5` | Per-container CPU/mem/network/disk metrics | Google's own cAdvisor project; **image moved from `gcr.io/cadvisor/cadvisor` to `ghcr.io/google/cadvisor` at v0.53.0+** — the phase description's `gcr.io/cadvisor/cadvisor` is the pre-migration path and should not be used for a new deployment [CITED: github.com/google/cadvisor/pkgs/container/cadvisor, github.com/google/cadvisor/issues/3856] |
| `prom/node-exporter` | `v1.12.1` | Host-level CPU/memory/disk/network metrics | The reference Prometheus host exporter [CITED: hub.docker.com/r/prom/node-exporter/tags] |
| `prometheuscommunity/postgres-exporter` | `v0.20.1` | Postgres-internal metrics (`pg_stat_*`, connections, replication) | The community-maintained (Prometheus Community org) standard Postgres exporter, also mirrored at `quay.io/prometheuscommunity/postgres-exporter` [CITED: github.com/prometheus-community/postgres_exporter, hub.docker.com/r/prometheuscommunity/postgres-exporter/tags] |

### Supporting

| Item | Purpose | When to Use |
|---------|---------|-------------|
| Redpanda native `/public_metrics` (already running, no new image) | Aggregated, low-cardinality Redpanda metrics — the recommended scrape target over the legacy `/metrics` endpoint | Always prefer `/public_metrics` for dashboards; use `/metrics` only for ad hoc debugging [CITED: docs.redpanda.com/current/manage/monitoring/] |
| Postgres `pg_monitor` built-in role | Grants postgres_exporter read access to `pg_stat_*`/`pg_settings`/replication state without superuser | Required grant for the new monitoring role (see Code Examples) [CITED: github.com/prometheus-community/postgres_exporter README] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Promtail | Grafana Alloy | Alloy is the actively-maintained successor and has an automated Promtail-config migration tool, but is a materially different config language (River, not YAML) and a bigger footprint to learn/operate for a single-operator personal project; D-01/D-08 already name Promtail explicitly. Documented as a known trade-off, not silently substituted (see State of the Art). |
| `docker-compose.monitoring.yml` (separate file) | `docker-compose.prod.yml` (same file as existing shared services) | A separate file matches this project's per-environment file convention (`.yml`/`.prod.yml`/`.nonprod.yml`) but forces every new service to cross a Compose-project boundary to reach `redpanda`/`postgres`/`caddy`, multiplying the number of new external networks needed. Recommended: same file, per Phase 11's own precedent (see Summary). |
| Per-database `postgres_exporter` (one exporter per DB) | Single exporter with `--auto-discover-databases` | Two DBs live on one shared instance (D-01/D-04 precedent); one exporter process with autodiscovery avoids running two near-identical containers for two databases on the same engine. |

**Installation:**
```bash
docker pull prom/prometheus:v3.14.0
docker pull grafana/grafana:13.2.1
docker pull grafana/loki:3.7.7
docker pull grafana/promtail:3.6.11
docker pull ghcr.io/google/cadvisor:v0.60.5
docker pull prom/node-exporter:v1.12.1
docker pull prometheuscommunity/postgres-exporter:v0.20.1
```

**Version verification:** All seven tags above were confirmed to exist and be current via a direct
`WebFetch` of each project's Docker Hub tags page (or GHCR package page for cAdvisor) and
cross-checked against the corresponding GitHub Releases page, this session (2026-09-07). Re-verify
at plan-execution time if this research is more than ~30 days old — this is a fast-moving stack
(Grafana ships weekly-ish patch releases; Prometheus/Loki monthly-ish).

## Package Legitimacy Audit

> The automated `package-legitimacy check` seam supports `npm|pypi|crates` ecosystems only —
> Docker image provenance has no equivalent automated gate in this toolchain. The table below is
> manual diligence performed this session in its place: each image's registry namespace,
> maintaining org, and multi-source cross-check.

| Image | Registry | Maintaining Org | Cross-checked | Verdict | Disposition |
|-------|----------|-----------------|----------------|---------|-------------|
| `prom/prometheus` | Docker Hub, official `prom` org | Prometheus project (CNCF) | Docker Hub tags page + GitHub releases page agree on `v3.14.0` | OK | Approved |
| `grafana/grafana` | Docker Hub, official `grafana` org | Grafana Labs | Docker Hub tags page + GitHub releases page agree on `13.2.1` | OK | Approved |
| `grafana/loki` | Docker Hub, official `grafana` org | Grafana Labs | Docker Hub tags page + GitHub releases page agree on `3.7.7` | OK | Approved |
| `grafana/promtail` | Docker Hub, official `grafana` org | Grafana Labs (EOL, LTS mode) | Docker Hub tags page (`3.6.11`) + official EOL notice on grafana.com/docs | OK, but EOL — see State of the Art | Approved, flagged |
| `ghcr.io/google/cadvisor` | GHCR, official `google` org | Google | GitHub releases (`v0.60.5`) + GHCR package page confirming the `gcr.io` → `ghcr.io` migration | OK | Approved |
| `prom/node-exporter` | Docker Hub, official `prom` org | Prometheus project | Docker Hub tags page + GitHub releases page agree on `v1.12.1` | OK | Approved |
| `prometheuscommunity/postgres-exporter` | Docker Hub, official `prometheuscommunity` org | Prometheus Community | Docker Hub tags page + GitHub repo agree on `v0.20.1`, also mirrored on `quay.io` | OK | Approved |

**Packages removed due to [SLOP] verdict:** none.
**Packages flagged as suspicious [SUS]:** none — all seven images are published by their
project's official org on Docker Hub or GHCR, all have multi-year histories and active recent
releases (confirmed this session), and none showed a suspicious `postinstall`-equivalent
(Docker images have no npm-style install script attack surface).

## Architecture Patterns

### System Architecture Diagram

```
                         Internet (HTTPS)
                                │
                                ▼
                     ┌─────────────────────┐
                     │       Caddy          │  (existing; D-02 adds mem_limit,
                     │  reverse_proxy       │   D-03 adds Grafana route)
                     └──────┬────────┬──────┘
                 existing   │        │  NEW: {$APP_DOMAIN_MONITORING}
                 site blocks│        │
                 (app,      │        ▼
                 app-nonprod)│  ┌───────────┐        ┌────────────┐
                             │  │  Grafana  │───────▶│ Prometheus │
                             │  │ (D-03)    │        │ (D-04,D-07)│
                             │  └───────────┘        └─────┬──────┘
                             │        │                    │ scrapes (pull)
                             │        ▼                    ├──────────────┬──────────────┬──────────────────────┐
                             │  ┌───────────┐               ▼              ▼              ▼                       ▼
                             │  │   Loki    │◀──push──┌──────────┐  ┌───────────┐  ┌──────────────┐  ┌────────────────────┐
                             │  │ (D-07)    │         │ Promtail │  │node_export│  │   cAdvisor    │  │  postgres_exporter  │
                             │  └───────────┘         │ (D-08)   │  │ (host)    │  │ (D-05, RO     │  │  (D-06, pg_monitor  │
                             │                        └────┬─────┘  └───────────┘  │  mounts)      │  │  role, kanban-db)   │
                             │                             │                                              │
                             ▼                    reads docker.sock                                        ▼
                       app / app-nonprod           + /var/lib/docker (RO)                            postgres (existing,
                       (existing containers,        discovers ALL containers                          D-01 shared instance)
                       untouched)                   on the HOST, both Compose
                                                     projects, no per-container
                                                     config needed (D-08)

  Prometheus also scrapes redpanda:9644/public_metrics AND redpanda-nonprod:9644/public_metrics
  (D-06) — the latter requires a NEW cross-Compose-project network (see Pitfall 1).
```

### Recommended Project Structure

```
docker/
├── postgres-init/                        # existing (Phase 11)
│   └── 01-create-databases-and-roles.sh
├── caddy/                                 # existing
│   └── Dockerfile
├── prometheus/
│   └── prometheus.yml                     # NEW — scrape config, mounted read-only
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/datasources.yaml   # NEW — Prometheus + Loki datasources
│   │   └── dashboards/dashboards.yaml     # NEW — file provider pointing at ./dashboards/json
│   └── dashboards/json/
│       ├── node-exporter-full.json        # NEW — pre-fetched from grafana.com/api, committed
│       ├── cadvisor.json                  # NEW
│       └── postgres-exporter.json         # NEW
├── loki/
│   └── loki-config.yaml                   # NEW — single-binary, filesystem, 30d retention
└── promtail/
    └── promtail-config.yaml               # NEW — docker_sd_configs, no filters (D-08)
```

### Pattern 1: Grafana provisioning-as-code (datasources + dashboards)

**What:** Grafana auto-loads datasource and dashboard definitions from YAML/JSON files mounted
into `/etc/grafana/provisioning/{datasources,dashboards}` at container start — no manual UI
clicks, matching this project's "everything as code" convention.
**When to use:** Always, for a reproducible deploy — the alternative (UI-created dashboards) is
lost on volume loss and undocumented in git.
**Example:**
```yaml
# Source: https://grafana.com/docs/grafana/latest/administration/provisioning (Context7 /websites/grafana_grafana)
# docker/grafana/provisioning/datasources/datasources.yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
```
```yaml
# docker/grafana/provisioning/dashboards/dashboards.yaml
apiVersion: 1
providers:
  - name: default
    type: file
    updateIntervalSeconds: 30
    options:
      path: /etc/grafana/provisioning/dashboards/json
      foldersFromFilesStructure: false
```
Dashboard JSON files themselves are NOT fetched at runtime — the file provider only reads a
local path. Pre-fetch each dashboard once and commit the JSON:
```bash
curl -s https://grafana.com/api/dashboards/1860/revisions/latest/download \
  > docker/grafana/dashboards/json/node-exporter-full.json
```
Candidate dashboard IDs [CITED: grafana.com/grafana/dashboards/1860-node-exporter-full/ (confirmed
ID 1860, Prometheus datasource, job label `node`), WebSearch cross-referenced for the other two —
tag these two `[ASSUMED]`, verify the exact ID against grafana.com at plan time]:
- **node_exporter:** ID `1860` ("Node Exporter Full") — `[CITED]`, directly confirmed this session.
- **cAdvisor:** ID `14282` ("Cadvisor exporter") or `193` — multiple plausible candidates surfaced,
  not individually confirmed — `[ASSUMED]`.
- **postgres_exporter:** ID `12485` ("PostgreSQL Exporter") — surfaced by search, not directly
  fetched — `[ASSUMED]`.

### Pattern 2: Promtail Docker service discovery (no static per-container config)

**What:** `docker_sd_configs` polls the Docker daemon socket and auto-discovers every running
container as a scrape target — satisfies D-08's "ALL containers" requirement with one config
block, and (because `docker.sock` is host-wide, not Compose-project-scoped) discovers containers
in **both** `docker-compose.prod.yml` and `docker-compose.nonprod.yml` projects without Promtail
needing network membership in either project beyond reaching Loki.
**When to use:** Whenever the target set is "every container on this host," as D-08 specifies.
**Example:**
```yaml
# Source: github.com/grafana/loki (Context7 /grafana/loki) — discovery.docker pattern adapted
# from Alloy's own getting-started example to Promtail's docker_sd_configs equivalent;
# cross-checked against WebSearch community examples for the relabel_configs shape.
# docker/promtail/promtail-config.yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: docker
    docker_sd_configs:
      - host: unix:///var/run/docker.sock
        refresh_interval: 15s
        # No `filters:` block, deliberately — D-08 requires ALL containers, not a labeled subset.
    relabel_configs:
      - source_labels: ["__meta_docker_container_name"]
        regex: "/(.*)"
        target_label: "container"
      - source_labels: ["__meta_docker_container_label_com_docker_compose_project"]
        target_label: "compose_project"
    pipeline_stages:
      - docker: {}   # required explicitly since Promtail 2.2+ — a missing docker stage silently
                      # changes parsing behavior for Docker's json-file log format (Pitfall 5)
```

### Pattern 3: Prometheus static targets with an `env` label (D-04)

**What:** One Prometheus instance, `env: prod` / `env: nonprod` labels applied per-target in
`static_configs`, rather than two Prometheus instances.
**When to use:** Exactly D-04's stated topology.
**Example:**
```yaml
# docker/prometheus/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: node
    static_configs:
      - targets: ["node-exporter:9100"]
        labels: { env: prod }   # host-level; the VM itself is shared, "prod" is a naming choice —

  - job_name: cadvisor
    static_configs:
      - targets: ["cadvisor:8080"]
        labels: { env: prod }   # per-container labels (container_label_*) distinguish prod/nonprod
                                  # containers within cAdvisor's own metric labels regardless

  - job_name: postgres
    static_configs:
      - targets: ["postgres-exporter:9187"]
        labels: { env: shared }  # D-01: one shared Postgres instance serves both databases

  - job_name: redpanda
    metrics_path: /public_metrics
    static_configs:
      - targets: ["redpanda:9644"]
        labels: { env: prod }
      - targets: ["redpanda-nonprod:9644"]
        labels: { env: nonprod }
```
*(Source: shape assembled from [CITED: docs.redpanda.com/current/manage/monitoring/]'s
`scrape_configs` example plus standard Prometheus `static_configs`/`labels` syntax — `[ASSUMED]`
for the exact job/label naming, which is a design choice, not a fact to verify.)*

### Anti-Patterns to Avoid

- **Fetching Grafana dashboard JSON at container start via a `gnetId`/network call:** the file
  provisioning provider only reads a local path — a dashboard "provisioned by ID" still requires
  the JSON to exist on disk first. Don't build a custom fetch-on-boot script; commit the JSON.
- **Giving postgres_exporter the existing `kanban_prod_app`/`kanban_nonprod_app` role credentials:**
  those roles are scoped to application CRUD, not `pg_stat_*` read access, and reusing app
  credentials for a monitoring sidecar blurs a boundary Phase 11 deliberately drew (least-privilege
  roles per concern). Create a dedicated `monitoring` role (see Code Examples).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Container resource metrics | A custom `docker stats` polling script writing to a file Prometheus scrapes via `textfile_collector` | cAdvisor | cAdvisor already solves cgroup-v1/v2 accounting correctly across kernel versions (a genuinely fiddly, version-dependent parsing problem — see Pitfall 3) and exposes it in Prometheus format natively |
| Postgres internal metrics | Custom `pg_stat_*` polling via a cron+psql script | postgres_exporter | Handles connection pooling, per-database iteration, and the full `pg_stat_*` surface; a hand-rolled poller reinvents this with none of the community-maintained metric-naming conventions Grafana community dashboards expect |
| Log tailing/shipping | A custom `tail -f`-based log forwarder | Promtail (or its eventual Alloy replacement) | Docker's `json-file` driver rotation (this project's own `x-logging` anchor: `max-size: 10m`, `max-file: "3"`) means a naive `tail -f` loses data across rotation boundaries; Promtail's positions file + Docker SD handles this correctly |
| Dashboard-as-code | Hand-writing Grafana dashboard JSON from scratch | Import a community dashboard ID, customize from there | Grafana's dashboard JSON schema is large and undocumented in places; community dashboards for node_exporter/cAdvisor/postgres_exporter already encode the correct PromQL for each exporter's metric names |

**Key insight:** every "don't hand-roll" item here is solved by the exact tool D-05/D-06 already
name — this phase's own scope avoids the hand-roll trap by construction; the risk is scope creep
into a hand-rolled variant during planning, not an unclear standard solution.

## Common Pitfalls

### Pitfall 1: Prometheus cannot reach `redpanda-nonprod` without a new cross-project network

**What goes wrong:** `redpanda-nonprod` lives in `docker-compose.nonprod.yml`'s own project and is
explicitly documented as "Deliberately NOT on kanban-edge -- only app-nonprod may reach the shared
edge network" (docker-compose.nonprod.yml, `redpanda-nonprod` service comment). If Prometheus is
added to `docker-compose.prod.yml`, it shares zero network with `redpanda-nonprod` by default —
D-06's "Redpanda's own native metrics endpoint" requirement silently fails for the nonprod broker
only, and D-04's "one shared stack monitors BOTH" is violated for that one target.
**Why it happens:** Compose's implicit `default` network never crosses project boundaries; this
project already solved the identical problem twice (`kanban-edge` for Caddy→app-nonprod,
`kanban-db` for app-nonprod→postgres) but neither existing network is the right scope for a third,
different cross-project concern (Prometheus→redpanda-nonprod).
**How to avoid:** Create one new external network (e.g. `kanban-metrics`), created once via
`docker network create kanban-metrics`, joined by `prometheus` (in `docker-compose.prod.yml`) and
`redpanda-nonprod` (in `docker-compose.nonprod.yml`) — mirroring the exact `kanban-edge`/`kanban-db`
precedent of "one purpose-scoped external network per cross-project concern," not reusing either
existing network for a scope it wasn't designed for.
**Warning signs:** Prometheus's own `/targets` page shows the `redpanda-nonprod` target `DOWN` with
a connection-refused/DNS-resolution error, while every other target is `UP`.

### Pitfall 2: `/docker-entrypoint-initdb.d` will NOT run the new monitoring-role script

**What goes wrong:** The natural instinct is to add a second numbered script (e.g.
`02-create-monitoring-role.sh`) next to `01-create-databases-and-roles.sh` in
`docker/postgres-init/`. That directory only executes on a **first boot against an empty data
directory** — this project's own `postgres` container already has a populated `postgres-data`
volume from Phase 11, so the new script silently never runs, and `postgres_exporter` fails to
authenticate with no obvious link back to the missing init step.
**Why it happens:** Documented explicitly in `01-create-databases-and-roles.sh`'s own header
comment: "This script runs exactly once, only when `/var/lib/postgresql/data` is empty on first
container boot... A failed or partial first run leaves a non-empty data directory, and every later
`docker compose up` silently skips this script again."
**How to avoid:** Create the monitoring role via a one-off `docker exec ... psql` command against
the **live, already-running** `postgres` container (same class of operation as Phase 11's
Flyway-over-SSH one-off container pattern), not via a new init script. See Code Examples for the
exact SQL.
**Warning signs:** `postgres_exporter` container logs show `password authentication failed for
user "monitoring"` or `role "monitoring" does not exist` despite the init-script file being
present and seemingly correct in the repo.

### Pitfall 3: cAdvisor's exact mount set differs slightly across guides — get it wrong and cAdvisor either fails to start or reports empty/zeroed metrics

**What goes wrong:** Guides vary on `/var/run` being `:ro` vs `:rw`, and some omit
`/var/lib/docker` entirely. Docker Engine (not Kubernetes) hosts specifically need:
```yaml
volumes:
  - /:/rootfs:ro
  - /var/run:/var/run:ro       # D-05 requires read-only; upstream examples often show :rw here
  - /sys:/sys:ro
  - /var/lib/docker/:/var/lib/docker:ro
```
D-05 already commits to all mounts being read-only (a stricter posture than cAdvisor's own
upstream example, which mounts `/var/run:/var/run:rw` — [CITED: prometheus.io/docs/guides/cadvisor/,
github.com/google/cadvisor/blob/master/docs/running.md]) — this is a deliberate, already-accepted
deviation from upstream's default, not a new risk to flag, but the plan should not silently copy
an upstream example's `:rw` back in.
**Why it happens:** Most cAdvisor tutorials predate the project's own D-05-style hardening posture
and were written for convenience, not least-privilege.
**How to avoid:** Use the read-only mount set above exactly; if cAdvisor logs
`/var/run` permission errors under `:ro`, that is expected on some Docker daemon configurations
using `/var/run` for live socket files it needs to poll — treat as a real finding to verify against
this specific VM's Docker version, not assume-and-move-on.
**Warning signs:** cAdvisor starts and its `/metrics` endpoint responds, but per-container metric
series are empty or missing entirely for containers other than cAdvisor itself.

### Pitfall 4: cgroup v2 and `--privileged`/`--pid=host` are needed only for PROCESS-level metrics, not container CPU/mem/network

**What goes wrong:** Some cAdvisor guides recommend `--privileged=true` and `--pid=host`
unconditionally. D-05's scope is explicitly "per-container CPU/mem/network breakdown" — process-level
metrics (`container_processes`, per-PID `/proc/pid/fd` inspection) are NOT in scope and are the
specific feature that needs the broader privilege grant.
**Why it happens:** Guides conflate "run cAdvisor with every flag it might ever need" with "run
cAdvisor for the metrics this phase actually wants."
**How to avoid:** Do not add `--privileged` or `--pid=host` unless a specific in-scope metric is
later found missing without them — start with the read-only mount set from Pitfall 3 alone, which
is sufficient for CPU/mem/network/disk per D-05's stated scope [CITED:
github.com/google/cadvisor/issues (process-metrics-specific privilege requirement),
github.com/google/cadvisor/blob/master/docs/running.md].
**Warning signs:** N/A if not attempted — the failure mode this avoids is over-privileging a
container D-05 already scoped as a "known, accepted exception," not under-provisioning it.

### Pitfall 5: Promtail silently mis-parses Docker logs without the explicit `docker: {}` pipeline stage

**What goes wrong:** Docker's `json-file` log driver wraps each line in a JSON envelope
(`{"log": "...", "stream": "stdout", "time": "..."}`). Promtail versions since 2.2 require an
explicit `pipeline_stages: [{docker: {}}]` entry to unwrap this — omitting it was silently
tolerated pre-2.2 with different (wrong) default behavior.
**Why it happens:** Copy-pasted older Promtail configs from pre-2.2 tutorials omit this stage.
**How to avoid:** Always include `pipeline_stages: [{docker: {}}]` on the Docker-discovery job
(already in the Code Examples config above) [CITED: github.com/grafana/loki upgrade-2.x.md].
**Warning signs:** Loki entries show raw JSON envelopes as the log line content instead of the
actual application/Postgres/Redpanda log text.

### Pitfall 6: A `filters:` block on `docker_sd_configs` silently narrows D-08's "ALL containers" scope

**What goes wrong:** Nearly every community Promtail+Docker example includes
`filters: [{name: label, values: ["logging=promtail"]}]` to opt containers in one at a time. D-08
is explicit that Promtail should ship **every** named container's logs with no opt-in labeling
step — copying a filtered example verbatim silently drops every container that wasn't labeled.
**Why it happens:** Filtering is the more common real-world need (most deployments don't want
every container's logs), so it's the default shape in most tutorials found this session.
**How to avoid:** Omit the `filters:` key entirely (see Code Examples above) — `docker_sd_configs`
with no filter discovers every running container on the Docker daemon it's pointed at.
**Warning signs:** Loki has entries for `app`/`caddy` but is missing `postgres`/`redpanda` (or vice
versa) — check the Promtail config for an unintended `filters:` block first.

## Code Examples

### Postgres monitoring role (run live via `docker exec`, NOT via `/docker-entrypoint-initdb.d` — see Pitfall 2)

```bash
# Source: DATA_SOURCE_NAME/pg_monitor grant pattern per
# github.com/prometheus-community/postgres_exporter README [CITED], adapted to this project's
# REVOKE-CONNECT-FROM-PUBLIC posture established in docker/postgres-init/01-create-databases-and-roles.sh
docker exec -i postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_SUPERUSER" -d postgres <<'EOSQL'
CREATE ROLE monitoring WITH LOGIN PASSWORD :'monitoring_pass';
-- Both databases explicitly granted CONNECT: PUBLIC's default CONNECT grant was already
-- revoked per-database by the existing init script, so this is required, not defensive.
GRANT CONNECT ON DATABASE kanban_prod TO monitoring;
GRANT CONNECT ON DATABASE kanban_nonprod TO monitoring;
GRANT pg_monitor TO monitoring;
EOSQL
```
`postgres_exporter`'s `DATA_SOURCE_NAME`:
```
postgresql://monitoring:${MONITORING_PASS}@postgres:5432/kanban_prod?sslmode=disable
```
*(`[ASSUMED]` — this project's Postgres has no TLS listener configured (per `docker-compose.prod.yml`'s
own `app` service comment on `DB_JDBC_PARAMS` removal), so `sslmode=disable` matches the app's own
established connection posture, not a new deviation.)*

### Grafana admin credentials as a secret, not a default

```yaml
# docker-compose.prod.yml grafana service (sketch — not the final block; mem_limit TBD by ladder)
grafana:
  image: grafana/grafana:13.2.1
  environment:
    GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}   # from .env.prod, never a literal default
  # No `ports:` — Caddy's reverse_proxy is the only path in (mirrors every existing service)
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Promtail as Loki's primary log-shipping agent | Grafana Alloy | Promtail entered LTS 2025-02-13, reached full EOL 2026-03-02 [CITED: grafana.com/docs/loki/latest/send-data/promtail/, direct quote: "Promtail is end of life (EOL) as of March 2, 2026. Commercial support has ended. No future support or updates will be provided."] | Promtail images are still published (`3.6.11`, confirmed on Docker Hub this session) and functionally unchanged, but receive no further updates/security patches. D-01/D-08 name Promtail explicitly, so this phase proceeds with it as directed — but the planner/CONTEXT-owner should treat "we're deploying an EOL component" as a known, accepted trade-off, not an oversight. Alloy is the documented migration path if this is revisited later, and Grafana ships an automated Promtail→Alloy config converter. |
| `gcr.io/cadvisor/cadvisor` image path | `ghcr.io/google/cadvisor` | At v0.53.0+ | The phase description's example path is stale; use `ghcr.io/google/cadvisor:v0.60.5` for a new deployment. |
| Loki `chunk_store_config`/`table_manager`-based retention | `compactor.retention_enabled` + `limits_config.retention_period` | Loki 2.x+ | The config shape in Code Examples uses the current mechanism directly — no legacy retention config should be introduced. |

**Deprecated/outdated:**
- Promtail: functionally fine today, formally unsupported. See above.
- `gcr.io/cadvisor/cadvisor`: superseded registry path, still resolvable for old tags but not the
  source of new releases.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Grafana community dashboard ID for cAdvisor (`14282` or `193`) | Architecture Patterns, Pattern 1 | Low — wrong/missing dashboard ID just means re-searching grafana.com at plan/execution time; no functional or security impact |
| A2 | Grafana community dashboard ID for postgres_exporter (`12485`) | Architecture Patterns, Pattern 1 | Low — same as A1 |
| A3 | `sslmode=disable` is correct for postgres_exporter's DSN (no TLS listener on this Postgres) | Code Examples | Low — consistent with the app's own already-verified connection posture (docker-compose.prod.yml comment); would only be wrong if a TLS listener were added to Postgres between now and plan execution |
| A4 | Prometheus/Grafana/Loki/Promtail/cAdvisor/node_exporter/postgres_exporter starting `mem_limit` ladder values (not yet proposed numerically in this doc — see Environment Availability / Open Questions) | N/A — deliberately not guessed here | N/A — this project's own convention (Phase 11/Phase 8) is to measure via restart-ladder, not estimate; no numeric guess is offered as fact anywhere in this file |
| A5 | Job/label naming in the `prometheus.yml` example (`job_name: node`, `env: prod` etc.) | Architecture Patterns, Pattern 3 | Low — a naming/style choice, not a correctness claim; the planner can rename freely |

**If this table is empty:** N/A — see entries above; none are HIGH risk.

## Open Questions

1. **Grafana subdomain vs. path-based route on the existing `APP_DOMAIN` block**
   - What we know: DuckDNS already hosts two enumerated subdomains for this project
     (`kanban-board-rud-vlad-473.duckdns.org`, `...-nonprod.duckdns.org`) — no wildcard, per the
     Caddyfile's own comment ("a wildcard address here would scope this block to every other
     DuckDNS tenant"). A third subdomain (e.g. `...-grafana.duckdns.org`) follows the identical
     established pattern and gets Grafana its own Let's Encrypt cert on the same `caddy-data`
     volume with zero Grafana-side config changes. A path-based route (`/grafana/*` on the
     existing prod domain) avoids a new DNS record/cert but requires
     `GF_SERVER_ROOT_URL`/`GF_SERVER_SERVE_FROM_SUB_PATH=true` and community reports of asset-loading
     failures under sub-path proxying if any config line is ordered wrong [CITED:
     grafana.com/tutorials/run-grafana-behind-a-proxy/, community.grafana.com sub-path threads].
   - What's unclear: whether DuckDNS's free tier still permits a third subdomain for this account
     (not verified this session — no DuckDNS API/dashboard access from this environment).
   - Recommendation: default to a new subdomain (matches established pattern, avoids sub-path
     asset-loading class of bugs entirely) unless the DuckDNS account is confirmed to be at its
     subdomain limit, in which case fall back to the path-route with the two `GF_SERVER_*` env vars
     set and `serve_from_sub_path: true` verified against a real request before considering it done.

2. **Exact `mem_limit` starting points for the restart ladder**
   - What we know: this project's convention (Phase 8/Phase 11) is to start from a provisional
     "Iteration 0" value and descend via live restart cycles under real burst load until a failing
     rung is found, then adopt one rung above it with a stated headroom margin — never arithmetic.
   - What's unclear: this research did not run that live measurement (it requires the actual VM
     under real container startup, which is plan/execution-phase work, not research-phase work).
   - Recommendation: the planner should insert a `checkpoint` or dedicated measurement task per
     new service, seeded with a generous Iteration-0 ceiling (all seven of these tools are
     individually lightweight relative to `redpanda`'s 2200m or `app`'s 3g caps already on this
     box) and let the ladder find the real floor, exactly as Phase 8/11 did.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker Engine + Compose v2 | Every new service | ✓ (existing VM already runs `app`/`postgres`/`redpanda`/`caddy` this way) | Not re-verified this session (unchanged since Phase 11) | — |
| `docker.sock` read access | cAdvisor (D-05), Promtail (Pattern 2) | ✓ (same socket already implicitly available to the Docker daemon on this host) | — | — |
| 4.9 GiB RAM free of 7.8 GiB (measured 2026-09-07, per CONTEXT.md) | All seven new containers combined | ✓ at measurement time | — | If the restart-ladder measurement (Open Question 2) finds the combined floor exceeds available headroom, the fallback is to drop cAdvisor first (D-05 explicitly notes it's independently removable) before touching D-04's shared-stack topology (costly to reverse) |
| 222 GB disk free (measured 2026-09-07) | Prometheus TSDB + Loki chunks at 30d retention (D-07) | ✓ — comfortable margin for this project's traffic scale (a personal/portfolio app's own burst benchmark is ~55 mutating requests; metrics/log volume at this scale is not disk-constrained) | — | — |

**Missing dependencies with no fallback:** none identified.
**Missing dependencies with fallback:** none identified — this is a config/container-addition
phase with no new external service dependency beyond what's already running on the VM.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | Grafana's own built-in login (admin/password), per D-03 — no Caddy-layer gate added, an already-accepted, explicitly-reversible risk |
| V4 Access Control | yes | Same as above — anyone who obtains valid Grafana credentials sees ALL logs (including Postgres/Redpanda/Caddy, per D-08) and ALL metrics across both prod and nonprod (D-04); this is a materially larger blast radius than a compromised app-user account, worth naming explicitly to whoever owns D-03's login credential |
| V7 Error Handling and Logging | yes | This phase's entire purpose is centralizing logs — the standard control is: do not log secrets/credentials/session tokens into any container's stdout/stderr, since Promtail now ships ALL of it (D-08) into a UI reachable by anyone with Grafana login. This project's existing exception messages ("You do not have access to that board") were already designed not to leak PII; verify Postgres's own log output (if `log_statement` levels are ever raised for debugging, per Phase 11's own note that `log_statement=all` was used for one-off verification) is not left enabled in a way that ships bind-parameter values into centralized logs |
| V14 Configuration | yes | Grafana admin password, postgres_exporter's `DATA_SOURCE_NAME` (contains a Postgres password) must be `.env.prod`-sourced secrets, never literals in a committed compose file or config — same discipline as every existing credential in this project (see Code Examples) |
| V9 Communications | yes, unchanged posture | All new inter-container traffic (Prometheus→exporters, Grafana→Prometheus/Loki, Promtail→Loki) is internal-Docker-network-only, unencrypted — consistent with this project's existing internal posture (Postgres itself has no TLS listener) and not a new deviation this phase introduces |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| `docker.sock` mount grants effective host-root to any process that compromises the mounting container | Elevation of Privilege | Read-only mount (D-05 already commits to this for cAdvisor); Promtail's `docker_sd_configs` also needs `docker.sock` read access (Pattern 2) — mount it read-only there too, an addition this research surfaces that D-05's wording (which only names cAdvisor's mounts) doesn't explicitly cover |
| Centralized log store becomes a single high-value target containing every service's logs across both environments | Information Disclosure | D-03's accepted risk — Grafana's own auth is the only gate; document this explicitly so the credential owner treats the Grafana password with the same care as a production database password, not a throwaway internal-tool password |
| Default/weak Grafana admin credential | Information Disclosure, Elevation of Privilege | `GF_SECURITY_ADMIN_PASSWORD` from `.env.prod`, never the image's `admin/admin` default — see Code Examples |
| postgres_exporter's monitoring role over-provisioned (e.g. reusing an app role, or superuser) | Elevation of Privilege | Dedicated `monitoring` role with `pg_monitor` only, per Code Examples — never the existing app roles or `POSTGRES_SUPERUSER` |

## Sources

### Primary (HIGH confidence — Context7, official docs directly fetched)
- Context7 `/websites/grafana_grafana` — Grafana provisioning (datasources/dashboards YAML shape)
- Context7 `/grafana/loki` — Loki single-binary filesystem config, retention/compactor config,
  Promtail Docker pipeline-stage upgrade notes, Alloy `discovery.docker` pattern
- `docs.redpanda.com/current/manage/monitoring/` — `/public_metrics` vs `/metrics`, scrape config
- `github.com/prometheus-community/postgres_exporter` README — `DATA_SOURCE_NAME`, `pg_monitor`
- `github.com/google/cadvisor/blob/master/docs/running.md` — canonical `docker run` command
- `grafana.com/docs/loki/latest/send-data/promtail/` — Promtail EOL notice, quoted verbatim
- `grafana.com/grafana/dashboards/1860-node-exporter-full/` — dashboard ID 1860 confirmed directly
- Docker Hub / GHCR tags pages for all 7 images — version/tag existence confirmed directly

### Secondary (MEDIUM confidence — WebSearch cross-checked against a primary source)
- cAdvisor mount-set variants (`prometheus.io/docs/guides/cadvisor/` vs. `running.md`) —
  cross-checked, D-05's read-only posture is a deliberate deviation from both
- cAdvisor cgroup v2 / `--privileged` process-metrics requirement — GitHub issues, cross-referenced
  against `running.md`
- Grafana community dashboard IDs for cAdvisor/postgres_exporter (not individually re-fetched)

### Tertiary (LOW confidence — WebSearch only, flagged in Assumptions Log)
- Exact cAdvisor/postgres_exporter dashboard IDs (A1, A2)
- `mem_limit` starting points (deliberately not asserted — see Open Question 2)

## Metadata

**Confidence breakdown:**
- Standard stack (image tags): HIGH — every tag directly confirmed against Docker Hub/GHCR this
  session, cross-checked against GitHub releases
- Architecture/network topology: MEDIUM-HIGH — the cross-project network gap (Pitfall 1) is
  derived directly from reading this repo's own compose files, not external research; the specific
  new-network name/shape is a recommendation, not yet implemented or tested
- Config shapes (Prometheus/Loki/Promtail/Grafana): HIGH — sourced from Context7/official docs,
  standard and low-risk
- Pitfalls: MEDIUM-HIGH — Pitfalls 1 and 2 are grounded in this repo's own committed files (highest
  confidence); Pitfalls 3-6 are grounded in official docs/GitHub issues (CITED tier)
- Resource sizing: LOW by design — deliberately not guessed; flagged as an Open Question per this
  project's own established measurement discipline

**Research date:** 2026-09-07
**Valid until:** ~30 days for image tags/versions (fast-moving stack — Grafana/Loki ship monthly+);
config shapes and architectural findings (network topology, Postgres init-script behavior) are
stable and not time-sensitive.
