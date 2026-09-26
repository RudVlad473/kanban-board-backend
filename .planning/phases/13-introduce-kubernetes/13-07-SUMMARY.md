---
phase: 13-introduce-kubernetes
plan: 07
subsystem: infra
tags: [kubernetes, k3s, flux, kube-prometheus-stack, loki, grafana, postgres-exporter, observability, d-07, d-08, d-10, d-17, d-18]

# Dependency graph
requires:
  - phase: 13-06
    provides: "Production live on k3s; a deliberate Endpoints-less placeholder IngressRoute grafana/grafana-http, sized exactly for this plan to replace"
  - phase: 13-03
    provides: "Suspended kube-prometheus-stack/Loki/Alloy/postgres-exporter manifests, dual-scope public-dashboard gate"
provides:
  - "kube-prometheus-stack, Loki, Alloy and postgres-exporter live and Ready in namespace monitoring"
  - "17 Prometheus targets, 0 down; Loki holding logs for all 6 required namespaces"
  - "Grafana reachable only at the monitoring hostname over a production certificate, admin password from Secret grafana-admin (D-10)"
  - "Exactly the three PUBLIC_DASHBOARDS public shares recreated on the new Grafana, README's two links repointed, Postgres Internals share unlinked (D-18 corrected)"
  - "docs/SESSION_LESSONS.md lesson 9: local memory contention on a shared/constrained host can kill every Gradle JVM"
affects: [13-09, 13-10]

# Actuals (#2632)
actuals:
  tokens: 51383
  tasks: 2
  commits: 8
plan_head_before: 1bd138b5a69260a1ad4bc05d66aba758b9d057c5

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "A HelmRelease values key nested one level too shallow (a subchart option placed at the parent values top level instead of under the subchart's own block) is silently ignored by Helm rather than rejected -- render with `helm template` against the exact committed values and diff the actual output, never trust a header comment's claimed Service/behavior without re-deriving it against the pinned chart version"
    - "container_network_* cAdvisor metrics carry no `container` label (network is reported per-pod, at the shared network namespace) -- a `container!=\"\"` filter on them matches zero series by construction; group by namespace/pod instead"
    - "A database CREATE/DROP resets its ACL to Postgres defaults, silently reverting any GRANT CONNECT an init script applied at first boot -- an init-script-only grant does not survive a later drop-and-recreate of the same database name"

key-files:
  modified:
    - k8s/flux-system/monitoring-controllers.yaml
    - k8s/flux-system/monitoring.yaml
    - k8s/platform/cert-manager/cert-manager.yaml
    - k8s/monitoring/controllers/kube-prometheus-stack.yaml
    - k8s/monitoring/controllers/postgres-exporter.yaml
    - k8s/monitoring/configs/ingressroute.yaml
    - k8s/monitoring/configs/dashboards/node-exporter-full.json
    - k8s/monitoring/configs/dashboards/cadvisor.json
    - k8s/monitoring/configs/dashboards/postgres-exporter.json
    - scripts/verify-public-dashboards.py
    - README.md
    - docs/INFRA_RUNBOOK.md
    - docs/SESSION_LESSONS.md
    - .planning/todos/pending/2026-09-08-grafana-admin-password-drift-from-env-prod.md -> completed
    - .planning/todos/pending/2026-09-07-rotate-grafana-viewer-password-leaked-in-session.md -> completed

key-decisions:
  - "Operator-authorized `git commit --no-verify` for 5 of 8 commits this plan made, after the local Gradle JVM proved unable to survive fork under sustained host memory pressure from unrelated concurrent sessions (confirmed via free -h, not assumed) -- each time preceded by re-running the non-JVM gates (verify-k8s-manifests.sh, verify-k8s-invariants.py, verify-public-dashboards.py + selftest) and gitleaks' own clean scan from the failed hooked attempt. The final docs-only commit's push happened to touch scripts/ (outside deploy.yml's paths-ignore), triggering the real CI/CD-with-Docker workflow server-side -- it passed green, independently confirming no Java-level defect was let through by any of the skips."
  - "A live database-state fix (re-applying GRANT CONNECT for the monitoring role) was applied directly against the running Postgres rather than through a manifest change -- the committed init script (k8s/data/postgres/init/02-create-monitoring-role.sh) is already correct for any future first boot; the grant was lost specifically because 13-06's incident recovery dropped and recreated kanban_prod/kanban_nonprod after the init script had already run once."

requirements-completed: [D-07, D-10, D-17, D-18]

coverage:
  - id: D1
    description: "kube-prometheus-stack, Loki, Alloy and postgres-exporter run in namespace monitoring, activated at the prod cutover; every Prometheus target up, no k3s-incompatible control-plane job, Redpanda scraped from both envs"
    requirement: D-07
    verification:
      - kind: other
        ref: "k3s kubectl get helmrelease -n monitoring (all 4 Ready); GET /api/v1/targets?state=active -> 17 targets, 0 down, no kube-controller-manager/scheduler/etcd/proxy job, redpanda env=prod and env=nonprod both present"
        status: pass
    human_judgment: false
  - id: D2
    description: "Loki returns logs for all 6 required namespaces, collected without docker.sock or hostPath"
    requirement: D-07
    verification:
      - kind: other
        ref: "GET /loki/api/v1/label/namespace/values -> cert-manager, flux-system, kanban-data, kanban-nonprod, kanban-prod, kube-system, monitoring; k3s kubectl get pods -A hostPath grep for docker.sock -> none found"
        status: pass
    human_judgment: false
  - id: D3
    description: "Grafana reachable only at the monitoring hostname over a production certificate, admin password from Secret created from .env.prod"
    requirement: D-10
    verification:
      - kind: other
        ref: "GET /api/health -> 200; GET /api/user (anonymous) -> 401; authenticated GET /api/user via Secret grafana-admin's password -> login=admin, isGrafanaAdmin=true; HTTP root redirects to HTTPS except /.well-known/acme-challenge/ (404, no 3xx)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Exactly the three PUBLIC_DASHBOARDS shares recreated on the new Grafana; README's two links repointed in one commit; Postgres Internals share exists but is unlinked"
    requirement: D-18
    verification:
      - kind: other
        ref: "GET /api/dashboards/public-dashboards -> exactly 3 shares (rYdddlPWk, pMEd7m0Mz, v5ciIbUZz), all isEnabled=true; each /api/public/dashboards/<token> -> 200; README links match node-exporter-full/cadvisor tokens exactly, does not contain the postgres-exporter token; verify-public-dashboards.py green"
        status: pass
    human_judgment: false
  - id: D5
    description: "Every panel across all three dashboards diagnosed: data-OK or a named, accepted uncollected-metric gap; no undiagnosed panel"
    requirement: D-07
    verification:
      - kind: other
        ref: "docs/INFRA_RUNBOOK.md 'Observability on k3s -- Plan 13-07' section 4: 124/6/35 panels per dashboard, 104/6/35 fully data-OK, 20 node-exporter-full panels named and diagnosed (kernel/hardware capability absent or collector never enabled), 3 infra bugs + 2 label-mismatch bugs found and fixed live"
        status: pass
    human_judgment: true
    rationale: "The panel-diagnosis table's completeness and the accepted-gap classifications are a judgment call about whether each named gap is genuinely unfixable (kernel capability, VM hardware absence) versus something that should have been fixed -- a human should skim the runbook's own reasoning per gap rather than trust an automated count alone."

duration: ~2h40m (09:02 UTC secrets created through 20:27 UTC final commit pushed; includes an extended mid-session pause diagnosing/resolving a host memory-pressure blocker across Task 1's commit)
completed: 2026-09-26
status: complete
---

# Phase 13 Plan 07: Observability activation on k3s Summary

**kube-prometheus-stack, Loki, Alloy and postgres-exporter are live on the production k3s cluster with zero down targets, all six required log namespaces present, and the three public Grafana dashboards recreated with real data — closing D-07/D-10/D-17/D-18, after finding and fixing five real infrastructure/dashboard bugs that only surfaced once the stack actually reconciled against the live cluster for the first time.**

## Performance

- **Duration:** ~2h40m across the session (09:02–20:27 UTC), including an extended pause working through a host memory-pressure blocker on the first commit
- **Tasks:** 2/2 complete
- **Files modified:** 15
- **Commits:** 8 — measured via `git rev-list --count 1bd138b..HEAD` (10 total commits in the window; 2 are external — `89a43b5` fluxcdbot's automated image bump, `9b0d12d` the coordinator's own CI-invariant fix, landed via the shared `main` branch between my own pushes)

## Accomplishments

- **Task 1 (tracer) — observability stack activated end-to-end.** Secrets `monitoring/grafana-admin` and `monitoring/postgres-exporter` created directly on the VM from `.env.prod`, never committed. Both monitoring Kustomizations unsuspended. cert-manager's ServiceMonitor enabled now that the CRD exists. A real, live-only-discoverable Grafana crash (two datasource ConfigMaps both marked `isDefault: true`, caused by 13-03's `sidecar:` values key sitting at the wrong nesting level) was found and fixed. Once Grafana came up, its public IngressRoutes still 404'd — the real rendered Service name is `kube-prometheus-stack-grafana`, not `kps-grafana` as the header comment assumed (a subchart's `fullnameOverride` does not inherit the parent chart's). Fixed across all three route occurrences. Authenticated `/api/user` and a real `/api/ds/query` panel query both confirmed working before Task 1 was called done.
- **Task 2 — every target/log/panel diagnosed; two more infra bugs found; public shares recreated.** 17 Prometheus targets, 0 down. All 6 required Loki namespaces present. Diagnosed all 165 panels across the three dashboards via direct Prometheus queries and the public API; found and fixed two dashboard-JSON label mismatches (a stale `instance` placeholder, and node-exporter-full's `job` label reading the wrong value entirely) and one real HelmRelease bug (postgres-exporter's `extraArgs` at the wrong values nesting level, meaning `--collector.postmaster`/`--collector.stat_statements` never reached the binary). While diagnosing, also found that the `monitoring` Postgres role's `GRANT CONNECT` had been silently reverted by 13-06's incident recovery (a `DROP`/`CREATE DATABASE` resets ACLs) — re-applied live against the running database. All three public dashboard shares recreated, README's two links repointed, both stale Grafana-credential todos closed with resolutions, and `docs/INFRA_RUNBOOK.md` gained a full "Observability on k3s" reference section.

## Task Commits

1. **Task 1 (tracer):**
   - `3a23906` — feat(13-07): activate observability stack, enable cert-manager ServiceMonitor
   - `769c678` — fix(13-07): nest kube-prometheus-stack Grafana sidecar under grafana: (D-07)
   - `bc8dc36` — fix(13-07): repoint Grafana IngressRoute at the real Service name
   - `0b3551d` — fix(13-07): repoint grafana-http IngressRoute at the real Service name too
2. **Task 2 (auto):**
   - `717fdac` — fix(13-07): node-exporter-full dashboard job label mismatch (D-07)
   - `1460e3a` — fix(13-07): dashboard label mismatches and postgres-exporter extraArgs nesting (D-07)
   - `6eacda1` — docs(13-07): recreate public dashboard shares, close both Grafana todos

**Plan metadata:** `861f3ae` — docs(13-07): record lesson 9 (a documentation commit made mid-Task-1 to capture the memory-pressure blocker as it was actively being diagnosed, not a separate task)

## Files Created/Modified

- `k8s/flux-system/monitoring-controllers.yaml`, `k8s/flux-system/monitoring.yaml` — unsuspended (D-02/D-17 activation)
- `k8s/platform/cert-manager/cert-manager.yaml` — `prometheus.enabled`/`servicemonitor.enabled` turned on
- `k8s/monitoring/controllers/kube-prometheus-stack.yaml` — `sidecar:` moved under `grafana:`; header comment corrected for Grafana/kube-state-metrics/node-exporter Service names
- `k8s/monitoring/controllers/postgres-exporter.yaml` — `extraArgs:` moved under `config:`
- `k8s/monitoring/configs/ingressroute.yaml` — all three Service references repointed to `kube-prometheus-stack-grafana`
- `k8s/monitoring/configs/dashboards/{node-exporter-full,postgres-exporter}.json` — `instance` placeholder corrected to the real node name
- `k8s/monitoring/configs/dashboards/node-exporter-full.json` — `job` label corrected (`node-exporter`, not `prometheus-node-exporter`)
- `k8s/monitoring/configs/dashboards/cadvisor.json` — two network-traffic panels' `container` filter removed, regrouped by namespace/pod
- `scripts/verify-public-dashboards.py` — comment updated to name the new Grafana and verification date
- `README.md` — two public links repointed at the new tokens
- `docs/INFRA_RUNBOOK.md` — new "Observability on k3s — Plan 13-07" section
- `docs/SESSION_LESSONS.md` — new lesson 9 (memory contention diagnostic sequence + `--no-verify` fallback pattern)
- Two Grafana-credential todos moved `pending/` → `completed/` with Resolution sections

## Decisions Made

See `key-decisions` in frontmatter. Summarized: (1) operator-authorized `--no-verify` used repeatedly under confirmed, sustained host memory pressure that killed every local Gradle JVM fork regardless of heap size — each use preceded by the lightweight non-JVM gates and a clean gitleaks scan, and independently confirmed safe by the one push in this plan that happened to trigger CI's real Java build/test workflow (green); (2) a live database grant re-applied directly rather than through a manifest, since the committed init script is already correct for any future first boot and the grant loss was a one-time side effect of 13-06's own incident recovery, not a defect in this plan's own manifests.

## Deviations from Plan

### Auto-fixed Issues (Rule 1 — bugs found only once the stack ran live)

**1. [Rule 1 — Bug] Grafana crash-looped on activation: two datasource ConfigMaps both marked `isDefault: true`**
- **Found during:** Task 1, verifying the `kube-prometheus-stack` HelmRelease reached Ready.
- **Issue:** `sidecar:` sat as a top-level sibling of `grafana:` in the committed values, but the chart's template reads `.Values.grafana.sidecar.datasources.defaultDatasourceEnabled` — the wrong nesting was silently ignored by Helm, so the chart's own default (`defaultDatasourceEnabled: true`) rendered a second datasource ConfigMap also marked default alongside this task's own literal-UID one. Grafana refused to start.
- **Fix:** Nested `sidecar:` under `grafana:`. Re-verified via `helm template`: the chart-managed ConfigMap no longer renders.
- **Files modified:** `k8s/monitoring/controllers/kube-prometheus-stack.yaml`
- **Commit:** `769c678`

**2. [Rule 1 — Bug] Grafana's public IngressRoutes 404'd on the real Service name**
- **Found during:** Task 1, verifying `/api/health`/`/api/user` over HTTPS.
- **Issue:** The committed IngressRoute (inherited from 13-03/13-06's placeholder, never live-verified) pointed at `kps-grafana`; the real rendered Service is `kube-prometheus-stack-grafana` — a subchart's own `fullnameOverride` does not inherit the parent chart's, since `grafana.fullname` reads `.Release.Name` directly.
- **Fix:** Repointed all three route occurrences (across `grafana` and `grafana-http` IngressRoute objects, found in two separate commits since the first pass missed the third occurrence).
- **Files modified:** `k8s/monitoring/configs/ingressroute.yaml`
- **Commits:** `bc8dc36`, `0b3551d`

**3. [Rule 1 — Bug] postgres-exporter never connected, then never collected pg_stat_statements/postmaster metrics**
- **Found during:** Task 2, diagnosing the Postgres Internals dashboard's three empty panels.
- **Issue:** Two independent causes — (a) the `monitoring` role's `GRANT CONNECT` on `kanban_prod`/`kanban_nonprod`, applied correctly by the committed init script at first boot, was silently reverted when 13-06's incident recovery dropped and recreated both databases mid-window (`CREATE DATABASE` resets ACLs to defaults); (b) the HelmRelease's `extraArgs` sat at the values top level, but the chart's Deployment template reads `.Values.config.extraArgs`, so `--auto-discover-databases`/`--collector.postmaster`/`--collector.stat_statements` never reached the exporter binary.
- **Fix:** Re-applied the `GRANT CONNECT` directly against the running Postgres (not a manifest change — the init script is already correct for any future first boot); nested `extraArgs` under `config:` in the HelmRelease.
- **Files modified:** `k8s/monitoring/controllers/postgres-exporter.yaml`
- **Commit:** `1460e3a`

### Auto-fixed Issues (Rule 1 — dashboard label mismatches)

**4. [Rule 1 — Bug] `instance` placeholder never matched the real k3s node name**
- **Found during:** Task 1/Task 2, running the tracer panel query and the full per-panel diagnosis.
- **Issue:** Both node-exporter-full and Postgres Internals hardcoded `instance="netcup-prod-node"`, a value that was never the real node name (`v2202608397723499373`).
- **Fix:** Replaced all 351+51 occurrences across both files.
- **Files modified:** `k8s/monitoring/configs/dashboards/{node-exporter-full,postgres-exporter}.json`
- **Commits:** `769c678` (partial, tracer's own single-panel fix), `1460e3a` (full dashboard-wide fix)

**5. [Rule 1 — Bug] node-exporter-full's `job` label read the wrong value across every panel**
- **Found during:** Task 1's tracer panel query.
- **Issue:** Every panel hardcoded `job="prometheus-node-exporter"`, but the chart deliberately sets `jobLabel: node-exporter` on the ServiceMonitor ("to match standard common usage in rules and grafana dashboards" — the chart's own comment) — no series ever carried the assumed value.
- **Fix:** Corrected to `node-exporter` across all 284 occurrences.
- **Files modified:** `k8s/monitoring/configs/dashboards/node-exporter-full.json`
- **Commit:** `717fdac`

**6. [Rule 1 — Bug] cAdvisor's two network-traffic panels filtered on a label that never exists**
- **Found during:** Task 2's per-panel diagnosis.
- **Issue:** `container!=""`/`container!="POD"` filters on `container_network_*` metrics, which are reported per-pod (the pod's shared network namespace) and never carry a `container` label at all — the filter matched zero series by construction.
- **Fix:** Removed the filter, regrouped by `namespace`/`pod`.
- **Files modified:** `k8s/monitoring/configs/dashboards/cadvisor.json`
- **Commit:** `1460e3a`

---

**Total deviations:** 6 auto-fixed (all Rule 1 — real bugs blocking correct operation, discovered only once the manifests reconciled against or queried the live cluster for the first time)
**Impact on plan:** All six fixes were necessary for the plan's own must-haves (every panel data-OK or diagnosed, public shares rendering real data) — no scope creep. None touched files outside `k8s/monitoring/` or its own dashboard JSON.

## Issues Encountered

**Sustained host memory pressure blocked the local pre-commit hook's `fastTest` step repeatedly** across most of this plan's commits. Diagnosed as ambient pressure from unrelated concurrent processes on a shared, memory-capped host (not a colliding sibling GSD worktree, which was checked and ruled out via `git worktree list`) — confirmed via `free -h` showing as little as 130Mi–1.7Gi free throughout. Even a fresh, non-shared, small-heap (`-Xmx512m`) single-use Gradle JVM was killed the same way, ruling out "a long-lived daemon's own low-memory self-expiration" as the mechanism. Operator-authorized `git commit --no-verify` was used for 5 of 8 commits, each preceded by re-running the lightweight non-JVM gates and confirming gitleaks had already scanned the diff clean in the failed hooked attempt's own log. One push in this plan (the final docs-only commit, which happened to touch `scripts/verify-public-dashboards.py`, outside `deploy.yml`'s `paths-ignore`) triggered the real CI/CD-with-Docker workflow server-side — it passed green, independently confirming no Java-level defect was let through by any of the skips. Full diagnostic sequence and the accepted fallback pattern are recorded in `docs/SESSION_LESSONS.md` lesson 9.

## Known Stubs

None — all three dashboards render real, live data (verified per-panel), and both public-share links plus the unlinked third are all confirmed 200 with real panel data.

## Threat Flags

| Flag | File | Description |
|------|------|--------------|
| threat_flag: resolved | `k8s/platform/edge/ingressroute-monitoring.yaml` | 13-06's own threat flag (a new public TLS-terminating route added mid-plan with a 503-only placeholder backend) is now resolved: `k8s/monitoring/configs/ingressroute.yaml` is the real, reconciled IngressRoute pointing at the live Grafana Service, covered by this plan's own threat register (T-13-34 through T-13-37). The 13-06 placeholder file itself is superseded by Flux's server-side apply of the same object names from this plan's manifests, not a separate live resource. |

## Next Phase Readiness

- D-07/D-10/D-17/D-18 fully closed: observability is live, complete, and gated by a passing `verify-public-dashboards.py` against the new Grafana.
- 13-09 (restart-ladder memory measurement) can now measure the full kube-prometheus-stack + Loki + Alloy + postgres-exporter footprint under real production load — every `resources` block in this phase's manifests still carries its `PROVISIONAL (restart-ladder in 13-09)` label, unchanged by this plan.
- 13-10's D-08 24h zero-OOM/zero-restart gate can now run against the complete stack (D-17's own stated purpose for this plan's placement).
- Compose is still stopped but not deleted (D-04 gate is 13-10) — this plan touched nothing Compose-related.
- One out-of-scope, pre-existing gap noted but not fixed here: `scripts/verify-deploy-scp-coverage.py`'s stale `deploy-to-netcup` job reference (removed by 13-06, D-14/D-15) was already fixed by the coordinator directly (`9b0d12d`, landed on `main` between this plan's own commits) before this plan needed to touch it.

## Self-Check: PASSED

- `k8s/monitoring/configs/dashboards/node-exporter-full.json` exists: FOUND
- `docs/INFRA_RUNBOOK.md` exists: FOUND
- `docs/SESSION_LESSONS.md` exists: FOUND
- `.planning/todos/completed/2026-09-08-grafana-admin-password-drift-from-env-prod.md` exists: FOUND
- `.planning/todos/completed/2026-09-07-rotate-grafana-viewer-password-leaked-in-session.md` exists: FOUND
- Commit `3a23906` exists in `git log --oneline --all`: FOUND
- Commit `769c678` exists: FOUND
- Commit `bc8dc36` exists: FOUND
- Commit `0b3551d` exists: FOUND
- Commit `717fdac` exists: FOUND
- Commit `1460e3a` exists: FOUND
- Commit `861f3ae` exists: FOUND
- Commit `6eacda1` exists: FOUND
- Live public health check over production LE cert: FOUND (re-checked at documentation time)
- All 3 public dashboard shares 200 with real panel data: FOUND
- CI/CD with Docker run (real Java build/test) green on the one push that touched `scripts/`: FOUND

---
*Phase: 13-introduce-kubernetes*
*Completed: 2026-09-26*
