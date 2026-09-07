# Phase 12: Self-hosted observability stack - Context

**Gathered:** 2026-09-07
**Status:** Ready for planning

<domain>
## Phase Boundary

Metrics (host + per-container CPU/memory/disk, plus Postgres/Redpanda-internal) and log
aggregation are queryable without any paid SaaS — Prometheus + Grafana + Loki/Promtail +
cAdvisor + node_exporter + postgres_exporter, running as additional containers on the existing
Netcup VPS, sized against measured live headroom (4.9 GiB RAM free of 7.8 GiB, CPU load avg
0.11/4 vCPU, 222 GB disk free — measured 2026-09-07), with Grafana reachable only through Caddy
(not a raw published port).

**In scope:** metrics collection (host, container, Postgres, Redpanda) and log aggregation
across both production and nonprod, dashboards in Grafana, Caddy routing for the Grafana UI,
resource sizing for every new container.

**Out of scope:** alerting (Alertmanager, notification channels, on-call anything) — this
phase is visibility only. Automated backup of the observability stack's own data (Prometheus
TSDB, Loki chunks) — not treated as critical data; losing it means losing history, not losing
the app's actual data.

</domain>

<decisions>
## Implementation Decisions

### Scope & todos folded
- **D-01:** Fold `2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md` into
  this phase's scope — this is the todo the phase was originally scoped from. Loki/Promtail
  closes the log-shipping half; the todo's alerting half stays open (this phase explicitly
  excludes alerting).
- **D-02:** Fold `2026-09-03-caddy-service-has-no-mem-limit.md` into this phase — opportunistic,
  since this phase already edits the `caddy` service block in `docker-compose.prod.yml` to add
  a Grafana route. Add the missing `mem_limit` (measured, not guessed, per this project's
  established documentation discipline) while that file is already open.

### Grafana exposure
- **D-03:** Grafana sits behind Caddy's `reverse_proxy` with no additional Caddy-layer gate
  (no `basic_auth`, no IP allowlist) — relies on Grafana's own login (admin/password or OAuth)
  the same way the app's own endpoints rely on Spring Security alone rather than a Caddy-level
  gate. — **Reversibility:** reversible — a `basic_auth` or IP-allowlist directive can be added
  to the Grafana Caddy block later with no migration.

### Monitoring topology
- **D-04:** One shared Prometheus/Loki/Grafana stack monitors BOTH production and nonprod
  (containers distinguished by label, e.g. `env=prod` / `env=nonprod`) — not two separate
  stacks. Mirrors Phase 11's D-01 shared-Postgres-instance precedent (lowest resource cost;
  the VPS's per-project-quota failure mode that motivated *separate* Neon-era instances doesn't
  apply to a self-hosted stack). — **Reversibility:** costly — splitting later means duplicating
  Prometheus/Loki/Grafana and re-pointing every scrape config and Promtail target across two
  instances.

### Metrics coverage
- **D-05:** Include cAdvisor for per-container CPU/mem/network breakdown, with its
  `/var/run/docker.sock`, `/sys`, `/proc` mounts **read-only**. Accepted as a scoped, known
  exception rather than a general hardening regression — tracked separately from the existing
  open todo about the app container running as root (`2026-08-20-dockerfile-runs-as-root-no-user-directive.md`),
  which this phase does not touch. — **Reversibility:** reversible — cAdvisor can be removed
  as a standalone container/service without affecting node_exporter, Prometheus, or Grafana.
- **D-06:** Include `postgres_exporter` (new container/service) and Redpanda's own native
  `/metrics` Prometheus endpoint (already exposed, just needs a Prometheus scrape target added)
  in this phase — not deferred to a follow-up. Gets query/connection/replication-lag-style
  visibility into the two stateful services now, alongside host- and container-level metrics.
  — **Reversibility:** reversible — each is an independent scrape target/service.

### Retention & log scope
- **D-07:** 30 days of retention for both Prometheus (`--storage.tsdb.retention.time=30d`) and
  Loki, sized against the 222 GB disk headroom rather than Prometheus's 15-day default — chosen
  for month-over-month comparison capability. — **Reversibility:** reversible — a config value,
  changeable without data loss beyond whatever has already aged out under the old setting.
- **D-08:** Promtail ships logs from ALL containers (app, nonprod-app, postgres, redpanda,
  redpanda-nonprod, caddy) via Docker's `json-file` log driver — not app-containers-only. One
  scrape config, and covers exactly the infra-incident scenario (Postgres/Redpanda/Caddy logs
  during an outage) that motivated this phase in the first place. — **Reversibility:**
  reversible — a Promtail scrape-config change.

### Claude's Discretion
- Compose file topology (whether the new services live in `docker-compose.prod.yml` alongside
  the existing shared services, or a separate `docker-compose.monitoring.yml`) — an
  implementation detail for research/planning to resolve against this project's existing
  multi-compose-file conventions (`docker-compose.yml` / `.prod.yml` / `.nonprod.yml`).
- Dashboard provisioning mechanism (Grafana dashboards-as-code JSON vs. UI-created) and which
  specific community dashboards to import for node_exporter/cAdvisor/Postgres/Redpanda.
- Exact container/service naming, network placement (which of `kanban-edge`/`kanban-db`/a new
  network each new service joins), and `mem_limit` values — sized via the same live
  restart-ladder measurement method Phase 11 used (D-08/D-10 there), not arithmetic guesses.

### Folded Todos
- **`2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md`** — "No remote log
  shipping, no structured/UTC logging standard, no alerting on unusual activity." This phase
  closes the log-shipping half via Loki/Promtail (D-01, D-08). The structured/UTC logging
  standard and alerting halves remain open — alerting is explicitly out of scope (see
  `<domain>`); structured/UTC logging format is a separate application-level change this phase
  does not touch.
- **`2026-09-03-caddy-service-has-no-mem-limit.md`** — "The caddy service has no mem_limit, and
  its memory is now attacker-influenced." Folded opportunistically (D-02) since this phase
  already edits that service block.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Established self-hosting pattern (mirror this)
- `docker-compose.prod.yml` — the `redpanda` and `postgres` service blocks (resource-capped,
  named volume, healthcheck, `x-logging` anchor, no `ports:` entry) are the pattern every new
  observability service should follow. `caddy` service block (lines ~66-109) is what D-02/D-03
  edit.
- `Caddyfile` — existing `rate_limit` directive structure and its extensive dated decision
  comments (2026-09-03) are the documentation-discipline bar the new Grafana route should match.
  Two existing site blocks (`{$APP_DOMAIN}`, `{$APP_DOMAIN_NONPROD}`) — the Grafana route likely
  needs its own subdomain/site block or a path on the prod block; left to planning.
- `docs/INFRA_RUNBOOK.md` — "Nonprod resource measurement" / "Manual deploy... Task 3" sections
  document the restart-ladder memory-measurement methodology every `mem_limit` on this VPS
  follows; new services' caps should be measured the same way, not guessed.
- `.planning/phases/11-migrate-database-from-neon-to-self-hosted-postgres/11-CONTEXT.md` — prior
  phase's decisions (D-01 shared-instance precedent behind this phase's D-04; D-03 no-published-port
  discipline behind D-03/D-05 here; D-08 measurement discipline).

### Todos this phase folds or is adjacent to
- `.planning/todos/pending/2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md`
  (folded, D-01)
- `.planning/todos/pending/2026-09-03-caddy-service-has-no-mem-limit.md` (folded, D-02)
- `.planning/todos/pending/2026-08-20-dockerfile-runs-as-root-no-user-directive.md` — NOT
  touched by this phase; referenced only because D-05's cAdvisor mounts are a related-but-
  distinct concern, noted so a future reader doesn't conflate the two.

### Project-level context
- `.planning/PROJECT.md` — "Epics 4, 6, 7 (Redis, Observability, Kubernetes) — deferred to
  future milestones" — this phase is the first pass at the deferred Observability epic, scoped
  to metrics+logs rather than the full original epic.
- `.planning/ROADMAP.md` Phase 12 entry — Goal statement this CONTEXT.md elaborates on.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `docker-compose.prod.yml`'s `x-logging: &default-logging` anchor — every new service should
  reuse this rather than defining its own logging block.
- `redpanda` service block's dated, measurement-backed `mem_limit` comment style — the
  documentation discipline (not just the mechanism) to replicate for every new container's cap.

### Established Patterns
- No `ports:` entry on any internal-only service (D-03 from Phase 11, reaffirmed here for
  Grafana/Prometheus/Loki/cAdvisor/node_exporter/postgres_exporter — none should publish a host
  port; only Caddy's `reverse_proxy` reaches Grafana).
- Named Docker volumes for anything with state to persist across container recreation
  (`redpanda-data`, `caddy-data`, `caddy-config`, `postgres-data` already exist) — Prometheus's
  TSDB and Loki's chunk store need the same treatment.
- Networks: `kanban-edge` (Caddy-facing, public-adjacent) and `kanban-db` (internal, Postgres
  access) already exist and partition by exposure level — new services should be placed
  deliberately rather than defaulting to `default`.

### Integration Points
- `caddy` service's Caddyfile — D-03's Grafana route and D-02's `mem_limit` addition both land
  here.
- Nonprod containers (`kanban-nonprod-app`, `kanban-nonprod-redpanda`) run under a separate
  Compose project from `docker-compose.prod.yml` but share the host and the `postgres` container
  — D-04's shared-stack scraping needs to reach both; exact network wiring is left to planning
  (Claude's Discretion above).

</code_context>

<specifics>
## Specific Ideas

No UI/visual specifics beyond "reachable through Caddy, not a raw port" and "Grafana's own
login is enough" (D-03). The concrete reference point grounding most decisions here was Phase
11's self-hosting precedent (shared instance over duplicated stacks, no published ports,
measurement-backed resource caps) and the live headroom numbers measured on 2026-09-07
(4.9 GiB RAM free, CPU idle, 222 GB disk free) that made "just add more containers" an easy call
rather than one requiring a tighter budget negotiation.

</specifics>

<deferred>
## Deferred Ideas

- Alerting (Alertmanager, notification channels) — explicitly out of scope for this phase (see
  `<domain>`); the natural next phase once metrics/logs exist to alert on.
- Structured/UTC logging format standard across the application's own log statements — the
  other open half of the folded log-shipping todo; an application-level change, not infra.
- Container-hardening (non-root `USER` directive in the Dockerfile) — pre-existing, unrelated
  todo; not touched by this phase even though cAdvisor's host-level mounts (D-05) sit in
  similar territory.

### Reviewed Todos (not folded)
None reviewed and explicitly declined — the todo-matcher's other ~35 hits were generic
date/keyword noise (e.g. "milestone", "2026") rather than topical matches to observability, and
weren't presented as real candidates.

</deferred>

---

*Phase: 12-self-hosted-observability-stack*
*Context gathered: 2026-09-07*
