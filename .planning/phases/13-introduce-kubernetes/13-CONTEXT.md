# Phase 13: Introduce Kubernetes - Context

**Gathered:** 2026-09-25
**Status:** Ready for planning

<domain>
## Phase Boundary

Both environments (nonprod, then production) run on a single-node **k3s** cluster on the existing
Netcup VPS instead of Docker Compose, deployed by **Flux** GitOps, fronted by **Traefik +
cert-manager**, and observed by **kube-prometheus-stack + Loki**. Compose is fully removed at the
end of the phase once a strict verification gate passes.

This deliberately **overrides** `docs/plans/backend-modernization/07-kubernetes-stretch.md`
(Epic 7), which scopes Kubernetes as "local only… do not attempt a production migration". The
operator chose a real production cutover (D-01). A planner reading Epic 7 must NOT scale this
phase back down to a local `kind` demo.

**In scope:** k3s install; Kustomize manifests for app/Postgres/Redpanda; Flux + image
automation; Traefik Ingress + cert-manager + rate limiting; kube-prometheus-stack + Loki; data
migration of production Postgres; nonprod cutover, then prod cutover; Compose decommission; CI
changes (manifest validation, sortable image tags, removal of Compose/SSH deploy jobs); docs and
architecture diagrams.

**Out of scope:** automated backups of the new volumes; alerting; multi-node/HA; promotion gating
between environments.

**Measured baseline (2026-09-25, read-only over `ssh netcup-prod`):**
- VPS: 7.8 GiB RAM, **4.9 GiB available, no swap**, 4 vCPU (load avg ~1.3), 210 GB disk free.
- 13 containers using ~2.9 GiB; their `mem_limit`s already sum to ~9.1 GiB (overcommitted on caps).
- `kanban_prod`: 38 users, 33 boards, 249 columns, 984 tasks, 983 subtasks, 2,261 activity_log
  rows, 9.5 MB. `kanban_nonprod`: 20 MB.
- Redpanda prod: `kanban.activity` log-start = high-watermark = 2335 (0 retained messages),
  `kanban.activity.dlt` empty, `activity-log` group lag 0. Only `_schemas` holds durable state.
- Image tags today: 7-char commit SHA (`${GITHUB_SHA::7}`) — not orderable.

**Size warning:** this scope is estimated at 12–16 plans, roughly double Phases 11/12. The
operator was offered a split at the D-02 seam (nonprod half / prod half) and chose to keep it as
one phase. Plan waves should still follow that seam so the nonprod half is complete and verified
before any production-touching plan starts.

</domain>

<decisions>
## Implementation Decisions

### Cluster location & rollout
- **D-01:** Full production cutover from Docker Compose to k3s on the Netcup VPS — not a local
  `kind` demo, not nonprod-only. — **Reversibility:** one-way — combined with D-04, the Compose
  runtime and its volumes are deleted; returning means rebuilding the Compose stack from git
  history and restoring data from a dump.
- **D-02:** Nonprod moves first and must complete at least one full deploy cycle on k3s before
  production moves. Production uses the same manifests with a different overlay. Both happen in
  this phase. During the interim, nonprod is on k3s while prod remains on Compose.
- **D-03:** Production cutover uses a short, announced maintenance window. Only one prod stack
  is in memory at a time — no side-by-side Compose+k3s overlap (no swap; overlap would put ~3.9
  of 4.9 GiB at risk and could OOM the live stack).
- **D-04:** Compose is decommissioned as soon as k3s prod passes the D-08 gate — compose files,
  Compose-specific deploy.yml jobs, and Docker volumes removed. No multi-day rollback window;
  the operator declined the recommended 7-day window. — **Reversibility:** one-way — Docker
  volumes (`postgres-data`, `redpanda-data`, `caddy-data`, observability volumes) are deleted.
  Because there is no fallback, **the D-08 gate is the only safety margin — the planner must not
  weaken it.**

### Workloads
- **D-05:** Postgres 16 runs as an in-cluster StatefulSet on k3s's `local-path` provisioner.
  Both databases are migrated with `pg_dump`/`pg_restore` during the D-03 window. The
  least-privilege roles (Phase 11 D-01: the nonprod role cannot connect to the prod DB) are
  recreated from `docker/postgres-init/01-create-databases-and-roles.sh` **before** the restore.
  Row counts are snapshotted after the Compose app stops, for D-08 parity. — **Reversibility:**
  costly — moving data out again is another dump/restore migration.
- **D-06:** Redpanda runs as a fresh single-replica StatefulSet per environment (in each app
  namespace, per D-11) with empty topics. Schemas are re-registered (see D-14 for the
  mechanism). The cutover runbook MUST order: stop the app → confirm `activity-log` lag 0 and
  empty DLT → stop the old broker. No topic data migration (nothing durable to migrate).
- **D-07:** Observability moves to the **`kube-prometheus-stack`** Helm chart (Prometheus
  Operator, ServiceMonitors, chart-provided Grafana). Planner requirements that come with it:
  - Loki is not in that chart — add it via its own chart plus a log collector. Prefer **Grafana
    Alloy** over Promtail (Promtail is EOL upstream) unless research finds a blocker.
  - The standalone cAdvisor, node-exporter and postgres-exporter containers are replaced by the
    chart's/kubelet's equivalents or a chart-managed exporter; Phase 12's cAdvisor
    `docker.sock` mount exception goes away.
  - **The two public dashboard links in `README.md` must keep working**
    (`/public-dashboards/7db62007…`, `/public-dashboards/5a72e5df…`). Their access tokens live
    in Grafana's SQLite DB, not in provisioning JSON — either carry `grafana.db` into the chart's
    Grafana volume, or recreate the shares and update the README in the same change. Datasource
    UID must stay a literal value and public dashboards must have no template variables (see
    project memory on Grafana public dashboards). Verify with `scripts/verify-public-dashboards.py`.
  - Dashboard queries must be rewritten from Docker/cAdvisor labels to Kubernetes labels
    (`namespace`, `pod`); an unrewritten panel renders "No data" silently.
  - Every component's `resources.limits` is measured with the restart-ladder method, not
    assumed from chart defaults.
  Prometheus TSDB and Loki history start fresh (Phase 12 classified them non-critical).
- **D-08:** Compose/volume deletion (D-04) is gated on ALL of:
  1. Automated checks green: public health endpoints on both domains, `uptime-check` and
     `verify-rate-limit` workflows, `verify-public-dashboards.py`, and a full GitOps deploy (push
     to `main` → Flux reconciles the new revision → new pod Ready).
  2. Row-count parity for `users`, `boards`, `columns`, `tasks`, `subtasks`, `activity_log`
     between the pre-cutover snapshot and the in-cluster Postgres.
  3. 24 hours with zero `OOMKilled` and zero restarts across all pods.
  4. The cutover `pg_dump` file stays on the VPS disk (outside any volume) until 1–3 pass.
  Compose stays **stopped** during the 24h — this is verification, not a parallel rollback path.

### Manifests
- **D-09:** Own workloads (app, Postgres, Redpanda) use Kustomize: `k8s/base/` plus
  `k8s/overlays/{nonprod,prod}`, applied with `kubectl apply -k` semantics (Flux
  `Kustomization`). Third-party charts (kube-prometheus-stack, Loki, cert-manager if charted) are
  declared as k3s `HelmChart` resources or Flux `HelmRelease` — whichever fits the D-14 Flux
  setup; no `helm` binary in CI.
- **D-10:** Secrets are plain Kubernetes Secrets created on the VPS from the existing env files
  (`kubectl create secret generic … --from-env-file`), never committed to git and never handled
  by CI. k3s starts with `--secrets-encryption`. Credential rotation remains a manual SSH step.
- **D-11:** Namespaces `kanban-prod`, `kanban-nonprod`, `kanban-data`, `monitoring`. One shared
  Postgres in `kanban-data` (keeps Phase 11 D-01). A NetworkPolicy allows port 5432 only from the
  two app namespaces and the Postgres exporter. Each environment's Redpanda lives in its app
  namespace. — **Reversibility:** costly — namespaces are baked into every overlay, DNS name and
  NetworkPolicy.
- **D-12:** A pre-merge CI job renders both overlays (`kubectl kustomize
  k8s/overlays/{nonprod,prod}`) and validates them with **kubeconform** pinned to the Kubernetes
  version of the pinned k3s release. CRD kinds (`HelmChart`/`HelmRelease`, `ServiceMonitor`,
  Flux kinds, cert-manager kinds) are handled by supplying schemas or explicit skips — research
  decides which. The k3s version itself is pinned, never `latest`.

### Deploy pipeline & edge
- **D-13:** Traefik (k3s default) is the Ingress controller, with cert-manager issuing Let's
  Encrypt certificates. The Caddy edge is removed completely: `docker/caddy/`, the custom
  `kanban-board-caddy` image, the `build-and-push-caddy-image` job, `Caddyfile`, and
  `scripts/verify-caddy-image-tag.py`. Planner requirements:
  - Rate limiting keeps its current scope: `/api/signin` and `/api/signup` on the **prod
    hostname only**, as a Traefik `RateLimit` middleware. `verify-rate-limit`'s thresholds are
    re-derived from Traefik's average/burst semantics, not copied from Caddy.
  - **Client source IP must reach Traefik.** k3s ServiceLB can hide the real client address
    unless `externalTrafficPolicy: Local` (or equivalent) is set; otherwise all clients share one
    rate-limit bucket. Research must confirm the mechanism; verification must prove per-client
    limiting with two distinct source IPs.
  - cert-manager uses the Let's Encrypt **staging** issuer first; switch to production issuance
    only after all three hostnames (`APP_DOMAIN`, `APP_DOMAIN_NONPROD`, `APP_DOMAIN_MONITORING`)
    validate.
  — **Reversibility:** costly — the custom Caddy image, its CI job and its rate-limit tuning are
  deleted; bringing Caddy back means rebuilding all three.
- **D-14:** Deploys are pull-based GitOps; the SSH `docker compose`/`kubectl` deploy path is
  retired. `register-schemas-production` (currently a CI job) becomes an in-cluster Kubernetes
  `Job` ordered before the app rollout (D-06 needs schemas before the app starts).
  `flyway-verify`/`flyway-verify-nonprod` stay in CI as pre-merge checks (their Phase 11 D-13
  SSH-tunnel target changes from the Compose network to the in-cluster Postgres).
- **D-15:** GitOps controller is **Flux** with image-reflector and image-automation
  controllers. Flux polls Docker Hub and commits tag bumps itself, using a write deploy key
  scoped to this single repo and stored as a cluster Secret. CI gains no new credentials — its
  deploy responsibility ends at pushing images. If `main` has branch protection, the Flux key
  needs a narrow bypass. **Hard requirement:** image tags must become sortable (e.g.
  `main-<run_number>-<sha7>`) so an `ImagePolicy` can select the newest; the current bare-SHA tag
  cannot be ordered. — **Reversibility:** costly — the tag scheme, the deploy key and the Flux
  bootstrap are all load-bearing for every deploy.
- **D-16:** Both environments track every `main` image independently — one `ImagePolicy` per
  Docker Hub repo (`kanban-board-backend`, `kanban-board-backend-nonprod`). No promotion gate,
  matching today's behaviour where `deploy-to-netcup` never waited on nonprod health.

### Claude's Discretion
- k3s install method and exact pinned version (and whether Flux or k3s's helm-controller owns
  third-party charts, per D-09).
- Exact `resources.requests`/`limits` for every workload — measured via the restart-ladder method
  documented in `docs/INFRA_RUNBOOK.md`, never guessed.
- Log collector choice (Alloy preferred per D-07) and its configuration.
- kubeconform CRD-schema handling (D-12).
- How Docker Engine and k3s coexist on the host during the D-02 interim (iptables `FORWARD`
  rules, and how the still-Compose prod stack and k3s nonprod pods reach the shared Postgres
  before D-05 moves it). Research must resolve this before the nonprod cutover — it is the
  highest-risk plumbing in the phase.
- Fate of Compose-specific invariant scripts (`verify-compose-ports.py`,
  `verify-deploy-scp-coverage.py`, `verify-postgres-memory-invariant.py` and selftests) — delete,
  or port the invariant they protect to the manifests (e.g. "no NodePort/hostPort except
  Traefik" replaces "no published ports").

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Scope origin (overridden — read to understand what NOT to do)
- `docs/plans/backend-modernization/07-kubernetes-stretch.md` — Epic 7's local-only scope; D-01
  overrides it deliberately. Its "explain get pods / describe / Service vs Deployment / rolling
  update" depth bar still applies to documentation.

### Prior phase decisions this phase builds on
- `.planning/phases/11-migrate-database-from-neon-to-self-hosted-postgres/11-CONTEXT.md` — D-01
  shared Postgres + least-privilege roles (kept by D-11), D-03 no published ports, D-08
  restart-ladder measurement, D-12 no automated backups, D-13 CI Flyway verify over SSH.
- `.planning/phases/12-self-hosted-observability-stack/12-CONTEXT.md` — D-03 Grafana behind the
  edge with its own login, D-04 one shared observability stack, D-05 cAdvisor exception (removed
  here), D-07 30d retention, D-08 all-container log shipping.

### Current runtime being replaced
- `docker-compose.prod.yml` — every prod service, its measured `mem_limit` and dated rationale,
  networks `kanban-edge`/`kanban-db`/`kanban-metrics`.
- `docker-compose.nonprod.yml` — `redpanda-nonprod`, `app-nonprod`.
- `Caddyfile` — three site blocks and the rate-limit directive whose scope D-13 must preserve.
- `docker/postgres-init/01-create-databases-and-roles.sh` — role/database creation reused by D-05.
- `docker/grafana/provisioning/` — datasource UID and dashboard JSON feeding D-07.
- `docker/prometheus/`, `docker/loki/`, `docker/promtail/` — current scrape/log configs.

### CI/CD
- `.github/workflows/deploy.yml` — jobs `build-and-push-docker-image` (tag scheme, D-15),
  `build-and-push-caddy-image` (removed, D-13), `flyway-verify*` (kept, D-14),
  `deploy-to-netcup`/`deploy-to-nonprod`/`health-check-nonprod`/`cleanup-*` (replaced by Flux),
  `register-schemas-production` (becomes in-cluster Job, D-14).
- `.github/workflows/invariant-checks.yml`, `verify-rate-limit.yml`, `uptime-check.yml` — gates
  that D-08 relies on or that must be ported.
- `scripts/verify-public-dashboards.py` — D-07/D-08 public-link check.

### Operations
- `docs/INFRA_RUNBOOK.md` — restart-ladder methodology, SSH access (`netcup-prod`), manual
  restore procedure; must gain the cutover runbook and k3s operations.
- `README.md` — architecture diagram and public dashboard links; must be updated in the same
  change (project memory: infra diagrams went stale for 4 months after the Neon cutover).
- `docs/DIAGRAM_CONVENTIONS.md` — 4+1 view conventions for the updated deployment diagram.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `docker/postgres-init/01-create-databases-and-roles.sh` — role/DB creation, reusable as an
  init script or a pre-restore Job.
- `docker/grafana/provisioning/dashboards/json/` — committed dashboards, the starting point for
  D-07's label rewrite.
- `scripts/verify-public-dashboards.py` (+ selftest) — directly usable as a D-08 gate.
- `register-schemas-production` job's script body — the payload for D-14's in-cluster Job.

### Established Patterns
- Every memory cap is measured and carries a dated rationale comment — carry that discipline
  into `resources.limits` in the overlays.
- No internal service publishes a host port; only the edge is public. In k3s terms: only
  Traefik binds 80/443; everything else is ClusterIP.
- Invariants are enforced by small Python scripts with selftests wired into
  `invariant-checks.yml`, not by prose.
- Digest-pinned `appleboy/*` actions hold the SSH key; with D-14/D-15 the SSH deploy jobs go
  away, but the SSH key is still used by `flyway-verify*`.

### Integration Points
- App datasource URL changes to cross-namespace DNS (`postgres.kanban-data.svc…`) in both
  overlays; Kafka bootstrap and schema registry URLs to the per-namespace Redpanda Service.
- Nonprod admin reset endpoint (TRUNCATE) must keep working against the in-cluster database.
- Docker Hub repos `kanban-board-backend` and `kanban-board-backend-nonprod` become the Flux
  image-reflector sources.

</code_context>

<specifics>
## Specific Ideas

- The operator consistently picked the more ambitious or more industry-standard option over the
  recommended minimal one (full prod cutover, kube-prometheus-stack, Traefik + cert-manager,
  GitOps with Flux) while accepting the recommended safety guards (nonprod first, maintenance
  window, strict deletion gate, sortable tags, no new CI credentials). Plans should honour both:
  real, recognisable Kubernetes tooling, with every irreversible step gated by measurement.
- Memory is the binding constraint: 4.9 GiB available, no swap. k3s itself (~0.5–1 GiB),
  kube-prometheus-stack (~1–1.5 GiB), Flux (~150–200 MiB) and cert-manager (~100 MiB) are all
  new; research should produce a memory budget before the first install, and the nonprod half
  should confirm it on the real box before prod moves.

</specifics>

<deferred>
## Deferred Ideas

- **Automated backups** of the `local-path` volumes (Postgres especially) — Phase 11 D-12's gap
  persists; a natural follow-up now that a CronJob is cheap to add.
- **Alerting** (Alertmanager ships inside kube-prometheus-stack but stays unconfigured) —
  Phase 12 excluded it; still out of scope.
- **Promotion gating** nonprod → prod (D-16 declined it).
- **Splitting this phase** at the D-02 seam — offered, declined; kept here in case planning shows
  the plan count is unmanageable.

### Reviewed Todos (not folded)
- `2026-08-12-add-nonprod-staging-environment-and-playwright-e2e-ci-gate.md`,
  `2026-09-07-rotate-grafana-viewer-password-leaked-in-session.md`,
  `2026-09-08-cadvisor-grafana-and-caddy-mem-limits-need-a-longer-observation-window-re-ladder.md`,
  `2026-09-08-grafana-admin-password-drift-from-env-prod.md` — matched only on generic keywords.
  Note for the planner: the cadvisor/caddy mem-limit re-ladder todo becomes moot once D-07/D-13
  remove those containers, and the Grafana password todos should be resolved when D-10 creates
  the Grafana Secret from env files — close them if the implementation does so.

</deferred>

---

*Phase: 13-introduce-kubernetes*
*Context gathered: 2026-09-25*
