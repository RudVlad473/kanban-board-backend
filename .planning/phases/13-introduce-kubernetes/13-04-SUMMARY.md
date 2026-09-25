---
phase: 13-introduce-kubernetes
plan: 04
subsystem: infra
tags: [kubernetes, k3s, traefik, cert-manager, postgres, statefulset, flux, kustomize]

# Dependency graph
requires:
  - phase: 13-01
    provides: "k8s/base/app + k8s/base/redpanda + k8s/overlays/nonprod Kustomize shape, verify-k8s-manifests.sh, verify-k8s-invariants.py (I1-I8), sortable image tag scheme"
provides:
  - "k8s/overlays/prod/ -- production app+Redpanda+TLS route+rate-limit Middlewares, gated inert until 13-06's maintenance window"
  - "k8s/data/postgres/ -- final in-cluster Postgres StatefulSet, pinned Service (clusterIP 10.43.54.32, matching 13-02's interim bridge), roles-before-restore init scripts, least-privilege NetworkPolicy"
  - "k8s/platform/cert-manager/, k8s/platform/edge/ -- cert-manager HelmRelease, staging/production ClusterIssuers, monitoring Certificate"
  - "scripts/cutover/row-counts.sql -- D-08.2 pre/post-migration row-count parity check"
  - "verify-k8s-invariants.py I9 (postgres init-script byte identity) and I10 (no redirect route matches the ACME challenge path), both with paired selftest fixtures"
affects: [13-06]

# Actuals (#2632)
actuals:
  tokens: 11537
  tasks: 3
  commits: 4
plan_head_before: 22ee2f623f1b9725ed58570a8b43791e7daa47a9

# Tech tracking
tech-stack:
  added: [cert-manager v1.21.1 (Flux HelmRelease)]
  patterns:
    - "Two separate JSON6902/strategic-merge patch files per overlay target (patches.yaml for the app Deployment, patches-redpanda.yaml for the redpanda StatefulSet) -- one shared file would misapply each target's own patch shape to the other target, per 13-01's own established Kustomize constraint"
    - "IngressRoute Middleware naming convention: auth-rate-limit / general-rate-limit / redirect-https, each carrying a PROVISIONAL comment naming the Caddy zone it translates and the plan (13-08) that re-derives it empirically"
    - "I10's ACME-exclusion invariant evaluated per rendered root (not globally) so Middleware<->IngressRoute cross-object lookups stay scoped to one root, matching how each hostname's redirect route lands in a different overlay/root over the phase"

key-files:
  created:
    - k8s/overlays/prod/kustomization.yaml
    - k8s/overlays/prod/patches.yaml
    - k8s/overlays/prod/patches-redpanda.yaml
    - k8s/overlays/prod/ingressroute.yaml
    - k8s/overlays/prod/certificate.yaml
    - k8s/data/postgres/kustomization.yaml
    - k8s/data/postgres/postgres.yaml
    - k8s/data/postgres/networkpolicy.yaml
    - k8s/data/postgres/init/01-create-databases-and-roles.sh
    - k8s/data/postgres/init/02-create-monitoring-role.sh
    - k8s/platform/cert-manager/kustomization.yaml
    - k8s/platform/cert-manager/cert-manager.yaml
    - k8s/platform/edge/kustomization.yaml
    - k8s/platform/edge/clusterissuers.yaml
    - k8s/platform/edge/certificate-monitoring.yaml
    - scripts/cutover/row-counts.sql
  modified:
    - scripts/verify-k8s-invariants.py
    - scripts/verify-k8s-invariants-selftest.py
    - scripts/verify-postgres-init-quoting.sh

key-decisions:
  - "Split the plan's single-named k8s/overlays/prod/patches.yaml into patches.yaml (app Deployment target) + patches-redpanda.yaml (redpanda StatefulSet target) -- a single shared file pointed at by two different `patches:` list entries would apply that file's ENTIRE content (both the app's strategic-merge resources block and redpanda's JSON6902 --memory patch) against each target, which is structurally wrong. Matches 13-01's own established constraint for the exact same reason (its SUMMARY documents Kustomize's JSON6902 target: must live in kustomization.yaml's own patches: list entry)."
  - "cert-manager chart pinned to v1.21.1 -- confirmed via cert-manager.io's own release notes (fetched live) as a real, current v1.x patch release as of this plan's write date (2026-09-25), matching D-12's never-latest pinning discipline extended to this new third-party chart."
  - "Postgres requests set to 128Mi (rounded up from Plan 11-08's anon+shmem irreclaimable-memory measurement of ~84MiB, not the raw cgroup total which saturates under I/O regardless of actual risk) rather than from the flatter peak-RSS figure Plan 11-03 originally measured -- the anon+shmem figure is what the Compose comment itself identifies as the only memory that can actually force an OOM-kill."
  - "verify-postgres-init-quoting.sh extended (not duplicated) to supply MONITORING_DB_PASS and assert the monitoring role's own auth + pg_monitor membership, conditional on the init dir under test actually carrying a 02 script -- keeps the harness usable standalone against docker/postgres-init (no 02 script there) while covering the new script's injection-safety."

requirements-completed: [D-05, D-06, D-08, D-09, D-11, D-13]

coverage:
  - id: D1
    description: "Prod overlay renders app + Redpanda + TLS route (websecure, app-tls) + rate-limit Middlewares (auth-rate-limit on /api/signin+/api/signup, general-rate-limit elsewhere) scoped to the prod hostname only; nonprod carries no rate limit"
    requirement: D-13
    verification:
      - kind: other
        ref: "bash scripts/verify-k8s-manifests.sh (kubeconform -strict, 10/10 objects valid)"
        status: pass
      - kind: other
        ref: "python3 scripts/verify-k8s-invariants.py"
        status: pass
      - kind: other
        ref: "Task 1's own <verify> Python assertion block (redpanda --memory/limit, app image/limit, rate-limit scoping, TLS/entrypoint, host-match, nonprod-carries-no-rateLimit)"
        status: pass
    human_judgment: false
  - id: D2
    description: "No HTTP->HTTPS redirect route can match /.well-known/acme-challenge/ -- I10 invariant added with selftest, fires on the real tree when deliberately broken and re-verified clean once restored"
    requirement: D-13
    verification:
      - kind: unit
        ref: "scripts/verify-k8s-invariants-selftest.py (I10 fixtures: missing negation fires, negation present is clean, undefined-Middleware web route fails closed)"
        status: pass
      - kind: other
        ref: "Manual real-tree proof: stripped the negation from k8s/overlays/prod/ingressroute.yaml's app-http route, confirmed verify-k8s-invariants.py reports FAIL: I10, restored and confirmed clean"
        status: pass
    human_judgment: false
  - id: D3
    description: "Postgres 16 StatefulSet on local-path with Compose-measured flags/memory cap; roles (incl. new monitoring role) created before any restore; init scripts byte-identical to the Compose source (I9)"
    requirement: D-05
    verification:
      - kind: other
        ref: "cmp docker/postgres-init/01-create-databases-and-roles.sh k8s/data/postgres/init/01-create-databases-and-roles.sh"
        status: pass
      - kind: other
        ref: "bash scripts/verify-postgres-init-quoting.sh --init-dir k8s/data/postgres/init (9/9 assertions, incl. monitoring role auth + pg_monitor membership, hostile-credential cases)"
        status: pass
      - kind: other
        ref: "Task 2's own <verify> Python assertion block (Service clusterIP/port, -c flags, image/limit)"
        status: pass
    human_judgment: false
  - id: D4
    description: "NetworkPolicy admits TCP 5432 to Postgres only from kanban-prod, kanban-nonprod and the labelled exporter pod in monitoring (AND-combined selector, exactly two peers)"
    requirement: D-11
    verification:
      - kind: other
        ref: "Task 2's own <verify> Python assertion block (NetworkPolicy peer/port shape: exactly 2 peers, 1 port, exporter peer AND-combined)"
        status: pass
    human_judgment: false
  - id: D5
    description: "cert-manager + two ClusterIssuers exist (staging/production), every Certificate starts on staging; monitoring hostname has its own Certificate so all three hostnames can validate in 13-06"
    requirement: D-13
    verification:
      - kind: other
        ref: "Task 3's own <verify> Python assertion block (issuer server URLs, no email field, traefik http01 solver, monitoring Certificate namespace/secret/issuer, chart pinned to exact v1.x.y)"
        status: pass
      - kind: other
        ref: "bash scripts/verify-k8s-manifests.sh (kubeconform -strict, 7/7 roots incl. the two new ones)"
        status: pass
    human_judgment: false
  - id: D6
    description: "A committed SQL file produces diff-able row counts for users, boards, columns, tasks, subtasks, activity_log"
    requirement: D-08
    verification:
      - kind: other
        ref: "Against a scratch postgres:16 carrying all 9 real Flyway migrations (V1-V9, applied individually), scripts/cutover/row-counts.sql printed exactly six table|0 lines in sorted order"
        status: pass
    human_judgment: false
  - id: D7
    description: "Kustomize base/overlay layout used for own workloads (D-09); third-party chart (cert-manager) declared via Flux HelmRepository/HelmRelease, no helm binary needed in CI"
    requirement: D-09
    verification:
      - kind: other
        ref: "bash scripts/verify-k8s-manifests.sh -- all 7 kustomization roots, including the new k8s/platform/cert-manager and k8s/platform/edge, render and validate with the same pinned client-side kubectl+kubeconform (no helm invocation in the default validation path)"
        status: pass
    human_judgment: false

duration: 46min
completed: 2026-09-25
status: complete
---

# Phase 13 Plan 04: Production overlay, final Postgres, cert-manager and issuers Summary

**Production-ready but deliberately inert k8s manifests -- prod overlay (app+Redpanda+TLS+rate-limit Middlewares), final Postgres StatefulSet with roles-before-restore init scripts, cert-manager+ClusterIssuers+monitoring Certificate, and the D-08.2 row-count parity SQL -- validated end to end but never applied to a live cluster.**

## Performance

- **Duration:** 46 min
- **Started:** 2026-09-25T13:43:00Z (approx, from Task 1's first Write)
- **Completed:** 2026-09-25T14:29:12Z
- **Tasks:** 3/3 complete
- **Files created:** 16
- **Files modified:** 3

## Accomplishments

- `k8s/overlays/prod/`: complete production overlay -- app Deployment + Redpanda StatefulSet (measured resource requests: app 512Mi/3Gi, redpanda 384Mi/2200Mi with `--memory 2G`), three Traefik Middlewares (auth-rate-limit 20/5m, general-rate-limit 120/1m, redirect-https), IngressRoutes `app-https` (websecure, TLS `app-tls`, signin/signup routed through the auth-rate-limit Middleware) and `app-http` (web, single redirect route excluding the ACME challenge path by construction), Certificate `app` on `letsencrypt-staging`
- `k8s/data/postgres/`: StatefulSet `postgres` on `postgres:16` carrying the Compose `-c` flags literally (`shared_buffers=64MB work_mem=4MB max_connections=25 shared_preload_libraries=pg_stat_statements`), Service `postgres` pinned to clusterIP `10.43.54.32` (matching 13-02's interim bridge so the 13-06 path switch updates clients in place), headless `postgres-headless` companion, requests 128Mi/limits 256Mi (measured from the 11-08 anon+shmem irreclaimable-memory figure), local-path 5Gi PVC; `init/01` byte-identical to the Compose script (I9), new `init/02-create-monitoring-role.sh` creates the monitoring role (LOGIN, CONNECT on both DBs, `pg_monitor`) before any restore on the empty PVC's first boot; `NetworkPolicy postgres-ingress` admits TCP 5432 from exactly the two app namespaces plus one AND-combined exporter-pod peer in monitoring
- `k8s/platform/cert-manager/` + `k8s/platform/edge/`: Flux `HelmRepository`/`HelmRelease` for cert-manager pinned to v1.21.1, two `ClusterIssuer`s (staging/production, no `email` field, traefik http01 solver), `Certificate grafana` in namespace `monitoring` on `letsencrypt-staging` so the monitoring hostname can validate in 13-06 before Grafana itself exists in-cluster
- `scripts/cutover/row-counts.sql`: diff-able row-count snapshot over `users`/`boards`/`columns`/`tasks`/`subtasks`/`activity_log`, proven against a real scratch `postgres:16` carrying all 9 real Flyway migrations
- `verify-k8s-invariants.py`: two new invariants, I9 (postgres init-script byte identity against the Compose source) and I10 (no redirect route can ever match the ACME challenge path, fails closed on an undefined Middleware reference), both proven RED->GREEN via the selftest AND manually confirmed to fire against the real tree (not just fixtures) before being restored clean
- `scripts/verify-postgres-init-quoting.sh`: extended to cover the new monitoring role's injection-safety, conditional on the init dir under test actually carrying a `02` script

## Task Commits

Each task was committed atomically:

1. **Task 1: Tracer -- prod overlay rendered end to end** - `bf57bc1` (feat, `--no-verify`)
2. **Task 2a: I9/I10 invariants + selftest + quoting-harness extension (TDD)** - `bc186a6` (test, `--no-verify`)
2. **Task 2b: Final Postgres tier manifests** - `bcc78c7` (feat, `--no-verify`)
3. **Task 3: cert-manager, ClusterIssuers, monitoring Certificate, row-count SQL** - `cc69e1d` (feat, `--no-verify`)

**Plan metadata:** (this commit)

**Measured:** `git rev-list --count 22ee2f6..HEAD` = 4 commits, matching the table above.

## Files Created/Modified

- `k8s/overlays/prod/kustomization.yaml` - Namespace kanban-prod, app+redpanda base, Flux setter marker for the prod ImagePolicy
- `k8s/overlays/prod/patches.yaml` - App container/initContainer measured resources
- `k8s/overlays/prod/patches-redpanda.yaml` - Redpanda measured resources + `--memory 2G` JSON6902 patch
- `k8s/overlays/prod/ingressroute.yaml` - Three Middlewares + two IngressRoutes (websecure/web)
- `k8s/overlays/prod/certificate.yaml` - Certificate `app` on `letsencrypt-staging`
- `k8s/data/postgres/kustomization.yaml` - Namespace kanban-data, configMapGenerator over init scripts
- `k8s/data/postgres/postgres.yaml` - Services + StatefulSet
- `k8s/data/postgres/networkpolicy.yaml` - `postgres-ingress` NetworkPolicy
- `k8s/data/postgres/init/01-create-databases-and-roles.sh` - Byte-identical copy of the Compose script
- `k8s/data/postgres/init/02-create-monitoring-role.sh` - New monitoring-role init script
- `k8s/platform/cert-manager/kustomization.yaml` - cert-manager root
- `k8s/platform/cert-manager/cert-manager.yaml` - HelmRepository + HelmRelease
- `k8s/platform/edge/kustomization.yaml` - edge root
- `k8s/platform/edge/clusterissuers.yaml` - Two ClusterIssuers
- `k8s/platform/edge/certificate-monitoring.yaml` - Certificate `grafana`
- `scripts/cutover/row-counts.sql` - D-08.2 row-count snapshot
- `scripts/verify-k8s-invariants.py` - I9, I10 added
- `scripts/verify-k8s-invariants-selftest.py` - I9, I10 fixtures added
- `scripts/verify-postgres-init-quoting.sh` - Extended for the monitoring role

## Decisions Made

- **Split the single-named `patches.yaml` into two files** (`patches.yaml` + `patches-redpanda.yaml`) -- a Rule 1 auto-fix, not optional: a single shared file pointed at by two different `patches:` list entries would apply that file's entire content against each target, which Kustomize's own JSON6902/strategic-merge semantics do not support. Matches 13-01's own established, API-mandated pattern.
- **cert-manager pinned to v1.21.1** -- confirmed live via cert-manager.io's own release notes as a real current v1.x patch release, not guessed.
- **Postgres requests derived from the anon+shmem irreclaimable-memory figure (~84MiB -> 128Mi)**, not the flatter raw-RSS figure -- matches what the Compose comment itself identifies as the number that actually predicts OOM risk.
- **Extended, not duplicated, the quoting harness** to cover the new monitoring-role script, conditional on the script's presence in the init dir under test, per the plan's own explicit authorization ("Extend its assertions only if it cannot see the 02 script's role").

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Split `k8s/overlays/prod/patches.yaml` into two files**
- **Found during:** Task 1
- **Issue:** The plan named a single `patches.yaml` covering both the app Deployment's strategic-merge resources patch and the redpanda StatefulSet's JSON6902 `--memory` patch. A single file referenced by two `patches:` list entries would apply that file's entire content against each target, misapplying each target's own patch shape to the other -- exactly the Kustomize constraint 13-01's own SUMMARY documented and fixed the same way.
- **Fix:** Split into `patches.yaml` (app target) and `patches-redpanda.yaml` (redpanda target), each referenced by its own `patches:` list entry.
- **Files modified:** `k8s/overlays/prod/kustomization.yaml`, `k8s/overlays/prod/patches.yaml`, `k8s/overlays/prod/patches-redpanda.yaml`
- **Verification:** `kubectl kustomize k8s/overlays/prod` renders 10 valid objects; `bash scripts/verify-k8s-manifests.sh` passes.
- **Committed in:** `bf57bc1`

**2. [Rule 3 - Blocking] Extended `verify-postgres-init-quoting.sh` to supply `MONITORING_DB_PASS`**
- **Found during:** Task 2
- **Issue:** `02-create-monitoring-role.sh` requires `MONITORING_DB_PASS`; the harness only supplied 01's own six variables, so the container exited before becoming ready when run against the new init dir.
- **Fix:** Added `MONITORING_PASS` to the harness's fixed benign values, passed as `-e MONITORING_DB_PASS` unconditionally (a no-op when the init dir under test has no 02 script), and added assertions for the monitoring role's own auth + `pg_monitor` membership, gated on the script's presence.
- **Files modified:** `scripts/verify-postgres-init-quoting.sh`
- **Verification:** Passes against `k8s/data/postgres/init` (9/9, including the new assertions) and against the original `docker/postgres-init` (7/7, no regression -- the new assertions are skipped there since it has no 02 script).
- **Committed in:** `bc186a6`

---

**Total deviations:** 2 auto-fixed (1 bug fix, 1 blocking-issue extension explicitly pre-authorized by the plan's own action text).
**Impact on plan:** No scope creep. Both were necessary for correctness -- one to make the overlay render at all, the other to make the plan's own explicitly-authorized harness extension actually work.

### Environmental: 4 pre-commit-gate bypasses (`--no-verify`), user-authorized per-plan

**Root cause:** the same standing host memory-pressure condition documented in 13-01's SUMMARY (a resident non-Claude Node process holding ~3.5-4Gi permanently on this shared box) recurred identically on this plan's first commit attempt -- `Gradle build daemon has been stopped: stop command received`, confirmed via `free -h` at failure time (778Mi free, 2.9Gi available, 636Mi swap already in use). Reported to the orchestrator as a `checkpoint:human-action` after 2 consecutive failed attempts on the normal hook (per the dispatch instructions' own budget); the coordinator authorized `--no-verify` scoped to this plan (13-04) only, extending automatically to any recurrence of the identical signature for this plan's remaining commits, with a requirement to independently verify each bypassed commit via the plan's own `<verify>` assertions before committing and to document the bypass in each commit body.

**What was bypassed and what was independently verified in its place, per commit:**

1. **`bf57bc1` (Task 1)** -- 2 consecutive attempts via the normal hook, identical `spotlessCheck`-stage daemon-killed signature. Diff is YAML-only. `bash scripts/verify-k8s-manifests.sh` (10/10 objects valid), `python3 scripts/verify-k8s-invariants.py` (clean), and Task 1's own `<verify>` Python assertions (`TASK 1 PASS`) all passed before commit.
2. **`bc186a6` (Task 2, test)** -- same authorization extended (recurrence of the identical signature). Diff is Python/shell-only, no Java touched. `verify-k8s-invariants-selftest.py`, `verify-k8s-invariants.py`, `verify-k8s-manifests.sh`, and the quoting harness against both init directories all passed before commit.
3. **`bcc78c7` (Task 2, feat)** -- same authorization. Diff is YAML/shell-only. `cmp` byte-identity, the quoting harness (9/9), `verify-k8s-manifests.sh`, `verify-k8s-invariants.py`, and Task 2's own `<verify>` assertions (`TASK 2 PASS`) all passed before commit.
4. **`cc69e1d` (Task 3)** -- same authorization. Diff is YAML/SQL-only. `verify-k8s-manifests.sh` (7/7 roots), `verify-k8s-invariants.py`, Task 3's own `<verify>` assertions (`TASK 3 PASS`), and the real scratch-Postgres row-count check all passed before commit.

**This was NOT a standing practice** -- authorization was scoped explicitly to plan 13-04 by the coordinator, with the same conditions 13-01 established (independent pre-commit verification, per-commit documentation, immediate stop on any REAL failure found in place of the bypassed gate). No REAL (non-environmental) failure was found by any of the independent verification steps above at any point in this plan.

---

**Total deviations (incl. environmental):** 2 auto-fixed implementation corrections + 4 environmentally-bypassed pre-commit gates (user-authorized, independently verified each time).
**Impact on plan:** No scope creep, no unverified work landed. All four bypassed gates had a genuine, passing substitute check run immediately beforehand.

## Issues Encountered

- **Sandbox command-complexity refusals.** Several compound Bash invocations (a multi-line heredoc piping into `kubectl kustomize`, a `git -C` redirect, writes to the worktree's own `.git/worktrees/<name>/` metadata directory) were refused by this environment's own sandboxing as "too complex to verify" or "outside the worktree." Worked around by splitting into simpler single-purpose commands, writing Python assertions to a temp `.dev/verify_taskN.py` file and running it directly (deleted after each use), and skipping the cwd-drift-sentinel/commit-ledger writes entirely (the sandbox refuses writes to `.git/worktrees/<name>/` even though that path is the correct per-worktree git-dir) -- commit counts for this SUMMARY were instead measured directly via `git rev-list --count 22ee2f6..HEAD` against the plan's own recorded base SHA.
- Everything else proceeded per the deviation-handling protocol above; no unresolved technical issue remains.

## User Setup Required

None - no external service configuration required. Nothing in this plan touches a live cluster or the production VPS; every manifest stays inert (unreconciled) until 13-06's maintenance window, per the plan's own objective.

## Next Phase Readiness

- Every production-side manifest the 13-06 maintenance window needs now exists, is validated (kubeconform -strict, I1-I10), and is gated inert: the window only needs to flip `suspend`/`path`/`issuerRef` fields, not author anything new.
- `k8s/data/postgres/`'s Service clusterIP (`10.43.54.32`) is pinned to match 13-02's interim `postgres-bridge` Service by design -- this plan could not verify that match directly in this worktree (13-02 has not landed on this branch's parallel wave), but the verify block's own conditional handles that absence gracefully; the match should be confirmed once both plans merge.
- I9/I10 now protect every future redirect route (monitoring's own route lands in 13-03, nonprod's in 13-06) and the postgres init-script copy from silent drift, not just this plan's own additions.

---
*Phase: 13-introduce-kubernetes*
*Completed: 2026-09-25*
