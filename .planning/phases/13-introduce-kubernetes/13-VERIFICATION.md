---
phase: 13-introduce-kubernetes
verified: 2026-09-30T15:16:10Z
status: passed
score: 15/18 must-haves verified
covered_files:
  - .github/workflows/deploy.yml
  - .planning/phases/13-introduce-kubernetes/13-CONTEXT.md
  - docs/INFRA_RUNBOOK.md
  - infra/vm/k3s-host-firewall.sh
  - k8s/flux-system/kustomization.yaml
  - k8s/overlays/nonprod/kustomization.yaml
  - k8s/overlays/prod/kustomization.yaml
  - k8s/platform/traefik/helmchartconfig.yaml
  - scripts/verify-k8s-invariants.py
covered_digest: "v1:sha256:35f94b79cba7bfcfa92cec5ec77c3a0daef5a9e09b4f73b1424899a4b9e190a8"
behavior_unverified: 2
overrides:
  - must_have: "Only one production stack was in memory at a time: every Compose container was stopped before in-cluster prod workloads started, inside one announced window whose start, end and downtime are recorded (D-03)."
    reason: "Staged W2-W4 were pushed by branch tip; apps-prod ran briefly before the data migration and Compose Postgres stayed up until the real dump. About 11 min public 502, zero data loss, no OOM, parity verified. Operator directed roll-forward."
    accepted_by: "operator (RudVlad473)"
    accepted_at: "2026-09-30T15:16:10Z"
overrides_applied: 1
behavior_unverified_items:
  - truth: "KANBAN-INGRESS alone keeps k3s NodePorts closed after Docker Engine and DOCKER-USER were retired, attributed by its DROP counter"
    test: "With Docker stopped (it is), open a temporary, source-IP-scoped Netcup Cloud Firewall rule for TCP 30104, run `nc -z -w 5 159.195.114.230 30104` from that IP, and read `iptables -t mangle -L KANBAN-INGRESS -n -v -x | grep DROP` on netcup-prod before and after. Remove the console rule afterwards."
    expected: "The KANBAN-INGRESS DROP counter (13 pkts / 1508 bytes at 2026-09-30T15:05Z, unchanged across a 45 s window) increases and the probe fails to connect."
    why_human: "The Netcup Cloud Firewall (Layer 2) drops the probe before it reaches the VM, so an unmodified off-box probe cannot move a Layer 3 counter. Only an operator can open the console rule. What IS proven: the chain and its PREROUTING position-1 jump are present after the reboot, and NodePorts 30080/30104 are unreachable off-box."
  - truth: "Traefik rate limiting is per client: with two distinct public source IPs, throttling one leaves the other unaffected (D-13, Pitfall 2)"
    test: "Re-run the 13-08 two-source-IP proof (runner IP plus a second IP) against the prod signin path and read Traefik's JSON access log for ClientHost."
    expected: "Only the throttled IP receives 429; the other still gets 401; ClientHost shows real public addresses, not a pod-CIDR or node IP."
    why_human: "Needs two distinct public source IPs and a live access-log read. The recorded proof (13-08-SUMMARY, verify-rate-limit run 36271337736) was not reproduced by this verifier. The CI gate verify-rate-limit.yml (run 36721560206, success) exercises the limits and the nonprod exemption, not per-client isolation."
human_verification:
  - test: "KANBAN-INGRESS DROP-counter attribution without Docker (see behavior_unverified_items[0])"
    expected: "Counter increases on a probe to a NodePort that the console rule now lets through"
    why_human: "Requires the operator to open a temporary Netcup console firewall rule"
  - test: "Two-source-IP per-client rate-limit proof (see behavior_unverified_items[1])"
    expected: "429 for the throttled IP only; real ClientHost in the access log"
    why_human: "Requires two public source IPs"
  - test: "Accept or reject the D-03 ordering deviation in the production cutover window (truth 3)"
    expected: "Operator judges whether the recorded incident (staged W2/W3/W4 pushed by branch tip, about 11 minutes of public 502, apps-prod briefly running in-cluster while Compose Postgres was still up, operator-directed roll-forward, zero data loss, no OOM) satisfies D-03's substance. If accepted, add the override block below to this file's frontmatter."
    why_human: "The literal plan truth 'every Compose container was stopped before in-cluster prod workloads started' is false as executed (13-06-SUMMARY and docs/history/2026-09-26-production-cutover-to-k3s.md say so themselves). Whether the deviation is acceptable is a judgment call for the operator, and the event cannot be re-run."
  - test: "Acknowledge the unidentified 72 MB anonymous Docker volume that was deleted (e757bd8d...)"
    expected: "Operator confirms it held nothing needed. It was Postgres-shaped (base/, global/, pg_hba.conf), created 2026-09-26T09:36:43+02:00, the cutover day, and only its top-level directory listing was read before `docker volume rm`."
    why_human: "Irreversible and now unverifiable: the volume is gone. D-04 accepted one-way deletion, but this volume was not on the plan's list of eight."
---

# Phase 13: Introduce Kubernetes Verification Report

**Phase Goal:** Both environments run on a single-node k3s cluster on the existing Netcup VPS instead of Docker Compose, deployed pull-based by Flux with sortable `main-<run>-<sha7>` tags, fronted by Traefik + cert-manager with re-derived and proven rate limits, Postgres in-cluster with row-count parity, kube-prometheus-stack + Loki/Alloy with public dashboard links recreated. Compose deleted only after the strict D-08 gate passes.
**Verified:** 2026-09-30T15:10:00Z
**Status:** human_needed
**Re-verification:** No, initial verification (no prior 13-VERIFICATION.md existed)

Method. The worktree is `finish-13`, HEAD `8b91d5e` = `origin/main` (fetched). SUMMARY claims were tested against the repo, against read-only commands on `netcup-prod`, and against GitHub. `.planning/REQUIREMENTS.md` does not exist (ROADMAP: "no REQUIREMENTS.md for this not-yet-scoped milestone"), so requirement IDs D-01..D-18 were cross-referenced against `13-CONTEXT.md`.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Both environments run on k3s (v1.36.4+k3s1), reconciled by Flux from `main` | ✓ VERIFIED | `ssh netcup-prod k3s kubectl get nodes` Ready v1.36.4+k3s1; `kanban-prod`/`kanban-nonprod` app + redpanda pods 1/1 Running; all 9 Flux Kustomizations `True` on `main@sha1:8b91d5e` after settling (see Warning W1); 5 HelmReleases `True`; both public health endpoints `{"status":"UP"}` HTTP 200 (probed by me over the internet at 15:03Z and 15:06Z) |
| 2 | Nonprod moved first and completed a full GitOps cycle before prod (D-02) | ✓ VERIFIED | 13-05-SUMMARY and runbook record it; independently, I watched the same mechanism run live for prod and nonprod (truth 15). ImageUpdateAutomation `kanban-images` `True`, `lastPushCommit 8b91d5e` |
| 3 | Production cutover in one announced window with only one prod stack in memory at a time (D-01, D-03) | ? UNCERTAIN | Window ran T_start 07:46:01Z to T_end 08:45:06Z, but 13-06-SUMMARY (self-declared deviation) and `docs/history/2026-09-26-production-cutover-to-k3s.md` record that the multi-commit branch was pushed by tip: apps-prod started in-cluster before the data migration, about 11 min public 502, operator-directed roll-forward, Compose Postgres untouched until the real dump, parity held. The literal plan ordering was violated. D-03's substance (no OOM overlap) held per the record. Needs operator judgment (human item 3) |
| 4 | Sortable tags `main-<run>-<sha7>` and Flux pull-based deploys; Flux tag-bump commits do not trigger a build loop (D-14, D-15, D-16) | ✓ VERIFIED | `.github/workflows/deploy.yml:145` `image_tag=main-${{ github.run_number }}-${GITHUB_SHA::7}`; `paths-ignore` includes `k8s/**` (line 27); two ImagePolicies `kanban-board-backend-prod` / `-nonprod` `True`, latest `main-143-15d6b3f`; fluxcdbot commits `8a2097f` (prod overlay) and `8b91d5e` (nonprod overlay) on `origin/main`; deploy.yml run #143 on `15d6b3f` = `push`, `success`; no deploy.yml run for the bot commits |
| 5 | Traefik + cert-manager front all three hostnames with Let's Encrypt production certificates, HTTP redirects present (D-13) | ✓ VERIFIED | `k3s kubectl get certificate -A` 3/3 Ready, all `issuerRef letsencrypt-production`; `openssl s_client` on all three hostnames: issuer Let's Encrypt (YR2/YR2/YR1), notAfter 2026-12-25; `curl -I http://...` returns 301 to https; Traefik is the only non-ClusterIP Service (`kube-system/traefik LoadBalancer 159.195.114.230`, ETP `Local`, `healthCheckNodePort 31759`) |
| 6a | Rate limits re-derived from Traefik token-bucket semantics on the prod edge (auth, general, Grafana login), nonprod unlimited (D-13) | ✓ VERIFIED | `k8s/overlays/prod/ingressroute.yaml` Middlewares `auth-rate-limit`, `general-rate-limit`; `k8s/monitoring/configs/ingressroute.yaml` `grafana-login-rate-limit`; nonprod IngressRoute carries none ("No rate limit on nonprod (D-13)"); `verify-rate-limit.yml` run 36721560206 `success` |
| 6b | Per-client limiting proven with two distinct source IPs (D-13) | ⚠️ PRESENT_BEHAVIOR_UNVERIFIED | ETP `Local` is set live and in `helmchartconfig.yaml`; a recorded proof exists (13-08-SUMMARY, run 36271337736, access-log ClientHost), but I did not reproduce it and no automated test asserts per-client isolation. Human item 2 |
| 7 | Postgres 16 runs in-cluster in `kanban-data`, roles created before restore, NetworkPolicy live, row-count parity with the pre-cutover snapshot (D-05, D-08.2, D-11) | ✓ VERIFIED | `postgres-0` 1/1 Running; NetworkPolicy `kanban-data/postgres-ingress` present; on the VM `diff counts-kanban_prod-before.txt counts-kanban_prod-after.txt` and the nonprod pair are both empty (activity_log 2261, boards 43, columns 249, subtasks 983, tasks 984, users 49). The live DB has since grown (users 77, boards 71, activity_log 3801), as expected for a running service. NetworkPolicy positive/negative probe is recorded in 13-06 but not re-run by me |
| 8 | kube-prometheus-stack, Loki, Alloy, postgres-exporter run in `monitoring`; public dashboard links work (D-07, D-17, D-18) | ✓ VERIFIED | 5 HelmReleases `True`; Prometheus API via kube-apiserver proxy: 17 active targets, 17 `up` (apiserver, kubelet, kube-state-metrics, node-exporter, monitoring/redpanda, postgres-exporter, cert-manager, coredns, etc.); Loki namespace label values: cert-manager, flux-system, kanban-data, kanban-nonprod, kanban-prod, kube-system, monitoring; both README public-dashboard URLs return HTTP 200 (`/api/public/dashboards/9b5bf7c8...`, `/cb7a5fc4...`); Grafana login page HTTP 200 over a production cert; `python3 scripts/verify-public-dashboards.py` `invariants OK -- k8s: 3 dashboard(s) checked` |
| 9 | Every component's memory limit is a restart-ladder measurement, no provisional label remains (D-07) | ✓ VERIFIED | `python3 scripts/verify-k8s-invariants.py --no-provisional` `invariants OK -- 12 kustomization root(s) checked ...`; selftest `I1-I10 each fire on an engineered violation`. Behavioural note: the first ladder-adopted Prometheus/node-exporter limits were wrong (five OOM kills, first D-08 FAIL); they were raised in quick task 260929-fwf and the re-opened window ran clean (truth 10) |
| 10 | D-08 gate passed on evidence, including a clean 24 h window with zero restarts and zero OOMKills | ✓ VERIFIED | Independent re-query (Prometheus via API proxy, `time=1790762330` = 2026-09-30T09:58:50Z, T0+24h): `sum(increase(kube_pod_container_status_restarts_total[24h]))` = **0**; `count(max_over_time(...reason="OOMKilled"[24h]))` = empty; `count(kube_pod_container_status_restarts_total)` = 34; `journalctl -k --since "2026-09-29 09:58:50" --until "2026-09-30 13:40:00" \| grep -c "Memory cgroup out of memory"` = **0**. Control: the same restart query at the original T0+24h returns 4.0, so the instrument can see failures. Earlier FAIL (2026-09-29) is retained unedited in the runbook |
| 11 | D-08.4: cutover dump retained outside any volume with valid checksums | ✓ VERIFIED | `/root/k3s-cutover-archive` (0700, files 0600): `sha256sum -c SHA256SUMS` 7/7 OK on the VM just now |
| 12 | D-04's one-way deletion followed an explicit operator decision made with the gate evidence | ✓ VERIFIED | Operator's verbatim reply "1 = decommission-reboot-keep-dump" recorded in runbook Decommission Record and 13-10-SUMMARY; gate table (all PASS) precedes it |
| 13 | Compose, Caddy and Docker Engine are gone from the VPS and the repo | ✓ VERIFIED | VM: `systemctl is-enabled docker docker.socket containerd docker-user-firewall` all `disabled`, `docker`/`containerd` `inactive`, no `/var/run/docker.sock`, `/var/lib/docker/volumes` holds only `backingFsBlockDev` and `metadata.db`; `uptime -s` 15:40:51 local shows the reboot. Repo: `docker-compose.prod.yml`, `docker-compose.nonprod.yml`, `Caddyfile`, `docker/{caddy,prometheus,loki,promtail,grafana,postgres-init}`, all Compose-only verify scripts and `docker-user-firewall.*` absent; only the local-dev `docker-compose.yml` remains; no Caddy/Compose reference in `.github`, `k8s`, `scripts` YAML. Commit `15d6b3f` and gate record `7196dab` are ancestors of `origin/main` |
| 14 | KANBAN-INGRESS alone keeps NodePorts closed once Docker's DOCKER-USER chain is gone, attributed by its DROP counter | ⚠️ PRESENT_BEHAVIOR_UNVERIFIED | Present and correct: `iptables -t mangle -S PREROUTING` = `-A PREROUTING -i eth0 -j KANBAN-INGRESS`; the chain allows only ESTABLISHED, 22, 80, 443, ICMP and DROPs other NEW; `k3s-host-firewall.service` enabled+active and survived a reboot. **Not proven:** the DROP counter (13 pkts) did not move in a 45 s window and the plan's Layer-3-only attribution needs the operator-opened console rule. Deliberate open item, not counted as passed. Human item 1 |
| 15 | Both environments keep deploying from `main` through Flux after decommission (D-16) | ✓ VERIFIED | Observed live: ImagePolicy went to `main-143-15d6b3f` (built by the decommission commit's deploy.yml run #143); fluxcdbot pushed `8a2097f`/`8b91d5e`; prod and nonprod `app` pods rolled to `main-143-15d6b3f` at 15:03Z (`Recreate` strategy, about 40 s of expected 503 on prod during roll), all Kustomizations returned to `True`, both endpoints UP again at 15:04Z |
| 16 | README, `docs/INFRA_ARCHITECTURE.md` and the three infra diagrams describe the k3s topology | ✓ VERIFIED | README mermaid contains `k3s_box`, Traefik, Flux, Alloy; `docs/diagrams/infra-{physical-deployment,delivery-scenario,packet-path-scenario}.png` present (176/150/356 kB) |
| 17 | Secrets are never committed and k3s encrypts Secrets at rest (D-10) | ✓ VERIFIED | `grep -rn "kind: Secret" k8s` returns nothing; invariant I-secret in the passing 12-root gate; `k3s secrets-encrypt status`: `Encryption Status: Enabled`, `All hashes match`; `infra/vm/k3s/config.yaml` `secrets-encryption: true` |

**Score:** 15/18 truths verified (1 UNCERTAIN, 2 present-but-behavior-unverified). No FAILED truths, so no BLOCKER.

### Decision Honor Check (13-CONTEXT.md)

| Decision | Honored? | Note |
|----------|----------|------|
| D-01 full prod cutover | Yes | Prod on k3s, live |
| D-02 nonprod first | Yes | Waves 3 then 4 |
| D-03 one prod stack in memory | Partly | Substance yes, literal ordering no (truth 3) |
| D-04 no rollback window, D-08 gate is the only safety margin | Yes, with one amended item | Gate evaluated twice; first FAIL retained. D-08.1(b)'s plan-invented floor ">= 90 runs" was amended to ">= 10, all success" by operator decision (quick task 260929-fwf) after GitHub's cron throttling made 90 unreachable. CONTEXT's D-08.1 states no count, only that `uptime-check` is green, so the amended floor still satisfies the decision's wording. 15 runs, 15 success in the amended window |
| D-05 in-cluster Postgres, roles before restore | Yes | Truth 7 |
| D-06 fresh Redpanda per env, lag 0 + empty DLT before stopping old broker | Yes per record | 13-06 records lag 0 / empty DLT evidence; not re-testable |
| D-07 kube-prometheus-stack + Loki + Alloy, no docker.sock | Yes | Truth 8; invariant forbids hostPath/hostNetwork |
| D-09 Kustomize base+overlays, no helm binary in CI | Yes | 12 kustomization roots; HelmReleases via Flux |
| D-10 secrets by hand, encryption on | Yes | Truth 17 |
| D-11 namespaces and NetworkPolicy | Yes | Four namespaces live plus flux-system, kube-system, cert-manager; policy on Postgres |
| D-12 kubeconform pinned in CI | Yes | `scripts/verify-k8s-manifests.sh` passes (per 13-10-SUMMARY); CI `Invariant Checks` green on `15d6b3f`, `8a2097f`, `8b91d5e` |
| D-13 Traefik, three zones, staging then production certs, per-client proof | Yes, except unreproduced two-IP proof (6b) | |
| D-14 schema registration in-cluster before app start | Yes (initContainer `register-schemas`, as the post-planning clarification allows) | |
| D-15/D-16 Flux image automation, independent policies, no promotion gate | Yes | Truth 4 and 15 |
| D-17 observability moves at prod cutover | Yes | 13-07 |
| D-18 (corrected) three public shares recreated, README keeps two links | Yes | Two README links return 200; `verify-public-dashboards.py` checks 3 |

### Requirements Coverage

`.planning/REQUIREMENTS.md` is absent for this phase (documented in ROADMAP). Every ID claimed in the ten plans' `requirements:` frontmatter is a CONTEXT decision; the union is exactly D-01..D-18, so there are **no orphaned** decisions.

| Requirement | Source plans | Status | Evidence |
|-------------|--------------|--------|----------|
| D-01 | 13-06, 13-10 | ✓ SATISFIED | Truths 1, 13 |
| D-02 | 13-02, 13-05, 13-06 | ✓ SATISFIED | Truth 2 |
| D-03 | 13-06 | ? NEEDS HUMAN | Truth 3 |
| D-04 | 13-08, 13-10 | ✓ SATISFIED with open item | Truths 12, 13; DROP-counter leg is truth 14 |
| D-05 | 13-04, 13-06 | ✓ SATISFIED | Truth 7 |
| D-06 | 13-01, 13-04, 13-05, 13-06 | ✓ SATISFIED | Recorded evidence; Redpanda 1/1 both envs |
| D-07 | 13-03, 13-07, 13-09 | ✓ SATISFIED | Truths 8, 9 |
| D-08 | 13-04, 13-06, 13-08, 13-09, 13-10 | ✓ SATISFIED | Truths 10, 11 |
| D-09 | 13-01, 13-03, 13-04 | ✓ SATISFIED | 12 roots |
| D-10 | 13-01, 13-02, 13-05, 13-06, 13-07 | ✓ SATISFIED | Truth 17 |
| D-11 | 13-01, 13-02, 13-04, 13-06, 13-08 | ✓ SATISFIED | Truth 7 |
| D-12 | 13-01, 13-02 | ✓ SATISFIED | CI green |
| D-13 | 13-04, 13-06, 13-08, 13-09, 13-10 | ✓ SATISFIED except 6b | Truths 5, 6a; 6b human |
| D-14 | 13-01, 13-05, 13-06 | ✓ SATISFIED | Truth 4 |
| D-15 | 13-01, 13-02, 13-05, 13-09 | ✓ SATISFIED | Truth 4 |
| D-16 | 13-01, 13-02, 13-05, 13-06, 13-10 | ✓ SATISFIED | Truth 15 |
| D-17 | 13-02, 13-05, 13-07 | ✓ SATISFIED | Truth 8 |
| D-18 | 13-03, 13-07 | ✓ SATISFIED | Truth 8 |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Invariant gate selftest and real run | `python3 scripts/verify-k8s-invariants-selftest.py; python3 scripts/verify-k8s-invariants.py --no-provisional` | `selftest OK`, `invariants OK -- 12 kustomization root(s)` | ✓ PASS |
| Dashboard gate | `python3 scripts/verify-public-dashboards-selftest.py; python3 scripts/verify-public-dashboards.py` | SELFTEST OK, `invariants OK -- k8s: 3 dashboard(s) checked` | ✓ PASS |
| Public health, both envs | `curl https://<host>/api/actuator/health` | UP, HTTP 200 (twice) | ✓ PASS |
| 24h zero restarts at T0+24h | Prometheus API proxy, `time=1790762330` | 0; OOMKilled series empty | ✓ PASS |
| Kernel OOM since re-opened T0 | `journalctl -k ... \| grep -c "Memory cgroup out of memory"` | 0 | ✓ PASS |
| Dump integrity | `sha256sum -c SHA256SUMS` on the VM | 7/7 OK | ✓ PASS |
| Off-box NodePort probe | not runnable meaningfully (Layer 2 masks it) | n/a | ? SKIP, human item 1 |

Not run by me: `verify-k8s-manifests.sh` (needs pinned kubectl/kubeconform downloads); its result is taken from 13-10-SUMMARY and from the green `Invariant Checks` workflow on three commits on `main`.

### Probe Execution

No `scripts/*/tests/probe-*.sh` files exist and no plan declares one. SKIPPED (no probes).

### CI on `origin/main` (checked with `gh`)

| Commit | Workflow | Conclusion |
|--------|----------|------------|
| `15d6b3f` (decommission) | CI/CD with Docker (deploy.yml #143), Invariant Checks, Secret Scan, Uptime Check, Dependabot | all `success` |
| `8a2097f`, `8b91d5e` (fluxcdbot bumps) | Invariant Checks, Secret Scan | `success` |
| `7196dab` (gate record, docs only) | none triggered under that head SHA; its successor `15d6b3f` is fully green | n/a |
| verify-rate-limit run 36721560206 (dispatch on `a01d000`) | verify-rate-limit.yml | `success` |

13-10-SUMMARY listed the `invariant-checks` and `deploy.yml` main runs as "NOT RUN (cannot push)". That gap is now closed by the runs above.

### Anti-Patterns Found

Debt-marker scan (`TBD|FIXME|XXX`) was not run across every file touched by the phase (about 60 files); I relied on the passing invariant gates and the CI. Recorded as a limit of this verification, not as a finding.

| File | Pattern | Severity | Impact |
|------|---------|----------|--------|
| `docs/INFRA_RUNBOOK.md` Task 1 verify block (per its own caveat) | `rb.split(<heading>)[1]` selects the retained FAIL record | Info | Plan-embedded verify script would misjudge the gate; irrelevant now that the gate is decided |
| `.planning/ROADMAP.md`, `.planning/STATE.md` | Phase 13 shows "8/10 plans executed", 13-09 and 13-10 unchecked, STATE `stopped_at 13-09 ... before 13-10` | Warning | Bookkeeping is stale against reality (both plans have SUMMARYs with `status: complete` and their commits are on `origin/main`). Needs the phase-complete step, not code |

### Warnings

- **W1, Flux readiness flaps at reconcile.** At 15:01 to 15:02Z `apps-prod`, `apps-nonprod`, `edge` and `monitoring` reported `False dependency ... is not ready` for about two minutes after the bump commits, then all returned `True`. The cause is Flux re-evaluating `dependsOn` chains (`data`, `cert-manager`, `edge`) on each new revision; it is transient and self-healing, but a monitor keyed on Kustomization Ready would page on every deploy.
- **W2, production data has no backup.** D-12 keeps automated backups out of scope, and the Compose volumes are gone. The prod DB now holds 77 users / 3801 activity rows against the 49 / 2261 in the retained dump, so the retained dump protects only the 2026-09-26 state. Known, documented in the runbook's "What is NOT recoverable", but it is the largest residual risk of the one-way D-04 decision.
- **W3, `Recreate` strategy** on the app Deployment means every Flux-driven deploy is a short public outage (about 40 s of 503 observed at 15:03Z). Not a phase goal, but it is what "pull-based deploys" costs today with one replica.
- **W4, the reboot reset restart counters** (32 restarts cluster-wide by construction), so D-08.3 cannot be re-run from live counters; the 24 h evidence is the Prometheus history at T0+24h, which I re-queried.
- **W5, `0.0.0.0:31759`** (13-10-SUMMARY: "not investigated") is identified: it is Traefik's `healthCheckNodePort` (`k3s kubectl -n kube-system get svc traefik -o jsonpath='{.spec.healthCheckNodePort}'` = 31759), a kube-proxy listener created by `externalTrafficPolicy: Local`. It falls under the same KANBAN-INGRESS drop as the NodePorts, so its exposure is tied to human item 1.
- **W6, k3s API (6443) and kubelet (10250) listen on a dual-stack wildcard** and `ip6tables INPUT` is ACCEPT (13-10-SUMMARY finding, confirmed by `ss -ltnp`). Whether the Netcup firewall blocks them over IPv6 was not probed by me either. Both require TLS client authentication. The IPv6 todo remains pending.

### Gaps Summary

There are no FAILED truths and no blocker anti-patterns, so the status is not `gaps_found`. The phase goal is substantively achieved and live: both environments run on k3s under Flux, Compose/Caddy/Docker are gone from the VPS and the repo, the D-08 gate passed on a re-opened clean 24 h window that I re-queried independently, and a fresh GitOps deploy (main-143) rolled to both environments while I watched.

The status is `human_needed` because four items need a person:

1. The KANBAN-INGRESS DROP-counter attribution after Docker's removal is not proven, as flagged. It needs the operator's temporary Netcup console rule. Until then the claim "the k3s-era host filter alone keeps NodePorts closed" rests on structure (chain present, jump first, survives reboot) and on the Layer-2 firewall, not on a Layer-3 counter.
2. The two-source-IP per-client rate-limit proof was recorded once, not automated, and not reproduced here.
3. D-03's literal ordering was violated during the prod window (documented incident). Operator to accept or reject. If accepted, add:

   ```yaml
   overrides:
     - must_have: "Only one production stack was in memory at a time: every Compose container was stopped before in-cluster prod workloads started, inside one announced window whose start, end and downtime are recorded (D-03)."
       reason: "Staged W2-W4 were pushed by branch tip; apps-prod ran briefly before the data migration and Compose Postgres stayed up until the real dump. About 11 min public 502, zero data loss, no OOM, parity verified. Operator directed roll-forward."
       accepted_by: "{operator}"
       accepted_at: "{ISO timestamp}"
   ```
4. The unidentified 72 MB anonymous Postgres-shaped volume deleted beyond the plan's list is irreversible; the operator should acknowledge it.

Bookkeeping to close at phase completion (not verification gaps): tick 13-09 and 13-10 in ROADMAP, set the phase count to 10/10, and refresh STATE.md.

---

_Verified: 2026-09-30T15:10:00Z_
_Verifier: Claude (gsd-verifier)_

## Operator close-out (2026-09-30)

Operator replied "1" to close Phase 13 now: D-03 accepted (override above). Status set to `passed` by that decision, NOT because the two behavior-unverified items were proven. They stay open as todos: KANBAN-INGRESS DROP-counter attribution (needs a temporary Netcup console rule), and reproducing the two-source-IP rate-limit proof. The unidentified 72 MB anonymous volume deleted in 13-10 is acknowledged as irreversible.
