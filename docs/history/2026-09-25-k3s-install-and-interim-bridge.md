# k3s install and interim bridge — Plan 13-02 (2026-09-25)

k3s and Flux now run alongside the existing Docker Compose stack on netcup-prod, proven end to
end against the one piece of plumbing the nonprod half of this migration depends on: a k3s pod
reaching Compose Postgres. This section is the live evidence trail — not a restatement of what
the plan intended, but what was actually installed, measured, and found broken along the way.

## 1. What was installed and pinned

| Component | Version | Verification |
|---|---|---|
| k3s | `v1.36.4+k3s1` | `INSTALL_K3S_VERSION` pin; `infra/vm/k3s/install.sh` sha256-verifies its own downloaded copy of `get.k3s.io` before running it -- see `K3S_INSTALLER_SHA256` in that file for the pinned checksum |
| Flux CLI (used to generate `gotk-components.yaml`) | `v2.9.5` | Downloaded and sha256-verified against the release's own published `flux_2.9.5_checksums.txt`; checksum recorded in `k8s/flux-system/gotk-components.yaml`'s own header comment |
| k3s config | `infra/vm/k3s/config.yaml` | `secrets-encryption: true`, `write-kubeconfig-mode: "0600"`, `disable: [servicelb, metrics-server]` |

## 2. Interim topology

Caddy (Compose) remains the only process serving public 80/443 for the whole interim window —
k3s's ServiceLB is disabled at install (`config.yaml`), so nothing in the cluster can claim a
host port even transiently. Traefik (k3s-packaged) is confined to `NodePort 30080`, reachable
only from `127.0.0.1` on the host, never the public interface — DOCKER-USER's `! -i eth0 -j
RETURN` rule structurally prevents any k3s-originated traffic from being exposed on eth0
regardless of what Service type Traefik's own chart renders (see the HelmChartConfig finding
below — the firewall is the real safety boundary here, not the Service type).

A selector-less Service + hand-authored EndpointSlice (`k8s/data/postgres-bridge/`) gives k3s
pods a stable DNS name, `postgres.kanban-data.svc.cluster.local`, for the still-Compose Postgres
container, reached via the `docker0` bridge gateway (`172.17.0.1:5432`) — the interim cross-
runtime bridge from 13-RESEARCH.md's Pattern 1.

## 3. A2 proof output

A throwaway Pod in `kanban-nonprod` (image `postgres:16`, `envFrom: app-env`) ran `psql` as the
nonprod role against both databases through the bridge. Captured log, verbatim:

```
kanban_nonprod
psql: error: connection to server at "postgres.kanban-data.svc.cluster.local" (10.43.54.32), port 5432 failed: FATAL:  permission denied for database "kanban_prod"
DETAIL:  User does not have CONNECT privilege.
A2_OK
```

Reads as: the nonprod role successfully selected `current_database()` against `kanban_nonprod`
(first line), was refused with `permission denied` against `kanban_prod` (second/third lines,
proving Phase 11 D-01 isolation survives the bridge), and the probe script's own `A2_OK` sentinel
printed — confirming both halves of the assertion held in the same run, not just the first one
that happened to succeed. `docker-user-firewall.sh check` passed immediately after; the deploy
that recreated Postgres with the new `172.17.0.1:5432:5432` binding left `RestartCount=0`.

## 4. Measured baseline (A1)

MemAvailable before k3s install (Task 1 pre-flight): **4998 MiB** / 7945 MiB total, no swap.
MemAvailable after k3s + Flux install, cadvisor OOM incident resolved (see §6): **3716 MiB** —
a **~1282 MiB** drop, above the low end of research's projected +0.7–1.0 GiB and within the same
order of magnitude. Gate (≥2048 MiB, no unresolved OOM) — **PASS**.

| Component | RSS/peak | Source command |
|---|---|---|
| k3s (systemd cgroup) | current ~2882 MiB, peak ~3205 MiB | `systemctl show k3s -p MemoryCurrent -p MemoryPeak` |
| helm-controller | ~16.9 MiB (peak) | pod cgroup `memory.peak` |
| image-automation-controller | ~95.0 MiB (peak) | pod cgroup `memory.peak` |
| image-reflector-controller | ~27.6 MiB (peak) | pod cgroup `memory.peak` |
| kustomize-controller | ~157.6 MiB (peak) | pod cgroup `memory.peak` |
| notification-controller | ~31.4 MiB (peak) | pod cgroup `memory.peak` |
| source-controller | ~147.8 MiB (peak) | pod cgroup `memory.peak` |
| coredns | ~19.2 MiB (peak) | pod cgroup `memory.peak` |
| local-path-provisioner | ~11.7 MiB (peak) | pod cgroup `memory.peak` |
| traefik | ~25.1 MiB (peak) | pod cgroup `memory.peak` |

All figures above are MEASURED against pod cgroups under `/sys/fs/cgroup/kubepods.slice/` on the
live host, not projected from research. Flux's six controllers together account for ~476 MiB
peak — noticeably above research's ~250 MiB WebSearch-derived estimate (13-RESEARCH.md
Assumption A1), with `kustomize-controller` and `source-controller` the two largest contributors
(both do the actual git-fetch/render work). Flagged for the interim budget, not treated as a
silent correction to the earlier estimate.

**Go/no-go result: GO.** MemAvailable holds >3.7 GiB with headroom well above the 2048 MiB floor,
and (after the cadvisor fix below) zero unresolved OOM kills against the current cgroup caps.

## 5. Flux operations

- **Suspend/resume a Kustomization:** `k3s kubectl patch kustomization <name> -n flux-system --type merge -p '{"spec":{"suspend":true}}'` (or `false` to resume) — the six later-phase Kustomizations (`apps-nonprod`, `cert-manager`, `edge`, `apps-prod`, `monitoring-controllers`, `monitoring`) ship `suspend: true` in git; a later plan flips this in its own commit, per `k8s/flux-system/*.yaml`'s own comments naming which plan unsuspends it.
- **Force reconcile:** `k3s kubectl annotate <kind> <name> -n flux-system reconcile.fluxcd.io/requestedAt="$(date +%s)" --overwrite` — used live in this plan to force the `platform` Kustomization and the `kanban-images` ImageUpdateAutomation to re-check after a manifest fix, rather than waiting out the 5–10m interval.
- **Rebase before push:** always `git pull --rebase` before pushing a manifest change — Flux's own ImageUpdateAutomation pushes directly to `main` on its own schedule (§6 below), so a stale local branch can silently clobber a bump commit.

## 6. k3s operations primer

- `k3s kubectl get pods -A` / `logs <pod> -n <ns>` / `describe pod <pod> -n <ns>` — same `kubectl` verbs as any cluster; `k3s kubectl` is a thin wrapper (`/usr/local/bin/kubectl` symlinks to it).
- **Service vs Deployment vs StatefulSet vs ConfigMap/Secret:** a Service is a stable virtual IP/DNS name routing to a set of Pods (by label selector, or none — see the postgres-bridge above); a Deployment manages stateless, interchangeable Pod replicas via a ReplicaSet; a StatefulSet manages Pods with stable identity and per-replica storage (ordinal naming, its own PVC per replica); ConfigMap/Secret are the two ways to inject non-code configuration into a Pod, identical in shape, differing only in that a Secret's data is base64-encoded at rest and (with `--secrets-encryption`, on here) encrypted in the datastore.
- **What Recreate does on a rollout:** the `Recreate` strategy (used by this repo's own app Deployment, `k8s/base/app/app.yaml`) terminates every existing Pod before creating any replacement — a brief full outage on every rollout, chosen deliberately over `RollingUpdate` because the app is not (yet) safely runnable at two versions concurrently against one shared Postgres schema mid-migration.

## 7. Rollback note

`/usr/local/bin/k3s-uninstall.sh` (installed by `install.sh` itself) fully removes k3s, including
its own iptables chains (`k3s-uninstall.sh` explicitly tears down the CNI and any `KUBE-*`/
`CNI-*` chains it created) — confirmed by design, not yet exercised live in this plan. Running it
does not touch `DOCKER-USER` or anything Compose manages; `docker-user-firewall.sh check`
continuing to pass afterward would be the expected confirmation.

## 8. Live findings from this plan's own execution

Two real bugs surfaced only once this work ran against the actual host and a live Flux
reconcile — neither was visible from static manifest validation alone, and both are recorded here
because a future session hitting the same symptom should not have to re-diagnose it.

**Traefik HelmChartConfig `service.type: NodePort` did not take effect on first apply.** The
committed `k8s/platform/traefik/helmchartconfig.yaml` sets `service.type: NodePort` in its
`valuesContent`; the Helm release's own recorded `config` correctly showed `"type": "NodePort"`
after the k3s-managed `helm upgrade` ran, but the rendered Service manifest inside that same
release still contained `type: LoadBalancer`, and the live Service object matched the stale
render. Root cause not fully isolated (a `--server-side=auto --force-conflicts=true` Helm
templating/apply quirk in this chart version is the leading candidate; not conclusively proven).
Practical impact was bounded regardless: k3s's ServiceLB is disabled at install, so a
`LoadBalancer`-type Service with no controller to satisfy it never resulted in any svclb pod, any
host port claim, or any firewall exposure — confirmed live (`k3s kubectl get pods -n kube-system
-l svccontroller.k3s.cattle.io/svcname` stayed empty throughout, `docker-user-firewall.sh check`
never drifted). Worked around with a direct `k3s kubectl patch service traefik -n kube-system -p
'{"spec":{"type":"NodePort"}}'`, confirmed stable across a subsequent Flux `platform`
reconcile. **Not yet durable against every possible re-trigger of the underlying helm-install
job** (e.g. a k3s restart, or any future `HelmChartConfig` content change) — flagged as an open
item for 13-06, when Traefik's role expands and this gets re-verified under real load.

**`ImageUpdateAutomation`'s `messageTemplate` used a removed Flux template field.** The committed
manifest used `{{ range .Updated.Images }}`, a field the v1beta2/v1 template model (Flux ≥2.3)
removed in favor of `{{ range .Changed.Changes }}` with `.OldValue`/`.NewValue` — the
`kanban-images` automation reported `Ready=False` (`template uses removed '.Updated' field`) from
the moment it was first applied, never having run successfully at all. Fixed in commit `21a06ca`
against Flux's own current documentation, verified live: force-reconciled, went `Ready=True`
("repository up-to-date"), and immediately produced a real bump commit (`2ce162b`, author
`fluxcdbot`, `chore(flux): bump images`, touching only the two overlays' `newTag` setter markers)
— proving the fix works end to end, not merely that the template now parses. `deploy.yml` did not
run for that commit (confirmed via `gh run list --workflow deploy.yml --commit 2ce162b`), matching
the `k8s/**` paths-ignore this whole scheme depends on to avoid a rebuild loop.

**A live OOM incident, unrelated to k3s's own manifests, surfaced by installing k3s.** Twice
during this plan's live work (`2026-09-25T16:40:14Z` and `2026-09-25T16:44:09Z`), the Docker
Compose `cadvisor` container was OOM-killed against its then-current 128 MiB cap
(`docker-compose.prod.yml`, itself already a doubling from the original 64m floor Plan 12-05's own
ladder measured). Plan 12-05's ladder already documented that cadvisor's memory scales with the
*number of containers it watches*, not with request load — installing k3s added 9 new cgroups
(Flux's six controllers, coredns, local-path-provisioner, traefik) for cadvisor to enumerate,
which is the mechanistically plausible cause, though not independently re-measured rung-by-rung
here. `mem_limit` was raised 128m → 256m in a separate commit (`4817b99`, **made by the
orchestrator, not by this plan's own executor** — outside 13-02's declared `files_modified`, since
it's a Compose-side production fix unrelated to this plan's Kubernetes scope) after operator
authorization; CI ran clean, and the current cadvisor instance (`StartedAt=2026-09-25T16:56:47Z`)
has held `RestartCount=0` and 61–75% memory utilization (158–191 MiB/256 MiB across repeated
checks) since. This is flagged as a genuinely new headroom question for 13-09's restart-ladder
work — the cadvisor cap now needs to account for the full container+pod population once every
later 13-xx plan's workloads land in-cluster, not just today's 13 Compose containers.

No 32-or-more-character hex string appears above beyond commit SHAs (7-40 hex chars, git commit
identifiers — not key material); no secret or private-key content was captured in this section or
anywhere else in this plan's execution (the `app-env` Secret's values were filtered and written
entirely server-side, never read into this session).

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
