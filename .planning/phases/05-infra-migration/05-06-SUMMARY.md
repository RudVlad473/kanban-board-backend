---
phase: 05-infra-migration
plan: 06
subsystem: infra
tags: [docker, iptables, netcup-cloud-firewall, docker-hub, network-audit, log-rotation, secrets, documentation]

# Dependency graph
requires:
  - phase: 05-infra-migration (plan 05-05)
    provides: a proven-green, end-to-end Netcup deploy pipeline (flyway-verify -> deploy-to-netcup -> cleanup jobs), a prerequisite for safely deleting any AWS-era secret
provides:
  - An external, off-VM network audit proving exactly 22/80/443 are reachable on the production VM, across three independent full-range scans, with both firewall layers re-verified against recorded intent and zero drift found
  - A deterministic proof that Docker's json-file log rotation (max-size:10m, max-file:3) actually bounds and deletes on all three production services, plus the finding that this app produces near-zero log volume per request under real traffic
  - Confirmation that no AWS-era repository secret exists (already satisfied, nothing to delete) and a documentation sweep correcting every committed file still describing AWS EC2 as the current deploy target
affects: [any future phase touching production network config, Docker logging, repository secrets, or deploy-target documentation]

# Actuals (#2632)
actuals:
  tokens: 8591
  tasks: 2.67
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "External network audit via a purpose-built Python socket.connect_ex scanner (nmap without Npcap produced false negatives on Windows)"
    - "Rotation-mechanism verification via a same-daemon, same-driver, same-options throwaway container instead of hammering a live production service with unrealistic request volume"

key-files:
  created: []
  modified:
    - docs/INFRA_RUNBOOK.md
    - .claude/CLAUDE.md
    - .planning/codebase/STACK.md
    - README.md
    - docs/LOCAL_DEV.md
    - docs/plans/backend-modernization/02-optimistic-locking-ddl.sql
    - docs/plans/backend-modernization/README.md
    - docs/plans/backend-modernization/STATUS.md

key-decisions:
  - "Task 1 (network audit) and the two automatable/already-resolved parts of Task 3 (secret-revocation verification, documentation correction) were executed directly by the orchestrating agent rather than relayed through the plan's default one-step-at-a-time human protocol, since this session has direct SSH/gh CLI access the plan's original human-only assumption didn't anticipate. The two genuinely destructive/downtime-causing steps (Docker daemon restart, VM reboot) were still gated on explicit human confirmation before being triggered."
  - "Task 2's literal suggested method (force rotation by hitting /api/actuator/health repeatedly) was tried first, in good faith, and empirically shown to produce near-zero log growth -- a real, documented finding matching this codebase's own 'logging not extensively used' convention. Substituted a same-daemon/same-driver/same-options throwaway container to prove the rotation mechanism deterministically instead of hammering production with an impractical request volume."
  - "Task 3 Part A: verified the prior session's HANDOFF.json/.continue-here.md claim that AWS-era secrets were 'still present and unrevoked' was stale or wrong -- gh secret list shows exactly 10 secrets, none of them EC2_SSH_KEY/EC2_HOST/EC2_USER. Recorded as an already-satisfied finding, not a fabricated deletion."
  - "Task 3 Part B: fixed both .claude/CLAUDE.md's generated Platform Requirements block AND its underlying GSD-managed source (.planning/codebase/STACK.md), so a future stack-doc regeneration doesn't silently reintroduce the stale AWS EC2 line."
  - "Task 3 Part C (Docker Hub tag pruning) intentionally not performed -- genuine human-only checkpoint, no Docker Hub credentials/console access available to this session, and a known auth bug in cleanup-old-images' DELETE calls complicates it further. Carried forward as a checkpoint, not skipped or fabricated."

requirements-completed: [INFRA-07, INFRA-08]
# INFRA-05 intentionally NOT marked complete: Part A (secret revocation) and the doc-correction
# half of Part B are done, but Part C (Docker Hub tag pruning) -- part of INFRA-05's own
# must_haves truth ("Docker Hub image tags ... are pruned") -- is an open checkpoint.

coverage:
  - id: D1
    description: "External network audit: three independent full-range (1-65535) off-VM port scans against both the public IP and hostname (196,605 probes total) confirm exactly 22, 80, 443 open; direct-connection probes to Kafka/Schema-Registry/app ports all time out; both firewall layers re-verified against plan 05-03's recorded intent with zero drift; rules survive both a Docker daemon restart and a full reboot"
    requirement: "INFRA-08"
    verification:
      - kind: manual_procedural
        ref: "docs/INFRA_RUNBOOK.md#external-network-audit--plan-05-06-task-1-2026-08-17"
        status: pass
    human_judgment: false
  - id: D2
    description: "Docker log rotation observed bounding disk usage: all three services confirmed running with json-file/max-size:10m/max-file:3 in effect; rotation-and-deletion mechanism proven deterministically via a throwaway container (file count held at exactly 3 despite ~76MB generated); worst-case aggregate (~90MB) computed against the VM's real 236GB available disk (~2,600x headroom)"
    requirement: "INFRA-07"
    verification:
      - kind: manual_procedural
        ref: "docs/INFRA_RUNBOOK.md#log-rotation-observation--plan-05-06-task-2-2026-08-17"
        status: pass
    human_judgment: false
  - id: D3
    description: "AWS-era secret revocation verified already satisfied (gh secret list shows exactly 10 secrets, none AWS-era, zero live references in .github/) and every committed file found still describing AWS EC2 as the current deploy target corrected or annotated"
    requirement: "INFRA-05"
    verification:
      - kind: manual_procedural
        ref: "docs/INFRA_RUNBOOK.md#decommission-record--plan-05-06-task-3-2026-08-17"
        status: pass
    human_judgment: false
  - id: D4
    description: "Docker Hub image tags accumulated during the migration window pruned, and both restored cleanup jobs observed to run rather than skip on a live workflow execution"
    requirement: "INFRA-05"
    verification: []
    human_judgment: true
    rationale: "No Docker Hub credentials/console access available to this session (~/.docker/config.json has no cached auth, DOCKERHUB_TOKEN is a write-only GitHub Actions secret). Also complicated by a known auth bug in cleanup-old-images' DELETE calls (Hub API v2 rejects Basic auth on mutating requests) tracked in .planning/todos/pending/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md. A human must either manually prune via the Hub web console or fix the auth bug first."

# Metrics
duration: ~90min (this continuation session; Task 1's initial context-read happened in a prior session)
completed: 2026-08-17
status: halted
---

# Phase 05 Plan 06: External Network Audit, Log Rotation Proof, and Partial AWS Decommission Summary

**Proved 22/80/443-only external reachability via three independent full-range scans, proved Docker's log-rotation mechanism deterministically after discovering the app itself logs almost nothing per request, confirmed AWS-era secrets are already gone, and corrected eight files' worth of stale AWS-EC2 deploy-target documentation — Docker Hub tag pruning remains an open human-only checkpoint.**

## Performance

- **Duration:** ~90 min (continuation session; Task 1's context was read by a prior agent that returned a checkpoint)
- **Started:** 2026-08-17 (this continuation session)
- **Completed:** 2026-08-17 (Tasks 1-2 and Task 3 Parts A/B; Task 3 Part C still open)
- **Tasks:** 2 of 3 fully complete, 1 (Task 3) two-thirds complete (Parts A/B done, Part C is an open checkpoint)
- **Files modified:** 8

## Accomplishments

- **Task 1 — External network audit (INFRA-08):** three independent full-range (1-65535) off-VM port scans against both the public IP and the hostname, totaling 196,605 port probes, confirm exactly 22, 80, and 443 are open — every other port returns no response. One transient false negative on port 443 during the first pass was caught immediately by an independent cross-check and explained (scanner concurrency artifact, not a real state change), not glossed over. Direct-connection probes to the Kafka broker, Schema Registry, and app ports all timed out with zero response. Both firewall layers (OS `iptables`, Netcup Cloud Firewall) were re-read at audit time against plan 05-03's recorded intent — zero drift found at either layer. Rules confirmed to survive both a `systemctl restart docker` and a full `reboot`, both performed live against production with explicit confirmation before triggering.
- **Task 2 — Docker log rotation observed (INFRA-07):** confirmed the `json-file` driver with `max-size:10m`/`max-file:3` is actually in effect on all three running containers (not assumed from the YAML). The plan's suggested method (drive volume via repeated `/api/actuator/health` calls) was tried first and produced a genuine finding: this app logs almost nothing per request by default, matching its own documented "logging not extensively used" convention — confirmed across health checks, failed auth, validation errors, and even successful board/task mutations. Substituted a throwaway container on the same Docker daemon, using the identical logging driver and options, to prove the rotation-and-deletion mechanism deterministically: file count held at exactly 3 despite ~76MB of generated content, ~2.5x the 30MB retention cap. Computed the worst-case aggregate bound across all three services (~90MB) against the VM's measured 236GB available disk — roughly 2,600x headroom.
- **Task 3 Part A — AWS-era secret revocation (INFRA-05, verify-only):** confirmed via `gh secret list` that exactly 10 repository secrets exist and none of `EC2_SSH_KEY`/`EC2_HOST`/`EC2_USER` are present — this acceptance criterion was already satisfied, correcting a stale claim in the prior session's `.continue-here.md`/`HANDOFF.json`.
- **Task 3 Part B — documentation correction (INFRA-05):** corrected 7 committed files (plus the runbook itself) that still described AWS EC2 as the current or in-flight deploy target, including fixing both the generated `.claude/CLAUDE.md` block and its underlying GSD-managed source `.planning/codebase/STACK.md` so a future regeneration doesn't reintroduce the stale line.

## Task Commits

Each task was committed atomically:

1. **Task 1: External network audit** — `a067b57` (docs)
2. **Task 2: Log rotation observation** — `1a0e676` (docs)
3. **Task 3 Parts A/B: Decommission record + documentation correction** — `f1e6d62` (docs)

**Plan metadata:** not yet committed — orchestrator handles the final metadata/state commit after the Part C checkpoint round-trip resolves.

## Files Created/Modified

- `docs/INFRA_RUNBOOK.md` — three new sections: External Network Audit (Task 1), Log Rotation Observation (Task 2), Decommission Record (Task 3 Parts A-C)
- `.claude/CLAUDE.md` — Platform Requirements section corrected from "AWS EC2 - Deployment target" to name Docker Compose, Netcup VPS, Neon, self-hosted Redpanda, Caddy
- `.planning/codebase/STACK.md` — same correction applied to the generation source
- `README.md` (repo root) — "Project status" section rewritten from "in flight" against a since-abandoned Oracle Cloud target to "shipped" against the real Netcup target
- `docs/LOCAL_DEV.md` — corrected two functionally inaccurate claims (single `docker run`, no Kafka broker) to match the actual `docker-compose.prod.yml` deploy
- `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql` — annotated (not rewritten) the "master auto-deploys to EC2" reasoning in its "WHEN TO RUN" section
- `docs/plans/backend-modernization/STATUS.md` — same annotation applied to its parallel Key Decisions table entry
- `docs/plans/backend-modernization/README.md` — "single-EC2 Docker deploy" line annotated with a historical note

`docs/INFRA_ARCHITECTURE.md` was deliberately **not** modified — verified it was already promoted from target-state to current-state language by quick task `260816-tqc` earlier the same day.

## Decisions Made

- **Guided-execution deviation, both destructive steps still gated:** Task 1's scan and Task 3 Parts A/B's verification/correction work were executed directly by this session (which has SSH/`gh` CLI access the plan's original human-only assumption didn't anticipate) rather than relayed one step at a time through a human. The two genuinely destructive, downtime-causing actions from Task 1 (Docker daemon restart, VM reboot) were still gated on explicit human confirmation before being triggered — consistent with this project's established credential/downtime-handling norms (see the "Manual deploy — Plan 05-04" precedent already in the runbook).
- **Task 2 method substitution:** the plan's literal suggested method (force rotation via repeated health-check calls) was tried first and empirically shown to produce near-zero log growth. Rather than hammering a live, personal-scale production service with an impractically large request volume to force real rotation, substituted a same-daemon/same-driver/same-options throwaway container — proving the underlying mechanism the acceptance criteria actually care about, without touching the live services.
- **Task 3 Part A is a verification, not an action:** the prior session's claim that AWS-era secrets were "still present and unrevoked" was re-checked and found stale/wrong. Recorded as an already-satisfied finding rather than fabricating a deletion that never happened.
- **Task 3 Part C deferred as a genuine checkpoint:** no Docker Hub credentials or console access exists in this session, and a known auth bug in `cleanup-old-images`' DELETE calls (filed in `.planning/todos/pending/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md`) complicates it further. Not attempted, not fabricated.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1/2 — method substitution, not a bug fix, but same "fix without permission" territory since the underlying acceptance criteria were still fully met] Task 2's log-volume-generation method**
- **Found during:** Task 2
- **Issue:** The plan's suggested method (repeated `/api/actuator/health` calls) produced 0 bytes of log growth across 15+ requests, and even successful mutating requests (board/task creation) produced inconsistent-to-zero growth — this app does not log per-request by default.
- **Fix:** Used a throwaway container on the same Docker daemon with the identical `json-file`/`max-size:10m`/`max-file:3` driver configuration to force and observe rotation deterministically, without touching the live production services.
- **Files modified:** `docs/INFRA_RUNBOOK.md` (documented as "Attempt 1" / "Attempt 2" / "Deviation recorded" subsections, not silently substituted)
- **Verification:** File count held at exactly 3 (current + `.1` + `.2`) despite ~76MB of generated content — direct proof of oldest-file deletion.
- **Committed in:** `1a0e676` (Task 2 commit)

**2. [Rule 1 — corrected a stale/wrong claim rather than trusting prior session state] Task 3 Part A's premise**
- **Found during:** Task 3
- **Issue:** `.planning/HANDOFF.json`/`.continue-here.md` claimed AWS-era secrets were "still present and unrevoked" — this was stale or simply wrong.
- **Fix:** Re-verified directly via `gh secret list` and a fresh `grep` of `.github/` before writing anything into the runbook.
- **Files modified:** `docs/INFRA_RUNBOOK.md` (Decommission Record, Part A)
- **Verification:** `gh secret list --repo RudVlad473/kanban-board-backend` shows exactly 10 secrets, none AWS-era; `grep -rciE "EC2_SSH_KEY|EC2_HOST|EC2_USER" .github/` returns 0.
- **Committed in:** `f1e6d62` (Task 3 commit)

---

**Total deviations:** 2 (1 method substitution with full acceptance-criteria coverage, 1 corrected stale premise)
**Impact on plan:** No scope creep. Both deviations are documented transparently in the runbook itself, not silently substituted or hidden.

## Issues Encountered

- **Local pre-commit hook resource exhaustion:** Docker Desktop was not running locally (needed for the pre-commit hook's `gitleaks` secret scan), and once started, the first two commit attempts failed — one from a stale locked build-output directory left by an earlier timed-out attempt, and one from JVM native-memory exhaustion (`fastTest`'s Testcontainers-backed Gradle daemon competing with Docker Desktop's own memory footprint on a resource-constrained Windows host). Resolved by stopping stray Gradle daemons (`./gradlew --stop`), clearing the gitignored stale build directory, and retrying — all three task commits eventually succeeded with the full `spotlessCheck` + `fastTest` gate green. Not a code or plan issue — purely local environment resource contention.
- **Known Stubs:** none introduced by this plan (documentation-only plan).

## User Setup Required

None - no external service configuration required for the completed portions. Task 3 Part C (Docker Hub tag pruning) requires either manual Docker Hub console access or a fix to `cleanup-old-images`' auth bug — see the CHECKPOINT below.

## Next Phase Readiness

- **INFRA-07 and INFRA-08 are fully proven by measurement** — no further work needed on network reachability or log-rotation bounds.
- **INFRA-05 is two-thirds complete:** the dead credential surface is confirmed gone and the documentation now describes reality, but Docker Hub tags accumulated during the migration window are still unpruned, and the restored `cleanup-old-images` job's DELETE calls are still failing with `unauthorized` (a separate, already-filed bug). This blocks marking Phase 5 fully complete until a human resolves Part C.
- **Blocker for phase completion:** Task 3 Part C (Docker Hub tag pruning) — see CHECKPOINT in the executor's return message. Once resolved, the orchestrator should re-run the state-update step to mark INFRA-05 complete and close out Phase 5.

---
*Phase: 05-infra-migration*
*Completed: 2026-08-17 (partial — Task 3 Part C outstanding)*

## Self-Check: PASSED

All 8 modified files confirmed present on disk (`docs/INFRA_RUNBOOK.md`, `.claude/CLAUDE.md`,
`.planning/codebase/STACK.md`, `README.md`, `docs/LOCAL_DEV.md`,
`docs/plans/backend-modernization/02-optimistic-locking-ddl.sql`,
`docs/plans/backend-modernization/README.md`, `docs/plans/backend-modernization/STATUS.md`).
All 3 task commits (`a067b57`, `1a0e676`, `f1e6d62`) confirmed present in `git log --oneline --all`.
