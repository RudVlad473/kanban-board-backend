# Nonprod on k3s — Plan 13-05 (2026-09-25)

Nonprod's public hostname is served entirely by k3s now: Caddy (Compose) proxies to Traefik's
NodePort, Traefik routes to the `app` Deployment in `kanban-nonprod`, and the Compose
`kanban-nonprod-app`/`kanban-nonprod-redpanda` containers are stopped (not removed — see
Rollback below). This section is the evidence trail for D-02's precondition on production: one
full GitOps cycle proven end to end, kubectl-only health evidence per D-17 (no in-cluster
Prometheus exists until Plan 13-07), and a measured interim memory budget.

## 1. Cutover sequence and rpk evidence

Executed in D-06 order, over `ssh netcup-prod`:

1. `docker compose -p kanban-board-nonprod stop app-nonprod`.
2. Drain check before stopping the broker — both passed, captured live:
   - `rpk group describe activity-log`: every partition LAG **0**.
   - `rpk topic describe kanban.activity.dlt -p`: every partition's HIGH-WATERMARK equalled its
     LOG-START-OFFSET (empty DLT).
3. `docker compose -p kanban-board-nonprod stop redpanda-nonprod` (stop, not `down` — volumes
   stay until Plan 13-10 per D-04).
4. `apps-nonprod` Flux Kustomization unsuspended (commit `a94f6a8`); force-reconciled. Waited for
   `redpanda-0` Ready, `register-schemas` init container exit code 0 (Avro subjects registered
   against `redpanda:8081` — StatefulSet-DNS form, per 13-RESEARCH.md Pitfall 4), and `app` Ready.
5. Caddy's nonprod upstream repointed to `172.17.0.1:30080` (the docker0 gateway, where
   kube-proxy answers Traefik's NodePort) and the nonprod SSH deploy/health-check/cleanup-on-
   failure jobs removed from `deploy.yml` (commit `1cc9fcb`).

**Caddy-reload bug, found and fixed same day.** `deploy.yml`'s `caddy reload` step (introduced
quick task 260903-dvp) silently reloaded a *stale* Caddyfile after this plan's Commit B: the
`appleboy/scp-action` deploy step replaces the file on the VM via rename (a new inode), but
Docker's bind mount was resolved once at container start and kept pointing at the old inode —
`caddy reload` inside the running container re-read the old content even though the host file was
correct, and `deploy-to-netcup` still reported green throughout. This caused a real ~2 minute
public 502 on the nonprod hostname. Root-caused live (differing md5sum/inode/mtime between the
host file and `docker exec cat`), worked around immediately with a manual
`docker compose up -d --force-recreate caddy` on the VM, then fixed structurally in commit
`37643d0`: `deploy.yml` now runs `up -d --force-recreate caddy` instead of `exec caddy reload`,
which discards the container and its resolved bind mount together — this sidesteps the inode
problem rather than retrying around it, and shifts the failure mode from fail-closed-but-silently-
stale to fail-loud. See `docs/INFRA_ARCHITECTURE.md`'s Caddy-reload description for the updated
mechanism. This bug is why the GitOps cycle proven in §2 below happened to be triggered by a
`docs`/`deploy.yml` fix commit rather than an application change — the cycle itself is identical
either way, since Flux reacts to any new image tag regardless of what changed in the commit that
built it.

Public nonprod health after cutover: `https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health`
returns `{"status":"UP","groups":["liveness","readiness"]}`.

## 2. GitOps cycle proof (D-02)

One complete push → build → Flux commit → reconcile → Ready cycle, every SHA and timestamp
captured live:

| Step | Value |
|---|---|
| Trigger push | `37643d0` ("caddy-reload-inode-bug" fix), authored 2026-09-25T18:16:26Z |
| CI build run | `deploy.yml` run `36172424477`, conclusion `success`, created 2026-09-25T18:17:00Z, finished 2026-09-25T18:25:38Z; built and pushed `rudenkovladimir/kanban-board-backend-nonprod:main-135-37643d0` |
| Flux bump commit | `3ccb95e`, author `fluxcdbot`, `chore(flux): bump images`, 2026-09-25T18:29:38Z — sets the nonprod overlay's `newTag` to `main-135-37643d0` and nothing else |
| No CI run for the Flux commit | `gh run list --workflow deploy.yml --commit 3ccb95e...` → empty, confirming the `k8s/**` paths-ignore held and no rebuild loop occurred |
| Running pod | `app-6bb4d7f9fb-bntz7`, image `rudenkovladimir/kanban-board-backend-nonprod:main-135-37643d0`, `Ready=true`, `restartCount=0`, started 2026-09-25T18:31:19Z |
| Public health | UP, verified after the rollout completed |

This is the same mechanism this plan's own Commit B exercised earlier in the day (build
`main-134-1cc9fcb` → Flux commit `34094ca` → reconcile), captured a second time here because the
caddy-reload fix commit happened to land during Task 3's own execution window and produced a
fresh, independently-verifiable cycle rather than requiring a synthetic trigger commit.

## 3. kubectl health evidence (D-17)

No in-cluster Prometheus exists yet (that lands in Plan 13-07), so this evidence is entirely
`kubectl`-based, collected at least 30 minutes after the rollout in §2:

- `k3s kubectl get pods -A` — every `kanban-nonprod` pod (`app`, `redpanda-0`) at `restartCount=0`.
- `k3s kubectl get events -A --field-selector reason=OOMKilling` — empty.
- `k3s kubectl describe pod` for `app` and `redpanda-0` — no `lastState.terminated` on either.
- `dmesg` — no `Memory cgroup out of memory` event with a boot-relative offset later than the
  13-02 incident's own last kill (raw uptime offset ~91796s after boot). **Caveat, recorded because
  it nearly produced a false positive:** `dmesg -T`'s human-readable timestamp on this host is
  offset by exactly +2h from the correct UTC time for events logged before the current boot's
  NTP sync stabilized — the same boot-relative-timestamp class of bug already documented for the
  Netcup SCP console (see this runbook's Triage section). Reading `dmesg -T` naively made the
  already-resolved 13-02 cadvisor OOM incident (real time ~16:40-16:51 UTC, `mem_limit` raised
  128m→256m, documented above) appear to be a brand-new incident at ~18:40-18:51 UTC. Cross-checked
  against `dmesg`'s raw kernel-uptime offsets and `uptime -s`'s boot time — same nine kill lines,
  same event, no new OOM since 13-02.

All four checks pass: zero restarts, zero OOMKilling events, no lastState termination, no new OOM
since 13-02.

## 4. Interim memory table, projected final budget, and verdict (A1)

Live host figures at measurement time: **MemAvailable 3799 MiB** / 7945 MiB total, no swap
(`grep MemAvailable /proc/meminfo`). k3s's own systemd cgroup (the whole single-node cluster,
every pod included) — `MemoryCurrent` 2803 MiB, `MemoryPeak` 3490 MiB
(`systemctl show k3s -p MemoryCurrent -p MemoryPeak`).

| Component | Measured (MiB) | Source |
|---|---|---|
| k3s core (server+agent+containerd+kubelet+flannel binaries only — CoreDNS/local-path-provisioner counted separately below; residual after subtracting every measured child pod from the systemd unit total, since they nest inside the same cgroup) | 1625.1 | `systemctl show k3s -p MemoryCurrent` minus the six rows below |
| CoreDNS | 22.7 | pod cgroup `memory.peak` |
| local-path-provisioner | 13.2 | pod cgroup `memory.peak` |
| Traefik | 26.9 | pod cgroup `memory.peak` |
| Flux, 6 controllers combined | 596.4 | pod cgroup `memory.peak`, summed (helm-controller 18.2, image-automation-controller 156.6, image-reflector-controller 35.7, kustomize-controller 191.3, notification-controller 32.7, source-controller 161.8) |
| app (nonprod, now on k3s) | 370.2 | pod cgroup `memory.peak`, app container only (excludes the `register-schemas` init container, measured separately) |
| register-schemas (nonprod init container peak) | 6.6 | pod cgroup `memory.peak`, init container scope |
| redpanda (nonprod, now on k3s) | 148.3 | pod cgroup `memory.peak` |

Replacing 13-RESEARCH.md's Q8 ASSUMED platform rows with these measured ones and recomputing the
projected final steady-state `requests` sum (production still on Compose in this table — the
prod-side and not-yet-deployed observability rows below stay at research's own VERIFIED/CITED
figures, labelled per row, since this plan does not touch them):

| Workload | `requests` (MiB) | Basis |
|---|---|---|
| k3s core (binaries) | 1625.1 | **MEASURED**, this plan (see table above) |
| CoreDNS | 22.7 | **MEASURED**, this plan |
| local-path-provisioner | 13.2 | **MEASURED**, this plan |
| Traefik | 26.9 | **MEASURED**, this plan (research's ASSUMED ~60 MiB was too high) |
| Flux (6 controllers) | 596.4 | **MEASURED**, this plan (research's CITED ~150 MiB was well under actual; matches 13-02's own earlier finding that Flux runs heavier than expected) |
| cert-manager | 80 | CITED range, 13-RESEARCH.md — not yet deployed |
| Prometheus Operator + kube-state-metrics | 130 | ASSUMED, 13-RESEARCH.md — not yet deployed |
| Grafana Alloy | 80 | ASSUMED, 13-RESEARCH.md — not yet deployed |
| Prometheus | 256 | Phase 12 VERIFIED (still on Compose) |
| Grafana | 547 | Phase 12 VERIFIED (still on Compose) |
| node-exporter (DaemonSet) | 20 | Phase 12 VERIFIED (still on Compose) |
| Loki | 94 | Phase 12 VERIFIED (still on Compose) |
| Postgres | 70 | Phase 11 VERIFIED (still on Compose) |
| postgres-exporter | 20 | Phase 11 VERIFIED (still on Compose) |
| Redpanda (prod) | 450 | Phase 11 VERIFIED (still on Compose) |
| Redpanda (nonprod) | 148.3 | **MEASURED**, this plan (k3s cgroup peak) |
| app (prod) | 490 | Phase 11 VERIFIED (still on Compose) |
| app (nonprod) | 370.2 | **MEASURED**, this plan (k3s cgroup peak) |
| **Sum of `requests`** | **5039.9** | |

**Go/no-go arithmetic for 13-06.** MemAvailable-at-full-Compose-stop is a projection, not a
direct measurement, because production's Compose stack is still running: current MemAvailable
(3799 MiB) plus the RSS the remaining 11 Compose containers would free if fully stopped (2021 MiB,
`docker stats --no-stream` summed) ≈ **5820 MiB** projected. Minus the plan's 512 MiB margin =
**5308 MiB threshold**. Projected final `requests` sum (5040 MiB) is **≤** that threshold.

**Verdict: PASS — by 268 MiB.** This is a real but thin margin, not a comfortable one: it depends
on the freed-RSS projection holding (page-cache reclaim and cadvisor's own footprint dropping once
Docker containers vanish are both directionally favorable but not separately verified here), and
on cert-manager/Prometheus-Operator/Alloy's still-ASSUMED figures landing close to their cited
ranges when actually deployed in 13-07. Flux alone already measured ~4x research's original
estimate (596 MiB vs ~150 MiB CITED) — the same could happen to any of the three still-ASSUMED
rows, and each would eat directly into this 268 MiB. **Recommendation for 13-06/13-07: re-measure
this table immediately after cert-manager and the minimal observability stack are actually
installed, before committing to the full Grafana/Loki/Alloy stack's memory footprint, and treat
this PASS as provisional until that re-measurement confirms it** — this input belongs to the D-01
decision, as required.

## 5. Rollback

Compose's nonprod containers (`kanban-nonprod-app`, `kanban-nonprod-redpanda`) are **stopped, not
removed** — their volumes and images are intact. To roll back before Plan 13-06:

1. `git revert` the Caddyfile/`deploy.yml` changes in commit `1cc9fcb` (restores the nonprod SSH
   deploy path and Caddy's direct-to-Compose upstream).
2. `docker compose -p kanban-board-nonprod start redpanda-nonprod app-nonprod`.
3. Re-suspend the `apps-nonprod` Flux Kustomization (`k3s kubectl patch kustomization
   apps-nonprod -n flux-system --type merge -p '{"spec":{"suspend":true}}'`) so Flux stops
   reconciling a cluster nothing is routing to.

Rollback was not exercised live in this plan — the cutover held throughout, so this is the
documented path, not a proven one.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
