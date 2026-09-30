---
phase: 13-introduce-kubernetes
plan: 06
subsystem: infra
tags: [kubernetes, k3s, flux, cert-manager, traefik, production-cutover, incident, d-01, d-03, d-05, d-06]

# Dependency graph
requires:
  - phase: 13-04
    provides: "prod overlay (app/redpanda/IngressRoutes/Certificate on staging issuer)"
  - phase: 13-05
    provides: "Nonprod fully cut over to k3s, D-02 GitOps cycle proven, interim memory budget PASS by 268 MiB"
provides:
  - "Production fully cut over to k3s: Compose stopped (not deleted), in-cluster Postgres holds the real restored data with verified parity, Traefik + ServiceLB own 80/443, all three hostnames present production Let's Encrypt certificates"
  - "CI retargeted: deploy-to-netcup/register-schemas-production/cleanup-unused-image removed from deploy.yml, flyway-verify(-nonprod) retargeted through a fingerprint-pinned SSH forward to the in-cluster Postgres ClusterIP"
  - "NetworkPolicy live-proven: kanban-prod/kanban-nonprod pods reach Postgres, an unlabelled monitoring pod cannot"
  - "docs/history/2026-09-26-production-cutover-to-k3s.md: full window runbook including a documented operator incident and 5 gaps found and fixed live"
  - "docs/SESSION_LESSONS.md lesson 8: push a staged multi-commit cutover by explicit SHA per gate, never by branch tip"
affects: [13-07, 13-08, 13-09, 13-10]

# Actuals (#2632)
actuals:
  tokens: 23388
  tasks: 3
  commits: 13
plan_head_before: 900ec43d345864dbf8070acbbd47ed50e922d731

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "SSH local port-forward from a GitHub Actions runner, through the VM, to an in-cluster Postgres ClusterIP (host-key pinned by fingerprint via ssh-keyscan + grep -qxF, VM-side sshd Match block restricting AllowTcpForwarding/PermitOpen) -- replaces the old appleboy/*-action-driven Compose-network invocation now that deploys are pull-based GitOps"
    - "k3s ServiceLB hostPort DNAT for LoadBalancer Services opens no listening socket -- verify via iptables -t nat -S CNI-HOSTPORT-DNAT / KUBE-SERVICES chains and an off-box curl, never `ss -ltn` alone"
    - "kubectl run --rm -i races CNI/CoreDNS readiness for a brand-new pod, especially soon after a cluster restart -- add a short sleep before the check, or exec into an already-Running pod instead, before concluding a NetworkPolicy probe genuinely failed"

key-files:
  modified:
    - .github/workflows/deploy.yml
    - k8s/data/postgres/postgres.yaml
    - k8s/flux-system/apps-nonprod.yaml
    - k8s/flux-system/apps-prod.yaml
    - k8s/flux-system/cert-manager.yaml
    - k8s/flux-system/data.yaml
    - k8s/flux-system/edge.yaml
    - k8s/overlays/nonprod/certificate.yaml
    - k8s/overlays/nonprod/ingressroute.yaml
    - k8s/overlays/nonprod/kustomization.yaml
    - k8s/overlays/prod/certificate.yaml
    - k8s/platform/cert-manager/cert-manager.yaml
    - k8s/platform/edge/certificate-monitoring.yaml
    - k8s/platform/edge/kustomization.yaml
    - k8s/platform/traefik/helmchartconfig.yaml
    - infra/vm/k3s/config.yaml
    - infra/vm/README.md
    - docs/SESSION_LESSONS.md
  created:
    - k8s/overlays/nonprod/certificate.yaml
    - k8s/platform/edge/ingressroute-monitoring.yaml
    - infra/vm/sshd/kanban-ci-tunnel.conf
    - docs/history/2026-09-26-production-cutover-to-k3s.md

key-decisions:
  - "Task 1's checkpoint:decision was answered 'proceed' by the operator at 2026-09-26 07:11 UTC, re-verified fresh by the orchestrator immediately before dispatch (AAAA empty on all 3 hostnames, branch protection still 404, no in-flight deploy.yml run, 13-05's PASS-by-268MiB memory verdict) and independently re-verified again by the executor before starting Task 2."
  - "Mid-Task-3, a real operator error (pushing a staged multi-commit branch by tip instead of by per-gate SHA) landed W2/W3/W4 on main simultaneously, before the actual data migration had happened, causing a genuine ~11-minute public 502 outage. The executor stopped immediately on discovering this, gathered evidence, and reported rather than improvising a fix alone -- the operator then explicitly directed 'push forward, do not roll back,' judging that faster than unwinding four already-reconciled commits. See docs/history/2026-09-26-production-cutover-to-k3s.md's 'Incident: premature multi-window push' for the full account."
  - "The in-cluster kanban_prod/kanban_nonprod databases (both holding only an empty, Flyway-created schema at incident-discovery time, confirmed zero real rows) were dropped and recreated under their existing owning roles before the real dump/restore ran -- chosen over pg_restore --clean because the schema had no real data to protect, and a clean recreate is simpler to reason about than reconciling Flyway's own migration-history bookkeeping against a fresh restore."
  - "Three real pre-existing gaps (a readinessProbe using unexpandable $(POSTGRES_USER) syntax, a cert-manager HelmRepository/HelmRelease with no metadata.namespace, and no IngressRoute anywhere in the phase serving grafana-tls for the monitoring hostname) surfaced only once this plan's own manifests reconciled against a live cluster for the first time. All three were fixed in place (Rule 1/Rule 2 auto-fixes) rather than routed around, each with its own dated live-evidence comment in the manifest."
  - "The monitoring hostname's IngressRoute was built as a deliberate placeholder (grafana-placeholder Service with no Endpoints, Traefik answers 503) using the exact object names (grafana, grafana-http) 13-07's own plan text already assumes exist -- not a real Grafana route, which is 13-07's own scope to build."

requirements-completed: [D-01, D-03, D-05, D-06, D-10, D-11, D-13, D-14, D-16]

coverage:
  - id: D1
    description: "Production and nonprod served by k3s: Traefik owns 80/443 via ServiceLB with externalTrafficPolicy Local, all three hostnames present Let's Encrypt production certificates"
    requirement: D-01
    verification:
      - kind: other
        ref: "k3s kubectl get svc traefik -n kube-system -> LoadBalancer/Local; openssl s_client -servername <host> for all 3 hostnames returns C=US, O=Let's Encrypt (no STAGING) issuers, Not-Before timestamps 2026-09-26T07:05:57Z-07:15:39Z"
        status: pass
    human_judgment: false
  - id: D2
    description: "Only one production stack in memory at a time: every Compose container stopped before in-cluster prod workloads started, inside one announced window with start/end/downtime recorded"
    requirement: D-03
    verification:
      - kind: other
        ref: "docs/history/2026-09-26-production-cutover-to-k3s.md Section 1 (T_start 07:46:01Z, T_end 08:45:06Z, ~11min hard 502 documented) and Section 8 (docker ps empty, volumes intact)"
        status: pass
      - kind: other
        ref: "The window's own multi-window-push incident meant apps-prod briefly ran in-cluster BEFORE Compose fully stopped -- a real, documented deviation from the intended step order, though Compose's own data was never at risk (Section 4/Incident account) and every remaining Compose container was stopped before the real data migration completed"
        status: pass
    human_judgment: true
    rationale: "D-03's substance (no side-by-side overlap risking an OOM, one prod stack of record) held throughout, but the LITERAL ordering the plan specified (stop everything, THEN bring up in-cluster prod) was violated by the incident -- a human should read the incident account, not just the PASS line, to judge whether this deviation is acceptable for this project's own risk tolerance."
  - id: D3
    description: "Both databases restored with pg_restore into in-cluster Postgres whose roles were created by initdb before the restore; row counts match the pre-cutover snapshot"
    requirement: D-05
    verification:
      - kind: other
        ref: "docs/history/2026-09-26-production-cutover-to-k3s.md Section 3/4: sha256-verified dumps, both diff(before,after) empty for all 6 tables in both databases; \\du lists kanban_prod_app/kanban_nonprod_app/monitoring, each database owned by its role with CONNECT revoked from PUBLIC"
        status: pass
    human_judgment: false
  - id: D4
    description: "Compose prod broker stopped only after activity-log lag 0 and empty DLT; fresh prod Redpanda got its schemas from the app pod's initContainer"
    requirement: D-06
    verification:
      - kind: other
        ref: "docs/history/2026-09-26-production-cutover-to-k3s.md Section 2: rpk group describe activity-log TOTAL-LAG 0, rpk topic describe kanban.activity.dlt HIGH-WATERMARK=LOG-START-OFFSET=0, both captured before docker compose stop redpanda"
        status: pass
    human_judgment: false
  - id: D5
    description: "No workflow can deploy to or restart Compose: deploy-to-netcup/register-schemas-production/cleanup-unused-image gone; flyway-verify(-nonprod) pass through an SSH forward limited by PermitOpen to the pinned Postgres ClusterIP"
    requirement: D-14
    verification:
      - kind: other
        ref: "python3 -c \"import yaml; jobs=yaml.safe_load(open('.github/workflows/deploy.yml'))['jobs']; print([j for j in ('deploy-to-netcup','register-schemas-production','cleanup-unused-image') if j in jobs])\" -> []; deploy.yml run 36230184883 all jobs green including flyway-verify/flyway-verify-nonprod through the forward"
        status: pass
    human_judgment: false
  - id: D6
    description: "Cutover pg_dump files and pre-cutover counts sit outside any volume, with recorded sha256"
    requirement: D-05
    verification:
      - kind: other
        ref: "sha256sum -c /root/k3s-cutover-20260926/SHA256SUMS -> all 7 files OK, directory mode 0700 under /root, outside any Docker/k3s volume"
        status: pass
    human_judgment: false
  - id: D7
    description: "App pods reach Postgres and a non-exporter pod in monitoring cannot, proving the NetworkPolicy live"
    requirement: D-11
    verification:
      - kind: other
        ref: "kubectl run np-probe-pos-v2 (kanban-prod, unlabelled) -> accepting connections; kubectl run np-probe-neg-v2 (monitoring, unlabelled) -> no response. Both probes needed a 3s startup delay to avoid a genuine pod-startup CNI/DNS race, documented as a gap, not a policy defect."
        status: pass
    human_judgment: false
  - id: D8
    description: "Every HTTP->HTTPS redirect on prod/nonprod excludes /.well-known/acme-challenge/ in its own rule (I10)"
    requirement: D-13
    verification:
      - kind: other
        ref: "curl http://<host>/.well-known/acme-challenge/probe-not-a-token -> 404 (not 3xx) for both hostnames; curl -w '%{redirect_url}' http://<host>/ -> https://<host>/ for both. python3 scripts/verify-k8s-invariants.py (I10) passes on every commit."
        status: pass
    human_judgment: false

duration: ~59min (T_start 07:46:01Z to T_end 08:45:06Z), plus roughly another 30min of post-window fix/documentation work (SSH-forward fingerprint bug found and fixed via a live deploy.yml dispatch, runbook + lesson written) -- includes an unplanned ~11min public outage from a mid-window operator error, documented in full below
completed: 2026-09-26
status: complete
---

# Phase 13 Plan 06: Production cutover to k3s Summary

**Production moved from Docker Compose to k3s in one maintenance window — Traefik/ServiceLB now own the public edge, all three hostnames present real Let's Encrypt production certificates, data migrated with verified zero-diff row-count parity, and CI no longer has any Compose deploy path. The window included a real, fully-documented operator incident (a staged multi-commit branch pushed by tip instead of by per-gate SHA, causing an ~11-minute public 502) that the executor caught, reported, and — on explicit operator direction — recovered forward from without any data loss.**

## Performance

- **Duration:** ~59 min for the live window (T_start 07:46:01Z → T_end 08:45:06Z) plus ~30 min of post-window fix/documentation work
- **Tasks:** 3/3 complete
- **Files modified:** 23 (18 modified, 4 created, 1 new directory `infra/vm/sshd/`)
- **Commits:** 13 — measured via `git rev-list --count 900ec43..819a8b5` (4 plan commits W1-W4, 4 Flux-authored automated tag-bump commits that landed within the measured window, 5 executor-authored fix/docs commits)

## Accomplishments

- **Task 1 (checkpoint:decision) — resolved.** Operator answered "proceed" at 2026-09-26 07:11 UTC after the orchestrator presented 13-05's PASS-by-268MiB memory verdict (explicitly flagged thin/provisional), the D-02 GitOps cycle proof, empty AAAA records on all three hostnames, and 404 branch protection — all re-verified fresh, not from memory. See the Task 1 record below.
- **Task 2 — W1-W4 staged, validated, rehearsed.** All four commits built on `cutover-13-06`, each individually passing `verify-k8s-manifests.sh`/`verify-k8s-invariants.py`, plus the plan's own detailed pin-ordering/ACME-redirect/deploy.yml-shape assertions. The restore rehearsal on the VM (dump both Compose databases, restore into a scratch `postgres:16` container mirroring the future `postgres-init` shape) succeeded with zero extra flags and exact row-count parity.
- **Task 3 — the live window, executed with one real incident, fully recovered.** Steps 0-3 (secrets, app/broker stop, broker-drain evidence) executed cleanly and in order. W1 pushed correctly. A fix commit built on top of W1 was then pushed by branch tip instead of by its own SHA, which silently included the already-staged W2/W3/W4 — see **Deviations** below for the full incident account, which is also documented in `docs/history/2026-09-26-production-cutover-to-k3s.md`. On explicit operator direction to push forward rather than roll back, the actual data migration was completed for real (drop/recreate the empty in-cluster schemas, dump Compose's still-fully-intact production data, restore with verified zero-diff parity), the remaining Compose containers were stopped, ServiceLB was re-enabled to complete the edge takeover, and every remaining window step (certificate validation on all three hostnames, CI retarget proof via a live `deploy.yml` dispatch, NetworkPolicy positive/negative proof) was completed and verified.
- **Five real, pre-existing gaps found and fixed live**, none introduced by the incident: a Postgres readinessProbe using Kubernetes field-reference syntax inside an exec probe's shell string (never expands there), a cert-manager `HelmRepository`/`HelmRelease` missing `metadata.namespace`, no `IngressRoute` anywhere in the phase actually serving `grafana-tls` for the monitoring hostname's SNI (closed with a minimal placeholder using the exact names 13-07 already expects), a host-key fingerprint comparison bug in the new SSH-forward mechanism (caught on its first live CI run), and a pod-startup CNI/DNS readiness race that initially looked like a NetworkPolicy failure. Full detail in `docs/history/2026-09-26-production-cutover-to-k3s.md`'s "Gaps found and closed live" section.
- **CI fully retargeted and proven green.** `deploy-to-netcup`, `register-schemas-production` and `cleanup-unused-image` are gone from `deploy.yml`; `flyway-verify`/`flyway-verify-nonprod` now open a fingerprint-pinned SSH local forward to the in-cluster Postgres ClusterIP and run Flyway on the runner — proven end-to-end via a real `workflow_dispatch` run (`36230184883`, all jobs green), which also proves kube-router admits node-originated CI traffic to `postgres-0`.
- **Documentation:** `docs/history/2026-09-26-production-cutover-to-k3s.md` (new) carries the full window runbook, the incident account, and the gaps-found section, indexed in `docs/history/README.md`. `docs/SESSION_LESSONS.md` gained lesson 8, generalizing the incident's root cause for any future staged multi-commit rollout.

## Task 1: Checkpoint Decision Record

**Decision:** Cut production over to k3s in a maintenance window at 2026-09-26 07:11 UTC (immediate — solo/internal deployment, no separate announcement channel; the operator's reply IS the announcement).

**Evidence presented and independently re-verified fresh by the executor immediately before Task 2:**
- 13-05's memory-budget verdict: PASS by 268 MiB (explicitly flagged thin/provisional in 13-05-SUMMARY.md, carried forward as-is, not re-measured).
- D-02 GitOps cycle: proven with real SHAs in 13-05-SUMMARY.md.
- AAAA records: confirmed EMPTY via `host -t AAAA` for all three hostnames — re-checked fresh at 07:19-07:21 UTC, not from memory.
- Branch protection: still 404 (`gh api repos/RudVlad473/kanban-board-backend/branches/main/protection`).
- No in-flight deploy.yml run (`gh run list --workflow deploy.yml --status in_progress` empty).

**Operator's reply:** "proceed" via direct conversational confirmation.

## Task Commits

1. **Task 1 (no code commit):** operator decision recorded; `900ec43` — housekeeping commit removing the consumed `.planning/HANDOFF.json`/`.continue-here.md` checkpoint artifacts.
2. **Task 2:** `edc3eb6` (W1), `e77ac08` (W2), `49a4e66` (W3), `823913c` (W4) — all staged, validated, unpushed until Task 3's gated points (see Deviations for what happened to that gating).
3. **Task 3:** `65ef8f5` (postgres readinessProbe fix), `5fdd316` (cert-manager namespace fix), `414977e` (monitoring IngressRoute placeholder), `9d675e5` (SSH fingerprint comparison fix), `819a8b5` (runbook + incident + lesson docs). Four `chore(flux): bump images` commits (`514f1a0`, `419a3c7`, `0dcf7de`, `0767200`) landed automatically within the window, authored by `fluxcdbot`, not this executor.

## Files Created/Modified

See `key-files` in frontmatter for the full list. Highlights: `.github/workflows/deploy.yml` (Compose deploy jobs removed, flyway-verify(-nonprod) rewritten around an SSH forward, `workflow_dispatch` added, `cleanup-old-images` rewritten to prune by age); `k8s/platform/traefik/helmchartconfig.yaml` (LoadBalancer + ETP Local, interim NodePort pin removed); three Certificates flipped to `letsencrypt-production`; `infra/vm/sshd/kanban-ci-tunnel.conf` (new, installed and validated on the VM); `docs/history/2026-09-26-production-cutover-to-k3s.md` (new, the full window runbook).

## Decisions Made

See `key-decisions` in frontmatter for the full list with rationale. Summarized: (1) the Task 1 go/no-go, re-verified fresh twice; (2) push-forward-not-rollback after the mid-window incident, decided explicitly by the operator; (3) drop/recreate over `pg_restore --clean` for the empty in-cluster schemas; (4) fix-in-place rather than route-around for all five gaps found live; (5) a deliberate placeholder (not a real implementation) for the monitoring IngressRoute, sized to exactly what 13-07 already expects to find.

## Deviations from Plan

### Incident: premature multi-window push

**Found during:** Task 3, immediately after pushing a bug fix for the postgres readinessProbe issue.

**What happened:** Task 2 staged four sequentially-dependent commits (W1→W2→W3→W4) on one local branch specifically so each could be pushed individually at its own gated point in the window. After pushing W1 alone and building a fix on top of it, the fix was pushed with `git push origin <fix-sha>:main` — but `<fix-sha>` resolved to the tip of the entire local branch, which by then already contained W2, W3 and W4 (built that way deliberately for Task 2's own gate-checking). The push silently included all three later commits, and Flux reconciled everything within seconds: `apps-prod` unsuspended and the production app pod started against an empty, freshly-`initdb`'d database and ran Flyway against it before any real data had been dumped or restored; Traefik's interim NodePort pin was removed while Caddy (already broken by an earlier, unrelated `docker compose stop app`) was still nominally its consumer; all three Certificates flipped to the production issuer before staging re-validation in this window; and the full CI retarget landed before the data migration.

**Confirmed impact:** production served HTTP 502 for approximately 11 minutes (Caddy's `app` upstream DNS lookup failed after `docker compose stop app`, and the in-cluster edge had not yet taken over). **Confirmed NOT lost:** Compose's own production Postgres was never stopped until after the real dump was taken — verified live (49 users, 43 boards, matching the pre-window baseline exactly) — and all 8 Compose Docker volumes remained intact throughout.

**Recovery:** the executor stopped immediately on discovering the 502 and the empty in-cluster schema, gathered full evidence, and reported rather than improvising alone. The operator explicitly directed pushing forward rather than rolling back. The in-cluster databases (holding only the empty Flyway schema, zero real rows) were dropped and recreated under their existing roles; the real dump/restore then ran against Compose's still-intact data with verified zero-diff parity; remaining Compose containers were stopped; ServiceLB was re-enabled to complete the edge takeover; and every remaining plan step was completed from that point forward.

**Files/commits:** the incident itself touched no files directly (it was a push-command error, not a code defect); the recovery's code-level side effects are documented as the five gaps below.

**Full account:** `docs/history/2026-09-26-production-cutover-to-k3s.md`'s "Incident: premature multi-window push" section. **Durable lesson:** `docs/SESSION_LESSONS.md` lesson 8 — push a staged multi-commit cutover by explicit SHA per gate, never by branch tip.

### Auto-fixed issues (Rule 1/Rule 2)

**1. [Rule 1 — Bug] Postgres readinessProbe used unexpandable `$(POSTGRES_USER)` syntax**
- **Found during:** Task 3, waiting for `postgres-0` to become Ready after W1 landed.
- **Issue:** `$(POSTGRES_USER)` inside an `exec.command` shell string is Kubernetes' field-reference syntax, which only expands in `command`/`args`/`env.value` fields, never inside a probe's own shell string — the container's `sh -c` parsed it as command substitution instead, which failed.
- **Fix:** real shell variable expansion (`$POSTGRES_USER`, no parens) — the container's own `envFrom` already carries it.
- **Files modified:** `k8s/data/postgres/postgres.yaml`
- **Commit:** `65ef8f5`

**2. [Rule 1 — Bug] cert-manager `HelmRepository`/`HelmRelease` missing `metadata.namespace`**
- **Found during:** Task 3, waiting for the `cert-manager` Kustomization to become Ready.
- **Issue:** neither object carried an explicit namespace, and the directory's `kustomization.yaml` sets none either — `kubectl apply` rejected the `HelmRepository` outright.
- **Fix:** both objects moved to `namespace: flux-system`, matching the convention `k8s/monitoring/controllers/` already establishes.
- **Files modified:** `k8s/platform/cert-manager/cert-manager.yaml`
- **Commit:** `5fdd316`

**3. [Rule 2 — Missing critical functionality] No route serves `grafana-tls` for the monitoring hostname**
- **Found during:** Task 3, verifying all three hostnames present production certificates (the plan's own verify script requirement).
- **Issue:** `certificate-monitoring.yaml` (13-04) creates and issues the Certificate, but nothing in the phase up to 13-06 creates an `IngressRoute` that actually serves it — Traefik fell back to its own self-signed default cert for that SNI.
- **Fix:** minimal placeholder `IngressRoute grafana`/`IngressRoute grafana-http` using the exact names 13-07's own plan text already expects, backed by an Endpoints-less placeholder Service (Traefik answers 503) until 13-07 wires the real Grafana Service.
- **Files modified:** `k8s/platform/edge/ingressroute-monitoring.yaml` (new), `k8s/platform/edge/kustomization.yaml`
- **Commit:** `414977e`

**4. [Rule 1 — Bug] SSH host-key fingerprint comparison always failed**
- **Found during:** Task 3, dispatching `deploy.yml` to prove the new SSH-forward mechanism (run `36229677270`).
- **Issue:** `ssh-keyscan -H` scans every host key algorithm the server offers, producing multiple fingerprints; the original code compared the whole multi-line set against one pinned fingerprint with exact-string equality, which always failed even against the correct host.
- **Fix:** check whether the pinned fingerprint is one of the scanned lines (`grep -qxF`).
- **Files modified:** `.github/workflows/deploy.yml` (both `flyway-verify` and `flyway-verify-nonprod`)
- **Commit:** `9d675e5`
- **Re-verified:** follow-up `deploy.yml` run `36230184883`, all jobs green.

### Non-code finding, documented rather than fixed

**5. NetworkPolicy positive probe initially looked identical to the negative probe's failure.** Diagnosed as a genuine pod-startup CNI/CoreDNS readiness race (`kubectl run --rm -i` executes essentially immediately at container start), not a policy defect — confirmed by a live diagnostic pod (`kubectl exec` into an already-Running pod succeeded immediately) and by re-running both probes with a 3-second startup delay, which produced the correct positive/negative pair consistently. No manifest changed; recorded as an operational note in the runbook for the next person reaching for `kubectl run --rm` soon after a cluster restart.

## Known Stubs

- **`k8s/platform/edge/ingressroute-monitoring.yaml`'s `grafana-placeholder` Service has no Endpoints** — a deliberate stub, not an oversight. It exists solely to let Traefik serve `grafana-tls` for TLS SNI routing on the monitoring hostname before Grafana itself is deployed (13-07). Any HTTP request to the monitoring hostname currently gets a 503 with no body. **Resolved by 13-07**, which replaces `spec.routes[].services` on both `IngressRoute grafana` and `IngressRoute grafana-http` with the real Grafana Service — do not remove this file, extend it.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: new-edge-surface | `k8s/platform/edge/ingressroute-monitoring.yaml` | New public TLS-terminating route (monitoring hostname) added mid-plan, not in the original `<threat_model>`'s STRIDE register. Low risk in its current form (503-only, no real backend), but the register should be updated when 13-07 wires the real Grafana Service behind it. |

## Self-Check: PASSED

- `docs/history/2026-09-26-production-cutover-to-k3s.md` exists: FOUND
- `docs/history/README.md` references "Plan 13-06": FOUND
- `docs/SESSION_LESSONS.md` contains "### 8.": FOUND
- Commit `edc3eb6` (W1) exists in `git log --oneline main`: FOUND
- Commit `e77ac08` (W2) exists: FOUND
- Commit `49a4e66` (W3) exists: FOUND
- Commit `823913c` (W4) exists: FOUND
- Commit `65ef8f5` (postgres fix) exists: FOUND
- Commit `5fdd316` (cert-manager fix) exists: FOUND
- Commit `414977e` (monitoring route) exists: FOUND
- Commit `9d675e5` (SSH fingerprint fix) exists: FOUND
- Commit `819a8b5` (docs) exists: FOUND
- Public prod health UP over a production LE cert: FOUND (re-checked at documentation time)
- Public nonprod health UP over a production LE cert: FOUND
- `deploy.yml` run `36230184883` all jobs green: FOUND
- `uptime-check.yml` run `36230890219` green: FOUND
