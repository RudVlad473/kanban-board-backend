---
phase: 13-introduce-kubernetes
plan: 02
subsystem: infra
tags: [kubernetes, k3s, flux, gitops, image-automation, postgres-bridge, traefik, oom-incident]

# Dependency graph
requires:
  - phase: 13-01
    provides: "k8s/base/app + k8s/base/redpanda + k8s/overlays/nonprod Kustomize shape, verify-k8s-manifests.sh, verify-k8s-invariants.py (I1-I8), sortable image tag scheme"
provides:
  - "A live k3s v1.36.4+k3s1 cluster + Flux v2.9.5 GitOps loop on netcup-prod, running alongside Docker Compose -- proven, not merely installed"
  - "k8s/flux-system/: gotk-components/gotk-sync, Kustomizations flux-system/platform/data (Ready, unsuspended) + 6 later-phase Kustomizations (apps-nonprod, cert-manager, edge, apps-prod, monitoring-controllers, monitoring -- all Suspended, dependsOn/healthChecks wired per 13-01's phase_contracts)"
  - "k8s/platform/: 4 phase namespaces, Traefik HelmChartConfig (interim NodePort 30080)"
  - "k8s/data/postgres-bridge/: selector-less Service (clusterIP 10.43.54.32) + EndpointSlice bridging k3s pods to Compose Postgres via 172.17.0.1:5432 -- A2-proven live, isolation intact"
  - "k8s/flux-system/image-automation.yaml: 2 ImageRepositories, 2 ImagePolicies (sortable main-N-sha7), 1 ImageUpdateAutomation -- proven live via 3 separate real fluxcdbot bump commits, zero deploy.yml triggers"
  - "docker-compose.prod.yml: postgres publishes 172.17.0.1:5432:5432 (interim, removed in 13-10), gated by a narrow scripts/verify-compose-ports.py exception"
  - "docs/INFRA_RUNBOOK.md's new k3s section: measured A1 baseline, verbatim A2 proof, and two real live-execution bug findings (Traefik Service-type quirk, ImageUpdateAutomation template field)"
affects: [13-05, 13-06, 13-07, 13-09]

# Actuals (#2632)
actuals:
  tokens: 93911
  tasks: 3
  commits: 5
plan_head_before: add7d19ef4cae77bdf8eb5e4ed6bd63e09dd6cfd

# Tech tracking
tech-stack:
  added:
    - "k3s v1.36.4+k3s1 (pinned, checksum-verified install)"
    - "Flux v2.9.5 (source/kustomize/helm/notification + image-reflector/image-automation controllers)"
  patterns:
    - "Selector-less Service + hand-authored EndpointSlice for a cross-runtime bridge to a non-cluster workload (13-RESEARCH.md Pattern 1), pinned clusterIP so the eventual in-cluster replacement (13-04's final Postgres Service) is a drop-in swap"
    - "Suspended Flux Kustomizations for every later phase's workload, applied once and staged inert -- flip suspend:false in the unsuspending plan's own commit rather than authoring new Kustomization CRs later"
    - "Live infra work reported as a running checkpoint/status-update dialogue with the orchestrator rather than silent execution -- an architectural conflict (worktree isolation vs. the plan's own 'push to main' instruction) and a live OOM incident were both surfaced and resolved collaboratively mid-execution, not discovered after the fact"

key-files:
  created:
    - infra/vm/k3s/config.yaml
    - infra/vm/k3s/install.sh
    - k8s/flux-system/gotk-components.yaml
    - k8s/flux-system/gotk-sync.yaml
    - k8s/flux-system/kustomization.yaml
    - k8s/flux-system/platform.yaml
    - k8s/flux-system/data.yaml
    - k8s/flux-system/apps-nonprod.yaml
    - k8s/flux-system/cert-manager.yaml
    - k8s/flux-system/edge.yaml
    - k8s/flux-system/apps-prod.yaml
    - k8s/flux-system/monitoring-controllers.yaml
    - k8s/flux-system/monitoring.yaml
    - k8s/flux-system/image-automation.yaml
    - k8s/platform/kustomization.yaml
    - k8s/platform/namespaces.yaml
    - k8s/platform/traefik/helmchartconfig.yaml
    - k8s/data/postgres-bridge/kustomization.yaml
    - k8s/data/postgres-bridge/postgres-bridge.yaml
  modified:
    - docker-compose.prod.yml
    - scripts/verify-compose-ports.py
    - scripts/verify-compose-ports-selftest.py
    - scripts/verify-k8s-manifests.sh
    - docs/INFRA_RUNBOOK.md
    - infra/vm/README.md

key-decisions:
  - "Task 1's checkpoint decision was pre-recorded by the operator as 'proceed' before this dispatch began (see Task 1 section below for full pre-flight evidence, both prior-run and freshly re-gathered this run)."
  - "image.toolkit.fluxcd.io/v1beta2 (as drafted from the plan's own action text) does not exist on the installed Flux 2.9.5 CRDs -- ImageRepository/ImagePolicy/ImageUpdateAutomation only serve v1. Corrected before the first commit (Rule 1)."
  - "verify-k8s-manifests.sh's KUBECONFORM_SKIP_KINDS gained CustomResourceDefinition (2026-09-25): gotk-components.yaml embeds the CRD *definitions* themselves (apiextensions.k8s.io/v1, a core kind), for which yannh/kubernetes-json-schema publishes no schema at any version checked -- a structural gap in that schema repo, not a version-pin artifact. Used the gate's own documented escape hatch."
  - "This plan ran as a worktree-isolated agent while its own <action> text assumed a sequential executor pushing directly to main (deploy.yml's live trigger is load-bearing for Task 2's own acceptance criteria). Surfaced as a checkpoint:decision rather than improvised past; the orchestrator resolved it by merging/pushing on my behalf at each step, a pattern repeated three times across this plan's execution (Task 2's repo commit, Task 3's static WIP commit, and implicitly for every subsequent commit)."
  - "A live OOM incident (Docker Compose cadvisor, unrelated to k3s's own manifests but triggered by installing k3s) was found during the A1 baseline measurement and, per the plan's own explicit gate ('if MemAvailable is under 2048 MiB, or any OOM kill appeared, stop and report before 13-05'), execution paused for a checkpoint:human-action rather than silently continuing or self-authorizing a docker-compose.prod.yml change outside this plan's declared scope."
  - "Two real bugs (Traefik HelmChartConfig's service.type not taking effect on first Helm apply; ImageUpdateAutomation's messageTemplate using Flux's removed .Updated field) were found only by running this plan's own manifests against the live cluster -- neither was visible from static kubeconform/invariant validation. Both are documented in the runbook's new 'Live findings' subsection so a future session hitting the same symptom does not re-diagnose it from scratch."

requirements-completed: [D-02, D-10, D-11, D-12, D-15, D-16, D-17]

coverage:
  - id: D1
    description: "k3s v1.36.4+k3s1 runs on netcup-prod with secrets encryption on, ServiceLB and metrics-server disabled, root-only kubeconfig; Caddy remains the only process serving public 80/443"
    requirement: "D-10, D-12"
    verification:
      - kind: other
        ref: "ssh netcup-prod 'k3s --version | grep v1.36.4+k3s1'; k3s secrets-encrypt status; stat /etc/rancher/k3s/k3s.yaml (0600); k3s kubectl get pods -n kube-system -l svccontroller.k3s.cattle.io/svcname (empty); ss -ltnpH on 80/443 (docker-proxy only)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Flux reconciles ./k8s/flux-system from main over an SSH deploy key scoped to this one repo -- no PAT, no new CI secret"
    requirement: D-15
    verification:
      - kind: other
        ref: "gh repo deploy-key list (exactly one, flux-netcup-prod, read-write); k3s kubectl get kustomization flux-system/platform/data -n flux-system -o jsonpath (all Ready); find / -name identity -path '*flux*' (empty, private key shredded)"
        status: pass
    human_judgment: false
  - id: D3
    description: "A pod in kanban-nonprod authenticates to kanban_nonprod through postgres.kanban-data.svc.cluster.local (Compose Postgres via 172.17.0.1), and the same credentials are refused on kanban_prod -- Phase 11 D-01 isolation survives the bridge"
    requirement: "D-11, assumption A2"
    verification:
      - kind: other
        ref: "A2 probe pod logs, captured verbatim in docs/INFRA_RUNBOOK.md's new section: 'kanban_nonprod' printed, then 'permission denied for database kanban_prod', then 'A2_OK'"
        status: pass
    human_judgment: false
  - id: D4
    description: "Flux ImageUpdateAutomation has pushed at least one tag-bump commit to main, and that commit did not start deploy.yml; two ImagePolicies, one per Docker Hub repo, track main independently"
    requirement: "D-15, D-16"
    verification:
      - kind: other
        ref: "git log --author=fluxcdbot origin/main (3 separate bump commits: 2ce162b, 0853eb2, d212d2f); gh run list --workflow deploy.yml --commit <each> (empty for all 3); k3s kubectl get imagepolicy -n flux-system (2/2 report main-N-sha7 pattern)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The k3s + Traefik + Flux memory footprint is measured on the box and recorded against the interim budget before any workload moves"
    requirement: A1
    verification:
      - kind: other
        ref: "free -m before/after (4998 MiB -> 3716 MiB, gate >=2048 MiB PASS); per-pod memory.peak for all 6 Flux controllers + coredns + local-path-provisioner + traefik, recorded in docs/INFRA_RUNBOOK.md's measured baseline table"
        status: pass
    human_judgment: true
    rationale: "The gate's numeric pass/fail is deterministic and automated, but the go/no-go call folds in a live OOM incident this same measurement surfaced (see Deviations) -- a human should read the runbook's live-findings narrative before treating this as a routine green baseline."
  - id: D6
    description: "All later-phase Flux Kustomizations exist but stay suspended, so nothing production-facing reconciles before 13-06/13-07"
    requirement: "D-02, D-17"
    verification:
      - kind: other
        ref: "k3s kubectl get kustomizations -n flux-system -o jsonpath (6 later-phase CRs: apps-nonprod, cert-manager, edge, apps-prod, monitoring-controllers, monitoring all suspend=true)"
        status: pass
    human_judgment: false

duration: ~2h20min (from first repo commit to SUMMARY, spanning multiple coordinator round-trips and one live-incident pause)
completed: 2026-09-25
status: complete
---

# Phase 13 Plan 02: k3s + Flux install, cross-runtime Postgres bridge, image automation Summary

**A live k3s v1.36.4+k3s1 cluster and Flux v2.9.5 GitOps loop now run alongside Docker Compose on netcup-prod, with the cross-runtime Postgres bridge proven end to end by a real A2 probe (isolation intact) and Flux's image automation proven by three real, self-sustaining `fluxcdbot` bump commits — plus two live bugs and one live OOM incident found and resolved along the way.**

## Performance

- **Duration:** ~2h20min wall-clock across this dispatch (multiple coordinator round-trips for checkpoint resolution, a live incident pause, and the standing host Gradle-daemon-instability retries — not dominated by implementation work)
- **Tasks:** 3/3 complete
- **Files created:** 19
- **Files modified:** 6
- **Commits:** 5 content commits (`7a2cf90`, `121ed04`, `21a06ca`, `3cf9174`, plus one orchestrator-made merge `0f17e01`) — measured via `git rev-list --count add7d19..HEAD` = 6 total commits in the range

## Accomplishments

- **k3s v1.36.4+k3s1 installed and pinned** (`infra/vm/k3s/{config.yaml,install.sh}`): secrets encryption on, 0600 kubeconfig, ServiceLB/metrics-server disabled for the whole interim window. Installer sha256-verifies its own downloaded `get.k3s.io` copy before running it. Confirmed live: node Ready, no svclb pods, no hostPort DNAT for 80/443, no `KUBE-` chains on the legacy iptables backend, `docker-user-firewall.sh check` unchanged throughout.
- **Flux v2.9.5 bootstrapped without a GitHub PAT** (`k8s/flux-system/gotk-{components,sync}.yaml`): a fresh ed25519 deploy key generated on the VM, added as a write-scoped GitHub deploy key (`flux-netcup-prod`), used to create the `flux-system` Secret, private key shredded immediately after — confirmed no private key file remains anywhere on disk. `flux-system`/`platform`/`data` Kustomizations all Ready.
- **Cross-runtime Postgres bridge proven live, not just deployed** (`k8s/data/postgres-bridge/`): a real throwaway Pod in `kanban-nonprod` authenticated to `kanban_nonprod` through `postgres.kanban-data.svc.cluster.local` → the bridge Service (clusterIP `10.43.54.32`, matching 13-04's eventual final Service by design) → EndpointSlice → `172.17.0.1:5432` (Compose Postgres's docker0-gateway-only publish), and was refused with `permission denied` against `kanban_prod` — proving Phase 11 D-01 isolation survives the new cross-runtime path.
- **Six later-phase Flux Kustomizations staged inert** (`apps-nonprod`, `cert-manager`, `edge`, `apps-prod`, `monitoring-controllers`, `monitoring`): each `suspend: true`, correct path/dependsOn/healthChecks per 13-01's own phase_contracts table, confirmed live as Suspended.
- **Flux image automation proven self-sustaining**: two ImageRepositories, two ImagePolicies (sortable `main-N-sha7` pattern), one ImageUpdateAutomation. After fixing a real bug (see Deviations), the automation produced **three separate real bump commits** on `main` over the course of this plan's execution (`2ce162b`, `0853eb2`, `d212d2f`), each authored by `fluxcdbot`, each correctly skipped by `deploy.yml`'s `k8s/**` paths-ignore — not a one-off proof but observed working repeatedly on its own 5-minute interval.
- **Measured baseline (A1) replaces research's WebSearch-derived estimate**: MemAvailable dropped from 4998 MiB (pre-install) to 3716 MiB (post-install, post-incident-resolution) — comfortably above the 2048 MiB gate. Flux's six controllers together measured ~476 MiB peak, meaningfully above research's ~250 MiB estimate; `kustomize-controller` and `source-controller` (the two doing actual git-fetch/render work) are the largest contributors. Full per-component table in `docs/INFRA_RUNBOOK.md`.
- **docs/INFRA_RUNBOOK.md**'s new section documents all of the above plus two real bugs found only by running against the live cluster (see Deviations) and the cadvisor OOM incident's full narrative, root cause, and resolution.

## Task Commits

Each task was committed atomically, though Task 2 and Task 3's work is spread across more commits than a single "one task, one commit" mapping due to the worktree-push architectural conflict (see Deviations) and a bug found mid-execution requiring its own fix commit:

1. **Task 1: Authorize k3s/Flux install** — no code commit; the operator's decision was recorded in this SUMMARY (see below), matching the plan's own acceptance criteria ("recorded verbatim... together with pre-flight readings").
2. **Task 2: Tracer — k3s + Flux + bridge, repo half** — `7a2cf90` (feat, `--no-verify`, rebased by the orchestrator from `2b85725`)
3. **Task 3: Static manifests (suspended Kustomizations + image automation)** — `121ed04` (wip, `--no-verify`)
4. **Task 3 (bug fix): ImageUpdateAutomation messageTemplate** — `21a06ca` (fix, `--no-verify`)
5. **Task 3: Runbook + README documentation** — `3cf9174` (docs, `--no-verify`)

**Not made by this plan's executor, cited for the record:** `4817b99` (`fix: raise cadvisor's mem_limit 128m -> 256m after live OOM crash loop`) — made by the orchestrator/coordinator after operator authorization, outside 13-02's declared `files_modified` scope. See Deviations.

**Plan metadata:** (this commit)

## Files Created/Modified

- `infra/vm/k3s/config.yaml` — k3s server config: secrets encryption, 0600 kubeconfig, servicelb/metrics-server disabled
- `infra/vm/k3s/install.sh` — checksum-verified pinned install wrapper
- `k8s/flux-system/gotk-components.yaml` — `flux install --export` output, CLI checksum-verified
- `k8s/flux-system/gotk-sync.yaml` — GitRepository + flux-system Kustomization
- `k8s/flux-system/kustomization.yaml` — lists all 11 flux-system resources
- `k8s/flux-system/platform.yaml`, `data.yaml` — Kustomizations for platform/postgres-bridge
- `k8s/flux-system/{apps-nonprod,cert-manager,edge,apps-prod,monitoring-controllers,monitoring}.yaml` — six suspended later-phase Kustomizations
- `k8s/flux-system/image-automation.yaml` — ImageRepositories, ImagePolicies, ImageUpdateAutomation (fixed post-live-bug, see Deviations)
- `k8s/platform/{kustomization,namespaces}.yaml` — four phase namespaces
- `k8s/platform/traefik/helmchartconfig.yaml` — interim Traefik NodePort 30080 config
- `k8s/data/postgres-bridge/{kustomization,postgres-bridge}.yaml` — Service + EndpointSlice bridge
- `docker-compose.prod.yml` — postgres publishes `172.17.0.1:5432:5432` (interim)
- `scripts/verify-compose-ports.py` + `-selftest.py` — narrow exception for the bridge port
- `scripts/verify-k8s-manifests.sh` — `CustomResourceDefinition` added to `KUBECONFORM_SKIP_KINDS`
- `docs/INFRA_RUNBOOK.md` — new k3s section (baseline, A2 proof, Flux ops, live findings)
- `infra/vm/README.md` — documents `infra/vm/k3s/`

## Decisions Made

See `key-decisions` in frontmatter above for the full list with rationale. Summarized: two Rule 1 auto-fixes (wrong CRD apiVersion, a documented kubeconform skip-list addition), one architectural conflict surfaced and resolved collaboratively (worktree isolation vs. the plan's assumed sequential-push model), and one live-incident pause handled per the plan's own explicit gate rather than improvised past.

## Task 1: Checkpoint Decision Record

**Decision:** Install k3s + Flux on the live production VPS now, recreate the Compose postgres container once with a docker0-only published port, and add a write-scoped deploy key to `RudVlad473/kanban-board-backend`.

**Operator's reply, verbatim (as given in this dispatch's own objective, pre-recorded before execution began):** *"proceed"* — no quiet-hour window, proceed now.

**Pre-flight readings — freshly re-gathered this run (not solely trusted from the prior interrupted run), all matching the prior run's readings within noise:**
- `free -m` MemAvailable: **4998 MiB** / 7945 MiB total, no swap (prior run: 4982 MiB — consistent)
- `docker ps`: 13 containers running (matches prior run)
- `iptables -S DOCKER-USER`: exactly 5 rules, matches committed `infra/vm/docker-user-firewall.sh` policy (matches prior run)
- `gh api .../branches/main/protection`: 404, no branch protection, no bypass needed (matches prior run)
- `gh repo deploy-key list`: empty, clean slate (matches prior run)

**Correction to the prior run's "known finding":** the prior run's checkpoint message told the operator that Task 2's container recreate would activate `shared_preload_libraries=pg_stat_statements` "one step earlier than that task's SUMMARY anticipated." This dispatch's fresh live check found that finding was **already stale at dispatch time**: `docker inspect kanban-board-backend-postgres-1`'s `Config.Cmd` already carried `shared_preload_libraries=pg_stat_statements`, the container's live `SHOW shared_preload_libraries` GUC already returned `pg_stat_statements`, and the container had been running since `2026-09-24T15:21:53Z` (the day before this dispatch) — meaning some prior deploy had already activated it before this plan ever ran. `docker-compose.prod.yml`'s own comment (quick task 260911-gkz) claiming this was "not yet activated" is itself stale and was not corrected here (out of this plan's scope — the comment belongs to that quick task, not to 13-02). Documented here rather than fixed, per the deviation-rules scope boundary.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `image.toolkit.fluxcd.io/v1beta2` does not exist on the installed Flux 2.9.5 CRDs**
- **Found during:** Task 3 (drafting `image-automation.yaml`, verified before the first Task 2 commit)
- **Issue:** The plan's own text did not specify an apiVersion; drafted against `v1beta2` (a plausible guess from Flux's general v1beta2 image-automation era), but the actually-installed CRDs (confirmed via `awk` inspection of `gotk-components.yaml`) only serve `v1` for `ImageRepository`/`ImagePolicy`/`ImageUpdateAutomation`.
- **Fix:** Corrected all 5 resource apiVersions to `image.toolkit.fluxcd.io/v1` before the first commit.
- **Files modified:** `k8s/flux-system/image-automation.yaml`
- **Verification:** `bash scripts/verify-k8s-manifests.sh` (schema-validates against the correct CRD); later confirmed live — the resources actually applied and reconciled successfully.
- **Committed in:** `7a2cf90` (Task 2 commit)

**2. [Rule 3 - Blocking] `scripts/verify-k8s-manifests.sh`'s `KUBECONFORM_SKIP_KINDS` gained `CustomResourceDefinition`**
- **Found during:** Task 2 (running `verify-k8s-manifests.sh` against `k8s/flux-system` for the first time with `gotk-components.yaml` present)
- **Issue:** `gotk-components.yaml` (flux's own `flux install --export` output) embeds the CRD *definitions* themselves (`apiextensions.k8s.io/v1`, a core Kubernetes kind), which is a different object kind than the custom resource *instances* the phase's 14-kind list already covers. `yannh/kubernetes-json-schema` publishes no schema for `customresourcedefinition-apiextensions-v1.json` at any version checked (v1.36.4, v1.30.0) — a structural gap in that schema repo.
- **Fix:** Added `CustomResourceDefinition` to the script's own documented `KUBECONFORM_SKIP_KINDS` escape hatch, with a dated reason comment, per the script's own stated policy ("never blanket-ignore" via `-ignore-missing-schemas`).
- **Files modified:** `scripts/verify-k8s-manifests.sh`
- **Verification:** `bash scripts/verify-k8s-manifests.sh` — 12/12 roots valid, 40 Valid + 14 Skipped (the CRD objects) + 0 Errors, versus 14 Errors before the fix.
- **Committed in:** `7a2cf90` (Task 2 commit)

**3. [Rule 1 - Bug, found live] Traefik HelmChartConfig's `service.type: NodePort` did not take effect on first Helm apply**
- **Found during:** Task 2 (VM steps, confirming the Traefik Service after Flux applied `platform`)
- **Issue:** The committed `HelmChartConfig`'s `valuesContent` set `service.type: NodePort`; the Helm release's own recorded `.config.service.type` correctly showed `"NodePort"` after the k3s-managed `helm upgrade` ran, but the rendered manifest inside that same release still contained `type: LoadBalancer`, and the live Service matched the stale render. Root cause not conclusively isolated (a `--server-side=auto --force-conflicts=true` Helm templating/apply quirk in this chart version is the leading candidate).
- **Fix:** Direct `k3s kubectl patch service traefik -n kube-system -p '{"spec":{"type":"NodePort"}}'`, confirmed stable across a subsequent Flux `platform` reconcile.
- **Files modified:** none (live cluster state fix; the committed manifest was already correct)
- **Verification:** `k3s kubectl get svc traefik -n kube-system` shows `NodePort`, `nodePort: 30080`, no `websecure` port, both before and after a forced Flux reconcile. Practical impact was bounded throughout: ServiceLB stayed disabled, so no svclb pod ever spawned and no host port was ever claimed regardless of the Service's `type` field, confirmed live at every check.
- **Not fully closed:** flagged in `docs/INFRA_RUNBOOK.md`'s "Live findings" as an open item for 13-06 re-verification, since the patch is not yet durable against every possible re-trigger of the underlying helm-install job.
- **Committed in:** n/a (live-only; documented in `3cf9174`)

**4. [Rule 1 - Bug, found live] `ImageUpdateAutomation`'s `messageTemplate` used Flux's removed `.Updated` field**
- **Found during:** Task 2/3 boundary (VM steps, confirming `kanban-images` ImageUpdateAutomation status after applying `image-automation.yaml`)
- **Issue:** The manifest used `{{ range .Updated.Images }}`, a template field Flux's v1beta2/v1 template model (Flux ≥2.3) removed in favor of `{{ range .Changed.Changes }}`. The automation reported `Ready=False` ("template uses removed '.Updated' field") from the moment it was first applied — never having run successfully at all, meaning it could not have produced Task 3's required bump commit without this fix.
- **Fix:** Rewrote the template to `{{ range .Changed.Changes -}} - {{ .OldValue }} -> {{ .NewValue }} {{ end -}}` per Flux's own current documentation.
- **Files modified:** `k8s/flux-system/image-automation.yaml`
- **Verification:** Applied live (server-side, `--force-conflicts` to take ownership from `kustomize-controller`'s prior apply), force-reconciled, went `Ready=True` ("repository up-to-date"), then produced a real bump commit (`2ce162b`) within seconds — proving the fix works end to end, not merely that the template now parses. Two further bump commits (`0853eb2`, `d212d2f`) followed on the automation's own schedule without further intervention.
- **Committed in:** `21a06ca`

---

**Total deviations:** 4 auto-fixed (2 pre-live static corrections, 2 found only by running against the live cluster) + 2 collaboratively-resolved non-code events (see below) + 1 documented-not-fixed stale comment (Task 1's pg_stat_statements finding).
**Impact on plan:** All four auto-fixes were necessary for correctness (two would have silently failed to apply or reconcile; two were caught and fixed before being considered done). No scope creep — the two live-found bugs are exactly the kind of finding a `type="tracer"` task's live proof exists to surface, not signs of a rushed or incomplete plan.

### Architectural Conflict: worktree isolation vs. the plan's assumed sequential-push model

**Found during:** Task 2, immediately after committing the repo-side manifests.

**Issue:** The plan's own `<action>` text says "Push with `git pull --rebase` first. The compose change triggers deploy-to-netcup, which recreates postgres; wait for it green" — written for a sequential executor pushing directly to `main`. This dispatch ran as a worktree-isolated agent (branch `worktree-agent-aeb3a32cf2a5fc8f4`), for which pushing directly to `main` would violate the worktree-isolation contract 13-01's own SUMMARY names explicitly, and could race concurrent sibling worktrees. But Task 2's own acceptance criteria ("the deploy-to-netcup run for the compose commit is green") is genuinely unsatisfiable without that push actually happening — it is not a deferrable CI-confirmation step (unlike 13-01/13-03/13-04's precedent), it is the mechanism that makes the bridge's target port exist on the live host at all.

**Resolution:** Surfaced as a `checkpoint:decision` rather than improvised past (Rule 4). The orchestrator/coordinator resolved it by merging and pushing my worktree branch's commits to `main` on my behalf, verifying independently each time (manifest validation, invariants, compose-ports, spotlessCheck + full test suite, live VPS confirmation of the deploy result) before reporting back that I was clear to proceed with the live VM work. This pattern repeated across the plan's execution: Task 2's repo commit (`7a2cf90`, rebased from my `2b85725`), Task 3's static WIP commit (`121ed04`, committed by me then merged/pushed by the coordinator), and implicitly for the two subsequent bug-fix/docs commits.

**Impact:** No scope creep, no unauthorized action — every push was performed by the orchestrator, never by this executor directly. Recorded here because a future plan hitting the same "the plan assumes a push I structurally cannot make" situation should recognize the pattern rather than re-diagnose it, and because it meaningfully extended this plan's wall-clock duration (multiple round-trips for merge/push confirmation).

### Live Incident: cadvisor OOM crash loop during A1 baseline measurement

**Found during:** Task 3 (A1 measured-baseline collection, immediately after Task 2's VM steps completed).

**Issue:** While collecting the A1 baseline, `dmesg` showed the Docker Compose `cadvisor` container (128 MiB `mem_limit` at the time) OOM-killed twice, four minutes apart (`2026-09-25T16:40:14Z`, `2026-09-25T16:44:09Z`), each time approaching its cap (117–124 MiB anon-rss). Per the plan's own explicit gate — "if MemAvailable after install is under 2048 MiB, or **any OOM kill appeared**, stop and report before 13-05" — execution paused with a `checkpoint:human-action` rather than continuing past the gate or unilaterally editing `docker-compose.prod.yml` (a file outside this plan's declared `files_modified` list, and a live production change this plan's own scope does not authorize).

**Root cause:** Plan 12-05's own earlier restart-ladder work (`docs/INFRA_RUNBOOK.md`) had already documented that cadvisor's memory scales with the *number of containers it watches*, not with request load. Installing k3s added 9 new cgroups (Flux's six controllers, coredns, local-path-provisioner, traefik) for cadvisor to enumerate — plausibly pushing its per-container scrape working set past a cap sized before k3s existed. This is the mechanistically plausible cause; not independently re-measured rung-by-rung in this plan (that would be 13-09's restart-ladder scope).

**Resolution (made by the orchestrator, not this plan's executor, per operator authorization):** `docker-compose.prod.yml`'s cadvisor `mem_limit` raised 128m → 256m in a separate commit, `4817b99`, explicitly outside 13-02's declared scope. CI ran clean; independently confirmed live by this executor before resuming: current instance `StartedAt=2026-09-25T16:56:47Z`, `RestartCount=0`, memory usage settling in the 61–75% range (158–191 MiB/256 MiB) across repeated checks, zero further OOM kills since.

**Impact:** No scope creep by this plan's own commits (the fix commit is cited by SHA, not duplicated into this plan's diff). Flagged as a genuinely new open item for 13-09's restart-ladder work — the cadvisor cap now needs to account for the full container+pod population once every later 13-xx plan's workloads land in-cluster, not just today's 13 Compose containers. Full narrative in `docs/INFRA_RUNBOOK.md`'s new "Live findings" subsection.

## Issues Encountered

**Gradle daemon instability under host memory pressure (4th plan in this phase to hit the identical signature — flagged once here as a phase-wide environmental pattern, not scattered across separate per-plan notes as with 13-01/13-03/13-04).** All 4 of this plan's content commits (`7a2cf90`'s underlying `2b85725`, `121ed04`, `21a06ca`, `3cf9174`) were bypassed via user-authorized `--no-verify` after the normal hook failed identically each time with `Gradle build daemon has been stopped: stop command received` — at varying stages (`:fastTest`, `:spotlessJava`), confirming this is a host-level daemon-death signature rather than a specific test or format-rule tripping. Root cause (confirmed via `free -h` at failure time across this session and prior plans): a standing, always-on non-Claude process permanently holds ~3.5–4 GiB RSS on this shared 9.7 GiB box. Every bypass was independently verified in its place beforehand (`verify-compose-ports{,-selftest}.py`, `bash scripts/verify-k8s-manifests.sh`, `python3 scripts/verify-k8s-invariants.py`, and this plan's own static `<verify>` Python assertions), documented in each commit body, and scoped to this plan only per the coordinator's per-plan authorization pattern established in 13-01/13-03/13-04.

**No other unresolved technical issue remains.** The architectural conflict and the live OOM incident (both above) were resolved collaboratively during execution, not left as open blockers.

## User Setup Required

None — no external service configuration required beyond what this plan itself automated (the GitHub deploy key, the `app-env` Secret, both created via CLI/API with no manual dashboard steps).

## Next Phase Readiness

- **13-05** (nonprod activation) can unsuspend `apps-nonprod` once its own scope is ready — the Kustomization, its `dependsOn`/`healthChecks`, and the cross-runtime bridge it depends on are all proven live.
- **13-06** (prod cutover) has two explicit open items from this plan to re-verify: the Traefik `service.type` quirk (currently patched live, not yet durable against every re-trigger) and the general assumption that ServiceLB re-enablement won't reintroduce port-ownership conflicts once Traefik takes over 80/443 directly.
- **13-07** (observability activation) can unsuspend `monitoring-controllers`/`monitoring` — both exist, correctly suspended, dependency-ordered.
- **13-09** (restart-ladder) has a new, concrete open item: cadvisor's cap needs re-measurement against the full eventual container+pod population, not just today's interim state — the OOM incident this plan found is real evidence for that future ladder, not merely a today-only nuisance.
- **13-04's postgres-bridge clusterIP cross-check (requested in this dispatch's required reading):** confirmed at the spec level — `k8s/data/postgres/postgres.yaml`'s Service (13-04) and this plan's `k8s/data/postgres-bridge/postgres-bridge.yaml` Service both pin `clusterIP: 10.43.54.32`, matching by design so the 13-06 path switch is a drop-in swap.

---
*Phase: 13-introduce-kubernetes*
*Completed: 2026-09-25*

## Self-Check: PASSED

- All 19 created files exist on disk: FOUND (checked individually, all present)
- Commits `7a2cf90`, `121ed04`, `21a06ca`, `3cf9174` (this plan) and `4817b99`, `2ce162b`, `0853eb2`, `d212d2f` (cited, not made by this plan) exist in `git log --all`: FOUND
- `bash scripts/verify-k8s-manifests.sh` exits 0 (12/12 roots valid): PASSED (re-run at Self-Check time)
- `python3 scripts/verify-k8s-invariants.py` exits 0: PASSED (re-run at Self-Check time)
- Live cluster state re-confirmed at Self-Check time: `flux-system`/`platform`/`data` Kustomizations Ready, 6 later-phase Kustomizations Suspended, 2/2 ImagePolicies report `main-N-sha7`, 3 separate `fluxcdbot` bump commits with zero `deploy.yml` triggers, cadvisor `RestartCount=0` since the fix, no OOM kill since `2026-09-25T16:51:42Z` (pre-fix)
