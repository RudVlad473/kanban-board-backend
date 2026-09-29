---
task: 260929-fwf
description: Close the two D-08 gate failures from plan 13-10 Task 1 (commit 4729376)
created: 2026-09-29
operator_decision: '"1 and 1" on 2026-09-29 (D-08.3 option 1, D-08.1(b) option 1)'
files_modified:
  - k8s/monitoring/controllers/kube-prometheus-stack.yaml
  - .planning/phases/13-introduce-kubernetes/13-10-PLAN.md
  - docs/INFRA_RUNBOOK.md
autonomous: false
---

# Quick task 260929-fwf

## Why

Plan 13-10 Task 1 evaluated the D-08 gate at T0+46.5h and recorded two FAILs (runbook section
`## D-08 gate — Plan 13-10 (2026-09-29)`, commit `4729376`):

- **D-08.3**: Prometheus was OOM-killed 3x (2026-09-27T11:00, 13:00, 17:00Z, anon-rss ~390MB vs its
  384Mi limit) and node-exporter 2x (2026-09-27T23:51Z, 2026-09-28T13:16Z, ~20MB vs 16Mi). The 13-09
  restart-ladder comments call both "verified clean"; the ladder was shorter than Prometheus's
  2-hourly head-compaction cycle, so it could not see the spike.
- **D-08.1(b)**: `>= 90` uptime-check runs is unreachable — GitHub throttles the `*/15` cron to one
  run per 2-8h (10 runs in ~46h, all success; same cadence back to 2026-09-19).

## Live evidence (read-only, 2026-09-29 ~09:25Z, Prometheus over `netcup-prod`)

- `max_over_time(container_memory_working_set_bytes{container="prometheus"}[3d])`: 431.2 MiB peak
  (other series of the same pod 374-383 MiB). Limit 384Mi.
- Current node-exporter pod `...-s62rw`: 15.8 MiB peak working set. Limit 16Mi.
- Node: 7945 MiB total, 1902 MiB available.

## Approaches considered

| Approach | Pros / Cons | Why picked |
|---|---|---|
| A. Raise both limits from observed peaks (Prometheus 640Mi = peak x1.5, node-exporter 32Mi = the ladder's known-passing shared rung), and treat the restarted 24h D-08.3 window as the verification | Pros: no prod restarts, no VM authorization, the window is exactly the load-bearing test the earlier ladder lacked. Cons: +~256Mi/+16Mi of worst-case node memory on a 1.9GB-available host; a new kill costs another full window | **Picked.** Smallest change that addresses the recorded cause |
| B. Re-run the restart ladder on the live VM to re-derive the caps | Pros: matches D-07's "measured" convention. Cons: needs fresh per-session prod-restart authorization; a ladder is minutes long, so it repeats the exact blind spot (2h compaction) that produced the failure | Rejected: cannot observe the failure mode |
| C. Cut Prometheus's footprint instead (fewer scrape targets / shorter retention) | Pros: no node-memory increase. Cons: silently shrinks observability scope, a design change to Phase 12/13 decisions | Rejected: out of scope, needs its own decision |

## Trade-offs

- **Memory**: the limit is a ceiling, not a reservation; requests move only modestly (Prometheus
  288Mi -> 384Mi, node-exporter 10Mi -> 16Mi), so scheduling headroom shrinks by ~110Mi total while
  worst-case use rises by ~272Mi. Host had 1902 MiB available; watch it in the new window.
- **State invalidation**: changing the HelmRelease values makes Flux upgrade the release, restarting
  Prometheus (a brief scrape gap; TSDB is on a PVC, WAL replay on start) and rolling the node-exporter
  DaemonSet. This is the moment the new T0 starts.
- **Security**: none — no exposure, image or RBAC change.

## Tasks

1. **Edit `kube-prometheus-stack.yaml`**: Prometheus requests 384Mi / limits 640Mi; node-exporter
   requests 16Mi / limits 32Mi. Rewrite the two 13-09 "MEASURED" comments to record the failed
   measurement, the live peaks above, the dated falsifier, and that the D-08.3 window is the
   verification. Verify: `python3 scripts/verify-k8s-invariants.py --no-provisional` and
   `bash scripts/verify-k8s-manifests.sh`.
2. **Amend `13-10-PLAN.md` D-08.1(b)** to: every uptime-check run in [T0, now] concluded success AND
   the count is >= 10, recorded as an operator-approved amendment dated 2026-09-29 with the reason.
   Add a matching dated note under the runbook's D-08 gate section (the recorded FAIL stays as the
   evidence of the old threshold).
3. **Commit** with hooks (no `--no-verify`), staging only these files. Do NOT push.

## Deferred to after the operator-approved push (NOT in this run)

Push -> Flux rollout -> record the new T0 in the runbook + 13-10 plan + STATE.md -> re-run 13-10
Task 1 once `[new T0, new T0 + 24h]` has elapsed.

## Acceptance

- Both invariant/manifest gates green after the edit.
- No file outside `files_modified` staged; nothing pushed; nothing changed on the VM.
