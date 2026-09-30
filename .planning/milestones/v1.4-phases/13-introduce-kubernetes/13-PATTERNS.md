# Phase 13: Introduce Kubernetes - Pattern Map

**Mapped:** 2026-09-25
**Files analyzed:** ~28 (grouped by shape — k8s manifests are per-workload/per-kind, not 1:1 with
existing files)
**Analogs found:** all groups have a same-repo analog except pure-new-tooling manifests
(Flux/cert-manager/kube-prometheus-stack CRDs), which have no in-repo precedent and fall back to
RESEARCH.md's Code Examples.

This is an infra migration phase: there is no controller/service/DTO shape to match. "Role" below
is repurposed as the manifest/script/doc category; "Data flow" as its lifecycle behavior
(declarative-desired-state, one-shot, gate/check, reference doc).

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `k8s/base/app/{deployment,service,configmap}.yaml` | workload manifest | declarative-desired-state | `docker-compose.prod.yml` `app:` service block (lines ~328-405) | role-match (Compose service → K8s workload, same env/resource-cap fields) |
| `k8s/base/postgres/{statefulset,service,pvc,init-configmap}.yaml` | stateful workload manifest | declarative-desired-state | `docker-compose.prod.yml` `postgres:` block (lines 155-328) + `docker/postgres-init/01-create-databases-and-roles.sh` | role-match |
| `k8s/base/redpanda/{statefulset,service}.yaml` | stateful workload manifest | declarative-desired-state | `docker-compose.prod.yml` `redpanda:` block (405-498) and `docker-compose.nonprod.yml` `redpanda-nonprod:` (lines ~80-151) | role-match — carry `--overprovisioned --smp 1 --memory 2G`/`512M` flags literally (Anti-Pattern in RESEARCH.md) |
| `k8s/base/schema-registration/` (initContainer patch, per Pattern 3) | one-shot init | event-driven, ordered-before-app | `.github/workflows/deploy.yml` `register-schemas-production` job (~lines 547-591) | role-match — port the job's script body into an initContainer, not a Job (Job immutability pitfall) |
| `k8s/platform/networkpolicy-postgres.yaml` | access-control manifest | declarative-desired-state | `docker-compose.prod.yml` external network declarations (`kanban-edge`/`kanban-db`/`kanban-metrics`, lines 63-73) + their per-service `networks:` scoping | role-match — same "who may reach whom" intent, K8s-native mechanism |
| `k8s/platform/helmchartconfig-traefik.yaml`, `k8s/platform/middleware-rate-limit.yaml` | edge/ingress manifest | declarative-desired-state | `Caddyfile` (rate-limit `zone auth` directive + three site blocks) | role-match — see Pattern 2 in RESEARCH.md for the token-bucket translation |
| `k8s/platform/clusterissuer-{staging,production}.yaml` | cert manifest | declarative-desired-state | none in-repo (Caddy did ACME implicitly) | no analog — use RESEARCH.md Code Examples (cert-manager section) |
| `k8s/flux-system/{gitrepository,kustomization,imagerepository,imagepolicy,imageupdateautomation}.yaml` | GitOps manifest | declarative-desired-state, pub-sub (polls Docker Hub) | none in-repo | no analog — use RESEARCH.md Code Examples (Flux bootstrap, ImagePolicy) |
| `k8s/platform/helmrelease-{kube-prometheus-stack,loki,alloy}.yaml` | third-party chart manifest | declarative-desired-state | `docker/prometheus/`, `docker/loki/`, `docker/promtail/` configs + `docker-compose.prod.yml` `prometheus:`/`grafana:`/`loki:`/`promtail:`/`cadvisor:` blocks (lines 583-886) | role-match — same measured mem caps carry into `resources.limits`; label rewrite per Pitfall 7 |
| `.github/workflows/invariant-checks.yml` (new `kubeconform` job) | CI gate job | request-response, pure-function-of-commit | existing jobs in same file: `compose-published-ports` (lines ~98-113) — selftest-then-gate two-step pattern | exact — copy the "install dep → run selftest → run gate" 3-step shape |
| `.github/workflows/deploy.yml` (edit: tag scheme, remove Compose/Caddy/SSH deploy jobs) | CI workflow | request-response | same file, `build-and-push-docker-image` job (lines 97-170) for the tag-scheme edit; `deploy-to-netcup`/`deploy-to-nonprod`/`cleanup-*` jobs (394-923) to be deleted | exact (same file) |
| `.github/workflows/verify-rate-limit.yml` (edit: thresholds re-derived for Traefik) | CI workflow, manual trigger | request-response | same file (whole file is the analog for structure; only the assertion targets change) | exact (same file) |
| `scripts/verify-k8s-manifests.py` or CI-inline kubeconform invocation | invariant script | batch, pure-function-of-commit | `scripts/verify-compose-ports.py` (+ `scripts/verify-compose-ports-selftest.py`) | exact — same docstring shape: WHY/SCOPE/KNOWN HOLES/numbered invariants + a paired selftest |
| `scripts/verify-cutover-row-counts.sql` or `.py` | one-shot cutover check | batch | `scripts/verify-postgres-memory-invariant.py` (measurement-based gate) | role-match |
| `docs/INFRA_RUNBOOK.md` (append: k3s ops, cutover runbook, restart-ladder for new workloads) | ops doc | reference | same file's existing dated, falsifiable sections (e.g. "Redpanda resource caps, measured" — see excerpt below) | exact (same file) |
| `README.md` (edit: architecture diagram, public dashboard links) | doc | reference | same file's existing "Verified state"/public-dashboard-link sections | exact (same file) |
| Deleted: `docker-compose.prod.yml`, `docker-compose.nonprod.yml`, `Caddyfile`, `docker/caddy/`, `scripts/verify-caddy-image-tag.py`, `scripts/verify-compose-ports.py(+selftest)`, `scripts/verify-deploy-scp-coverage.py(+selftest)` | removal | — | — | N/A — deletion, not pattern-following; `verify-postgres-memory-invariant.py`'s invariant may be *ported* to a k8s-resources check per CONTEXT.md's "Claude's Discretion" |

## Pattern Assignments

### `k8s/base/app/deployment.yaml` + `service.yaml` (workload manifest)

**Analog:** `docker-compose.prod.yml` `app:` service (lines 328-405), `docker-compose.nonprod.yml` `app-nonprod:` service

**Env var / config pattern to carry over** — every DB/Kafka/schema-registry URL is currently
Compose-DNS-shaped (`postgres`, `redpanda:19092`) and must become K8s-DNS-shaped
(`postgres.kanban-data.svc.cluster.local`, `redpanda-0.redpanda.kanban-prod.svc.cluster.local:19092`
per Pitfall 4). Read the full current env block before rewriting — do not guess variable names.

**Memory cap discipline (mandatory, per CONTEXT.md "Established Patterns"):** every `mem_limit` in
Compose carries a dated, falsifiable rationale comment measured via the restart-ladder method. The
`redpanda:` block excerpt below is the canonical example of this comment shape — `resources.requests`/
`resources.limits` in the new manifests must carry an equivalent comment, not a bare number:

```yaml
# docker-compose.prod.yml lines 428-464 (redpanda mem_limit + --smp/--memory flags, measured basis)
mem_limit: 2200m
command:
  - redpanda
  - start
  - --overprovisioned
  - --smp
  - "1"
  - --memory
  - 2G
# MEASURED BASIS (05-04 Task 3, 2026-08-16) -- Netcup VPS Lite 2 G12s, 4 vCPU / 7.8GiB RAM.
# Idle baseline: redpanda 347.8MiB RSS / 0.43% CPU (of 2G internal cap, ~17%)...
# [full ladder + conclusion — see docs/INFRA_RUNBOOK.md "Redpanda resource measurement"]
```

**Do not re-derive** `--smp`/`--memory` from the official Redpanda Helm chart's resource-based
auto-calc (RESEARCH.md Anti-Patterns) — copy `--overprovisioned --smp 1 --memory 2G` (prod) /
`512M` (nonprod, verify exact nonprod value in `docker-compose.nonprod.yml` lines 87-103) literally
into the StatefulSet's container args.

---

### `k8s/base/postgres/statefulset.yaml` + init handling

**Analog:** `docker-compose.prod.yml` `postgres:` block (155-328); `docker/postgres-init/01-create-databases-and-roles.sh`

**Role/DB creation script — reuse near-verbatim as a ConfigMap-mounted init script or pre-restore Job:**
```bash
# docker/postgres-init/01-create-databases-and-roles.sh lines 1-19
set -eo pipefail
: "${PROD_DB_NAME:?PROD_DB_NAME must be set}"
: "${PROD_DB_USER:?PROD_DB_USER must be set}"
: "${PROD_DB_PASS:?PROD_DB_PASS must be set}"
: "${NONPROD_DB_NAME:?NONPROD_DB_NAME must be set}"
: "${NONPROD_DB_USER:?NONPROD_DB_USER must be set}"
: "${NONPROD_DB_PASS:?NONPROD_DB_PASS must be set}"
```
This script's REVOKE-PUBLIC-CONNECT logic (Phase 11 D-01 isolation) must be preserved exactly —
D-05 explicitly requires recreating these roles **before** the `pg_restore`.

**mem_limit comment discipline** — same as app; see `postgres:` block's own dated OOM-kill history
(lines ~174-230) before setting `resources.limits` for the StatefulSet.

---

### `.github/workflows/invariant-checks.yml` (new kubeconform job)

**Analog:** same file, `compose-published-ports` job

**3-step gate shape to copy exactly** (install dependency → run selftest → run the actual gate):
```yaml
# .github/workflows/invariant-checks.yml lines ~98-113
compose-published-ports:
  name: Only Caddy publishes host ports (80/443)
  runs-on: ubuntu-latest
  steps:
    - name: Checkout code
      uses: actions/checkout@v5
    - name: Install the gate's one dependency
      run: python3 -m pip install --disable-pip-version-check --quiet pyyaml
    - name: Verify the gate's invariants can fire
      run: python3 scripts/verify-compose-ports-selftest.py
    - name: Verify compose published-ports invariants
      run: python3 scripts/verify-compose-ports.py
```
New job (`k8s-manifests-valid` or similar) follows the same shape:
checkout → install kubeconform (pinned version/SHA, not `latest`, matching
`INSTALL_K3S_VERSION` pinning discipline) → `kubectl kustomize k8s/overlays/{nonprod,prod} |
kubeconform ...` per RESEARCH.md's Quick run command. `paths-ignore` block at the top of the file
(`docs/**`, `**/*.md`, `.planning/**`) should be reused unchanged — it already matches "pure
function of committed non-doc files."

**Docstring shape to copy for any new/ported `scripts/verify-*.py`:** WHY this gate exists (with a
falsifiable, dated claim) → SCOPE → KNOWN HOLES (enumerated, not hidden) → numbered invariants
matching emitted FAIL-line numbers. See `scripts/verify-compose-ports.py` lines 1-60 for the full
worked example — this is the required shape for any new k8s-invariant script (e.g. the ported
"no NodePort/hostPort except Traefik" check CONTEXT.md's Claude's Discretion section names).

---

### `.github/workflows/verify-rate-limit.yml` (edit for Traefik)

**Analog:** same file (workflow_dispatch-only trigger, generous timeout, no secrets)

**Structure to keep unchanged:** `workflow_dispatch` only (never auto-chained to deploy — the
rationale comment at the top of the file, lines ~17-20, explains why: cost of a false-real
lockout on the runner's own egress IP). Only the assertions inside
`scripts/loadtest/run-rate-limit-verification.sh` change (token-bucket vs fixed-window semantics,
per RESEARCH.md Pattern 2/Assumption A3) — the workflow YAML itself needs no structural change
beyond possibly adding a third `nonprod_url`-shaped input if Traefik's per-namespace routing
changes the negative-control hostname.

---

### `docs/INFRA_RUNBOOK.md` (append k3s sections)

**Analog:** same file's existing dated, falsifiable, measurement-cited section style — e.g. the
"Redpanda resource caps, measured" section (line ~691) and the Decommission Record (line ~338)
are the closest shape-match for the new cutover runbook and Compose decommission entries this
phase must add. New sections should follow the same `## Heading (Plan N-NN)` + measured-basis +
falsifier pattern already established, not prose without dates/evidence.

---

## Shared Patterns

### Measured, dated memory caps (applies to every new StatefulSet/Deployment resources block)
**Source:** `docker-compose.prod.yml` (every `mem_limit`, e.g. lines 152, 249, 362, 428, 568, 629,
680, 732, 784, 866, 956) — each carries a restart-ladder-measured, dated, falsifiable comment.
**Apply to:** every `k8s/base/*/statefulset.yaml` or `deployment.yaml` `resources.requests`/`limits`.
Do not port a Compose `mem_limit` number as `limits` without re-measuring — Kubernetes schedules
against `requests`, not `limits` (RESEARCH.md Anti-Patterns), so a `requests` figure needs its own
measurement, not a copy of the old cgroup cap.

### No host port except the edge
**Source:** `scripts/verify-compose-ports.py` docstring ("only `caddy` may publish a host port")
**Apply to:** all new `k8s/base/*/service.yaml` — default to `ClusterIP`; only Traefik's own
Service may be non-ClusterIP (LoadBalancer via klipper-lb/ServiceLB). This is the K8s-native
continuation of the same invariant, and CONTEXT.md's Claude's Discretion section explicitly asks
for it to be ported as a new invariant script rather than dropped.

### Gate docstring shape (WHY / SCOPE / KNOWN HOLES / numbered invariants + paired selftest)
**Source:** `scripts/verify-compose-ports.py` (full file) + `scripts/verify-compose-ports-selftest.py`
**Apply to:** any new `scripts/verify-*.py` this phase adds (k8s manifest invariants, row-count
parity check, rate-limit re-derivation). Every gate in `invariant-checks.yml` follows
selftest-before-gate ordering — new jobs must too.

### CI job trigger scoping (`paths-ignore` for docs/planning-only changes)
**Source:** `.github/workflows/invariant-checks.yml` lines 60-73
**Apply to:** the new kubeconform CI job — reuse the identical `paths-ignore` list so a
docs-only phase-planning commit doesn't trigger manifest validation.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `k8s/flux-system/*.yaml` (GitRepository, Kustomization, ImageRepository, ImagePolicy, ImageUpdateAutomation) | GitOps manifest | pub-sub/declarative | No GitOps tooling exists in this repo today — deploy is currently SSH+Compose. Use RESEARCH.md's "Code Examples" section (ImagePolicy, Flux bootstrap-without-PAT) verbatim as the starting shape. |
| `k8s/platform/clusterissuer-*.yaml` | cert-manager manifest | declarative | Caddy handled ACME implicitly with no committed config surface; use RESEARCH.md's cert-manager Code Example. |
| `k8s/platform/helmrelease-kube-prometheus-stack.yaml` (Prometheus Operator CRD wiring, ServiceMonitor disabling for k3s control-plane per Pitfall 5) | third-party chart values | declarative | No Prometheus Operator/Helm chart precedent in-repo (current Prometheus is static-config, bare container); use RESEARCH.md Pitfall 5's values snippet. |

## Metadata

**Analog search scope:** repo root (`docker-compose*.yml`, `Caddyfile`), `.github/workflows/`,
`scripts/`, `docker/postgres-init/`, `docs/INFRA_RUNBOOK.md`, `README.md` — all read directly, no
gitignored/mirror paths involved (this repo has no `.gsd/capabilities` mirror tree).
**Files scanned:** 12 read directly (docker-compose.prod.yml, docker-compose.nonprod.yml,
Caddyfile, deploy.yml, invariant-checks.yml, verify-rate-limit.yml, verify-compose-ports.py,
01-create-databases-and-roles.sh, INFRA_RUNBOOK.md headings, README.md not re-read here — covered
by CONTEXT.md's own citation of its two link sections).
**Pattern extraction date:** 2026-09-25
