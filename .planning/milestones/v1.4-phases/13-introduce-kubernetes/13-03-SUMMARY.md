---
phase: 13-introduce-kubernetes
plan: 03
subsystem: infra
tags: [kubernetes, observability, kube-prometheus-stack, loki, alloy, grafana, flux, helm, prometheus, postgres-exporter]

# Dependency graph
requires:
  - phase: 13-introduce-kubernetes/13-01
    provides: "Kustomize base/overlay layout, verify-k8s-manifests.sh, verify-k8s-invariants.py -- the shared validation gates this plan's HelmReleases/manifests are checked against"
provides:
  - "k8s/monitoring/{controllers,configs}: 4 pinned HelmReleases (kube-prometheus-stack, loki, alloy, postgres-exporter) declared as suspended-until-13-07 Flux resources, with literal-UID datasources and 3 Kubernetes-label-rewritten public dashboards"
  - "scripts/verify-public-dashboards.py: dual-scope (Compose + k8s) public-dashboard safety gate, the D-18 continuity proof for the three README-linked public shares"
  - "Grafana IngressRoute + login rate-limit Middleware, the ACME-path-excluding HTTP->HTTPS redirect shape 13-04's invariant I10 checks against"
affects: [13-04, 13-07, 13-09, 13-10]

# Actuals (#2632)
actuals:
  tokens: 160705
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added:
    - "kube-prometheus-stack 91.5.2 (Helm chart, appVersion v0.94.1)"
    - "grafana/loki 7.3.0 (appVersion 3.6.12, single-binary mode)"
    - "grafana/alloy 1.12.1 (appVersion v1.19.2, DaemonSet)"
    - "prometheus-community/prometheus-postgres-exporter 8.2.0 (image v0.20.1)"
  patterns:
    - "Every observability component declared as a Flux HelmRelease with a pinned chart version, statically proven via `helm template` against committed values in each task's own <verify> block before any live cluster exists"
    - "Grafana datasources sidecar with the chart's own default Prometheus datasource explicitly disabled, so a hand-authored literal-UID ConfigMap is the only datasource Grafana ever provisions"
    - "Chart-native credential injection (config.datasource.passwordSecret) preferred over hand-rolled extraEnvs once discovered that the naive env-var approach would make the chart bake a committed Secret from a literal password value"
    - "A separate single-route IngressRoute owns the `web` entryPoint exclusively (never shared with a websecure-serving IngressRoute), so the ACME HTTP-01 challenge-path exclusion is the ONLY rule ever evaluated on that entryPoint"

key-files:
  created:
    - k8s/monitoring/controllers/repositories.yaml
    - k8s/monitoring/controllers/kube-prometheus-stack.yaml
    - k8s/monitoring/controllers/loki.yaml
    - k8s/monitoring/controllers/alloy.yaml
    - k8s/monitoring/controllers/postgres-exporter.yaml
    - k8s/monitoring/controllers/kustomization.yaml
    - k8s/monitoring/configs/datasources.yaml
    - k8s/monitoring/configs/podmonitors.yaml
    - k8s/monitoring/configs/ingressroute.yaml
    - k8s/monitoring/configs/kustomization.yaml
    - k8s/monitoring/configs/dashboards/node-exporter-full.json
    - k8s/monitoring/configs/dashboards/cadvisor.json
    - k8s/monitoring/configs/dashboards/postgres-exporter.json
  modified:
    - scripts/verify-public-dashboards.py
    - scripts/verify-public-dashboards-selftest.py

key-decisions:
  - "postgres-exporter HelmRelease uses the chart's own config.datasource.* block with passwordSecret, not extraEnvs (DATA_SOURCE_URI/USER/PASS) -- discovered live via `helm template` that the chart's secrets.yaml template unconditionally requires config.datasource.password unless passwordSecret/passwordFile/datasourceSecret is set, and setting a literal password would make the chart RENDER a committed Secret object, violating D-10/I8."
  - "cadvisor.json's 'Containers Info' table panel transformations (filterFieldsByName/organize) rewritten from Docker Compose label names (container_label_com_docker_compose_project/_service) to Kubernetes-native fields (namespace/pod/container/image/instance) -- caught by the dual-scope gate's K8S_BANNED_LITERALS check as a real incomplete rewrite, not just the panel's query expr."
  - "k8s node-exporter-full.json and postgres-exporter.json's `instance` literal is set to a placeholder (netcup-prod-node), not a discovered value -- the real k3s node hostname is unknowable until k3s is actually installed (13-07); documented as a live-verification gap in both the dashboard rewrite and the gate's own docstring KNOWN HOLES section, rather than fabricated with false certainty."
  - "verify-public-dashboards.py's dual-scope refactor keeps K8S_PENDING as a named (now-empty) set rather than deleting the mechanism -- a future dashboard addition lands the same way, and the 'file present but still listed pending' self-check stays exercised."

requirements-completed: [D-07, D-09, D-17, D-18]

coverage:
  - id: D1
    description: "kube-prometheus-stack, Loki (single-binary, 30d), Grafana Alloy and a chart-managed postgres-exporter declared as Flux HelmReleases with pinned chart versions, under the (not-yet-created, 13-02-owned) suspended monitoring Kustomizations"
    requirement: "D-07, D-09, D-17"
    verification:
      - kind: other
        ref: "helm template of all 4 pinned charts against committed values (each task's own <verify> block, re-run at SUMMARY time)"
        status: pass
      - kind: other
        ref: "bash scripts/verify-k8s-manifests.sh"
        status: pass
      - kind: other
        ref: "python3 scripts/verify-k8s-invariants.py"
        status: pass
    human_judgment: false
  - id: D2
    description: "The chart replaces cAdvisor/node-exporter/postgres-exporter containers with kubelet cAdvisor, the chart's node-exporter (no hostNetwork/hostPID), and a chart exporter; no docker.sock mount anywhere"
    requirement: D-07
    verification:
      - kind: other
        ref: "helm template assertion: no hostNetwork/hostPID on node-exporter DaemonSet, no docker.sock in any rendered doc (Task 1 <verify>); verify-k8s-invariants.py I2"
        status: pass
    human_judgment: false
  - id: D3
    description: "Grafana keeps datasource UIDs PBFA97CFB590B2093/P8E80F9AEF21F6940 as literal values, runs image 13.2.1, and its own default Prometheus datasource is disabled so grafana-datasources is the only one"
    requirement: D-07
    verification:
      - kind: other
        ref: "helm template assertion: grafana image ends :13.2.1; datasources.yaml uids == {PBFA97CFB590B2093, P8E80F9AEF21F6940} (Task 1 <verify>)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Three dashboards exist as k8s copies with Kubernetes-label queries and stable hard-coded values; verify-public-dashboards.py gates both Compose and k8s copies"
    requirement: "D-07, D-18"
    verification:
      - kind: other
        ref: "python3 scripts/verify-public-dashboards.py (dual-scope: compose 3 checked, k8s 3 checked)"
        status: pass
      - kind: other
        ref: "python3 scripts/verify-public-dashboards-selftest.py"
        status: pass
    human_judgment: true
    rationale: "Query well-formedness (uid/no-variable/no-Docker-literal) is proven statically by this gate; whether each panel actually RENDERS DATA against the real chart-scraped series can only be proven live, at the 13-07 activation -- explicitly documented in the gate's own docstring KNOWN HOLES section, not silently assumed."
  - id: D5
    description: "Alertmanager, kube-prometheus-stack default alert rules, and k3s-incompatible control-plane ServiceMonitors are disabled"
    requirement: D-07
    verification:
      - kind: other
        ref: "helm template assertion: no Alertmanager kind, no kube-controller-manager/scheduler/etcd/proxy ServiceMonitor (Task 1 <verify>)"
        status: pass
    human_judgment: false
  - id: D6
    description: "The monitoring host's HTTP->HTTPS redirect (IngressRoute grafana-http, web entryPoint only) excludes /.well-known/acme-challenge/ in its own rule"
    requirement: D-13
    verification:
      - kind: other
        ref: "helm-independent assertion over ingressroute.yaml: web entryPoint carried by exactly IngressRoute grafana-http with one route matching the ACME-exclusion pattern (Task 2 <verify>)"
        status: pass
    human_judgment: false

duration: 46min
completed: 2026-09-25
status: complete
---

# Phase 13 Plan 03: In-Cluster Observability Stack Summary

**Four pinned Flux HelmReleases (kube-prometheus-stack, Loki, Alloy, postgres-exporter) plus the Kubernetes-label rewrite of all three public dashboards, statically proven against the real charts before any cluster exists.**

## Performance

- **Duration:** 46 min (from first commit `d87e5f6` at 2026-09-25T14:27:30Z to SUMMARY at 2026-09-25T14:35:36Z UTC plus the checkpoint pause awaiting bypass authorization)
- **Started:** 2026-09-25T14:27:30Z
- **Completed:** 2026-09-25T14:35:36Z (SUMMARY authoring)
- **Tasks:** 3/3 complete
- **Files modified:** 15 (13 created, 2 modified)

## Accomplishments

- `k8s/monitoring/controllers/`: HelmRepositories (prometheus-community, grafana) and four pinned HelmReleases -- kube-prometheus-stack 91.5.2 (Alertmanager/default-rules/control-plane ServiceMonitors disabled, node-exporter without hostNetwork/hostPID, Grafana pinned to 13.2.1 with its own default datasource disabled), Loki 7.3.0 (single-binary, filesystem storage, 720h retention, gateway/caches/canary/self-monitoring all off), Alloy 1.12.1 (DaemonSet, API-only pod-log tailing via `loki.source.kubernetes`, no hostPath mounts), postgres-exporter 8.2.0 (chart-native `config.datasource.*` block with `passwordSecret`, `kanban-board/postgres-client` pod label for the NetworkPolicy contract).
- `k8s/monitoring/configs/`: `grafana-datasources` ConfigMap carrying the Compose-era literal UIDs verbatim; a Redpanda PodMonitor spanning both app namespaces with an `env` relabeling; a Grafana `IngressRoute` (websecure, rate-limited `/login`) plus a strictly separate `grafana-http` IngressRoute that owns the `web` entryPoint exclusively and excludes the ACME HTTP-01 challenge path from its redirect.
- `k8s/monitoring/configs/dashboards/`: all three public dashboards (node-exporter-full, cadvisor, postgres-exporter) copied and rewritten from Docker/Compose labels to Kubernetes/kubelet labels -- including a real defect caught mid-task (cadvisor's "Containers Info" table panel still filtering on Docker Compose-specific field names after the query itself was rewritten).
- `scripts/verify-public-dashboards.py` + selftest: refactored from a single scope into a dual-scope gate (Compose + k8s), each scope independently checking every invariant (datasource-uid literalness, no template variables, no `DS_` placeholders, panel-type allowlist, Grafana-version-vs-allowlist drift) against its own ground truth (Compose file vs. the k8s datasources ConfigMap / HelmRelease). Gained a k8s-scope-only invariant rejecting Docker-era literals (`cadvisor:8080`, `node-exporter:9100`, `postgres-exporter:9187`, `container_label_com_docker`) that would silently render "No data" against the real chart-scraped series.

## Task Commits

Each task was committed atomically:

1. **Task 1: Tracer -- kube-prometheus-stack HelmRelease -> literal-UID datasources -> one rewritten dashboard -> public-dashboard gate green on the k8s copy** - `d87e5f6` (feat)
2. **Task 2: Loki, Alloy, postgres-exporter, Redpanda PodMonitor, Grafana route with its login rate limit** - `fbf9a2a` (feat)
3. **Task 3: Rewrite cadvisor and postgres-exporter dashboards to Kubernetes labels; complete the dual-scope gate** - `f22c792` (feat)

**Plan metadata:** (this commit)

## Files Created/Modified

- `k8s/monitoring/controllers/repositories.yaml` - HelmRepositories for prometheus-community and grafana chart sources
- `k8s/monitoring/controllers/kube-prometheus-stack.yaml` - Prometheus Operator + Prometheus + Grafana + kube-state-metrics HelmRelease, Alertmanager/control-plane-monitors disabled
- `k8s/monitoring/controllers/loki.yaml` - Single-binary Loki HelmRelease, 720h retention, all extra components disabled
- `k8s/monitoring/controllers/alloy.yaml` - DaemonSet log-collector HelmRelease, API-only pod-log tailing
- `k8s/monitoring/controllers/postgres-exporter.yaml` - Chart-native datasource config with passwordSecret, NetworkPolicy pod label
- `k8s/monitoring/controllers/kustomization.yaml` - Lists all 5 controller manifests
- `k8s/monitoring/configs/datasources.yaml` - Literal-UID Grafana datasources ConfigMap (sidecar-labelled)
- `k8s/monitoring/configs/podmonitors.yaml` - Redpanda PodMonitor spanning both app namespaces
- `k8s/monitoring/configs/ingressroute.yaml` - Grafana IngressRoute (websecure + rate-limited login) and grafana-http (web, ACME-excluded redirect)
- `k8s/monitoring/configs/kustomization.yaml` - Lists configs + 3-dashboard configMapGenerator
- `k8s/monitoring/configs/dashboards/node-exporter-full.json` - Kubernetes-label rewrite of the node-exporter dashboard
- `k8s/monitoring/configs/dashboards/cadvisor.json` - Kubernetes-label rewrite (kubelet cAdvisor) including the Containers Info panel fix
- `k8s/monitoring/configs/dashboards/postgres-exporter.json` - Kubernetes-label rewrite (stable node-name instance)
- `scripts/verify-public-dashboards.py` - Dual-scope (Compose + k8s) public-dashboard safety gate
- `scripts/verify-public-dashboards-selftest.py` - Gained 5 new k8s-scope selftest cases

## Decisions Made

- **postgres-exporter HelmRelease uses `config.datasource.*` with `passwordSecret`, not `extraEnvs`** -- discovered live via `helm template` that the naive DATA_SOURCE_URI/USER/PASS env-var approach copied from the Compose service comment would trip the chart's own `secrets.yaml` template into requiring (and, if a literal value were supplied, rendering) a committed Secret object -- a real D-10/I8 violation the manifest-render step caught before it ever reached a commit.
- **cadvisor.json's "Containers Info" table transformations rewritten to Kubernetes-native fields**, not just its PromQL query -- the dual-scope gate's own K8S_BANNED_LITERALS check flagged `container_label_com_docker_compose_project`/`_service` surviving in the panel's `filterFieldsByName`/`organize` transformation options after the query expr itself was already correctly rewritten to kubelet labels, proving the gate catches incomplete rewrites beyond just the `expr` field.
- **`instance` literal for node-exporter-full.json and postgres-exporter.json is a documented placeholder (`netcup-prod-node`)**, not a discovered real hostname -- the k3s node's actual name cannot be known until k3s is installed (13-07, out of this plan's scope); recorded as a live-verification gap in both the dashboard files' surrounding context and the gate's own KNOWN HOLES docstring section rather than silently assumed correct.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] postgres-exporter's structured `config.datasource.*` replaces the Compose-copied `extraEnvs` DATA_SOURCE_URI/USER/PASS pattern**
- **Found during:** Task 2 verification (`helm template` render step)
- **Issue:** The plan's `<action>` specified DATA_SOURCE_URI/DATA_SOURCE_USER/DATA_SOURCE_PASS env vars, mirroring the Compose service's own environment block. The chart's `secrets.yaml` template unconditionally calls `required "..." .Values.config.datasource.password` unless one of `passwordSecret`/`passwordFile`/`datasourceSecret` is set -- rendering failed outright with that error, and setting `password` directly would have made the chart bake a committed Secret from a literal value.
- **Fix:** Switched to the chart's own `config.datasource.{host,user,port,database,sslmode}` fields plus `config.datasource.passwordSecret: {name: postgres-exporter, key: password}`, referencing the same externally-created Secret D-10 already specifies.
- **Files modified:** `k8s/monitoring/controllers/postgres-exporter.yaml`
- **Verification:** `helm template` renders cleanly; `verify-k8s-invariants.py` I8 (no committed Secret) passes.
- **Committed in:** `fbf9a2a` (Task 2 commit)

**2. [Rule 1 - Bug] cadvisor.json's "Containers Info" panel transformations rewritten from Docker Compose fields to Kubernetes fields**
- **Found during:** Task 3 verification (`verify-public-dashboards.py` dual-scope gate)
- **Issue:** The panel's PromQL query was correctly rewritten to kubelet-cAdvisor labels, but its `filterFieldsByName`/`organize` transformations still referenced Docker Compose-specific series labels (`container_label_com_docker_compose_project`, `_project_working_dir`, `_service`) that kubelet's cAdvisor scrape never produces -- the table would have rendered with those columns silently empty.
- **Fix:** Rewrote the transformation's `include.names` and `renameByName` to Kubernetes-native fields (`namespace`, `pod`, `container`, `image`, `instance`).
- **Files modified:** `k8s/monitoring/configs/dashboards/cadvisor.json`
- **Verification:** `python3 scripts/verify-public-dashboards.py` passes (K8S_BANNED_LITERALS check for `container_label_com_docker` clears).
- **Committed in:** `f22c792` (Task 3 commit)

---

**Total deviations:** 2 auto-fixed (1 blocking chart-API-mismatch bug, 1 incomplete-rewrite bug caught by this plan's own gate)
**Impact on plan:** Both auto-fixes are corrections to how the plan's own `<action>` text was implemented, caught by this plan's own verification steps before being considered done -- neither is scope creep, and neither is a latent defect left standing.

## Issues Encountered

**Pre-commit hook bypassed 3 times (`--no-verify`), user-authorized for this plan (13-03) only, same scoped pattern as 13-01/13-04.** Root cause: a standing host memory-pressure issue (documented in 13-01-SUMMARY.md and reconfirmed here) -- the pre-commit hook's `./gradlew fastTest` step repeatedly hit `Gradle build daemon has been stopped: stop command received`, unrelated to this plan's code. All three commits' diffs were YAML/JSON/Python-only (zero Java source touched); `spotlessCheck` passed cleanly on every attempt before the daemon died. Before each bypass, this plan's own manifest (`verify-k8s-manifests.sh`), invariant (`verify-k8s-invariants.py`), dashboard-gate (`verify-public-dashboards.py`), and each task's own `<verify>` Python block were independently run and confirmed passing, and are re-documented in each commit body:
- `d87e5f6` (Task 1) -- bypassed after 2 consecutive normal-hook attempts failed identically; checkpoint raised to the orchestrator first, authorization received before bypassing.
- `fbf9a2a` (Task 2) -- bypassed after 1 normal-hook attempt failed identically (per the extended authorization's "retry the normal hook first" instruction).
- `f22c792` (Task 3) -- bypassed after 1 normal-hook attempt failed identically.

No bypassed verification ever surfaced a real (non-environmental) failure -- the authorization's stop-and-report condition for that case was never triggered.

## User Setup Required

None - no external service configuration required. The Secrets this plan's manifests reference (`grafana-admin`, `postgres-exporter`) are created on the VM in 13-07 (D-10), out of this plan's scope by design.

## Next Phase Readiness

- 13-07 can activate observability by unsuspending the monitoring Kustomizations (13-02's responsibility) and creating the `grafana-admin`/`postgres-exporter` Secrets, without authoring anything further -- matching this plan's own `<success_criteria>`.
- **Known gap, explicitly deferred to 13-07 per this plan's own scope:** the `instance` placeholder (`netcup-prod-node`) in two dashboards, and the real Service names this plan derived via `helm template` (`kps-prometheus`, `kps-grafana`, `loki`), must be re-confirmed against the actual installed k3s cluster once it exists -- this plan proves the manifests are internally consistent and schema-valid, not that they reconcile successfully against a live cluster.
- 13-09's restart-ladder work has a starting point in every `resources:` block this plan wrote (each labelled `PROVISIONAL (restart-ladder in 13-09)`), anchored to the same-binary Compose predecessors' measured caps where one exists.
- 13-10 (Compose decommission) can delete `docker/grafana/provisioning/dashboards/json/`, `docker/loki/`, and the Compose observability services once the k8s scope's live "renders data" proof (this plan's one documented KNOWN HOLE) passes at the 13-07 activation.

---
*Phase: 13-introduce-kubernetes*
*Completed: 2026-09-25*

## Self-Check: PASSED

- `k8s/monitoring/controllers/repositories.yaml` exists: FOUND
- `k8s/monitoring/controllers/kube-prometheus-stack.yaml` exists: FOUND
- `k8s/monitoring/controllers/loki.yaml` exists: FOUND
- `k8s/monitoring/controllers/alloy.yaml` exists: FOUND
- `k8s/monitoring/controllers/postgres-exporter.yaml` exists: FOUND
- `k8s/monitoring/configs/datasources.yaml` exists: FOUND
- `k8s/monitoring/configs/podmonitors.yaml` exists: FOUND
- `k8s/monitoring/configs/ingressroute.yaml` exists: FOUND
- `k8s/monitoring/configs/dashboards/node-exporter-full.json` exists: FOUND
- `k8s/monitoring/configs/dashboards/cadvisor.json` exists: FOUND
- `k8s/monitoring/configs/dashboards/postgres-exporter.json` exists: FOUND
- Commit `d87e5f6` exists in `git log`: FOUND
- Commit `fbf9a2a` exists in `git log`: FOUND
- Commit `f22c792` exists in `git log`: FOUND
- `bash scripts/verify-k8s-manifests.sh` exits 0: PASSED (re-run at Self-Check time)
- `python3 scripts/verify-k8s-invariants.py` exits 0: PASSED (re-run at Self-Check time)
- `python3 scripts/verify-public-dashboards.py` exits 0: PASSED (re-run at Self-Check time)
- `python3 scripts/verify-public-dashboards-selftest.py` exits 0: PASSED (re-run at Self-Check time)
