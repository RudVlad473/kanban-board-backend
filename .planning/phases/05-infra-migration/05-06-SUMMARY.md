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
  - "Task 3 Part C (Docker Hub tag pruning): closed in a follow-on continuation of this same plan, after this SUMMARY was first written and committed (74139a3) as 'halted' on this exact checkpoint. Two commits landed live-tested rather than guessed: 8a31d85 fixed the JWT-token-exchange auth bug (Hub API v2 rejects Basic auth on mutating requests) -- its first live run (CI run 32016633112) succeeded but exposed a second, previously-invisible bug (only 9 of 41 accumulated tags deleted, since the tag-list call only ever read page 1 of Hub API v2's 10/page pagination); faacda4 fixed that by following the `next` cursor until null. CI run 32017867204 (2026-08-17T09:58Z, commit faacda4) is the live proof: `cleanup-old-images` job succeeded, all 32 remaining non-current tags issued a `DELETE`, `FAILED=0` (the job's own explicit per-call HTTP-status check, not a body-content guess), zero `::warning`/`::error` lines. This satisfies the todo's 'verify live, don't trust documentation' standard through the job's own status-code checking rather than a separate follow-up GET -- the mechanism that would surface a partial failure (the FAILED counter driving a non-zero exit) is the same one that already ran clean."

requirements-completed: [INFRA-05, INFRA-07, INFRA-08]
# INFRA-05 completed in two parts: Part A (secret revocation) and the doc-correction half of
# Part B were done when this SUMMARY was first written; Part C (Docker Hub tag pruning) closed
# afterward via commits 8a31d85 (JWT auth fix) and faacda4 (pagination fix), live-verified by
# CI run 32017867204 -- see the key-decisions entry above and coverage item D4.

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
    verification:
      - kind: ci_run
        ref: "https://github.com/RudVlad473/kanban-board-backend/actions/runs/32017867204"
        status: pass
    human_judgment: false
    rationale: "Closed in a follow-on continuation after this plan first halted here. Root cause was two independent bugs, each found and fixed live rather than guessed: (1) Hub API v2 rejects Basic auth on mutating requests -- fixed via JWT token exchange (commit 8a31d85); its first live run deleted only 9/41 tags, surfacing (2) the tag-list call only reading page 1 of a 10/page-paginated response -- fixed by following the `next` cursor until null (commit faacda4). CI run 32017867204 confirms both fixes together: 32 tags deleted, the job's own explicit per-call HTTP-status check (FAILED=0) reported zero failures, job concluded success."

# Metrics
duration: ~90min (this continuation session; Task 1's initial context-read happened in a prior session) + a later same-day continuation closing Task 3 Part C (2 commits, 8a31d85 + faacda4)
completed: 2026-08-17
status: complete
---

# Phase 05 Plan 06: External Network Audit, Log Rotation Proof, and AWS Decommission Summary

**Proved 22/80/443-only external reachability via three independent full-range scans, proved Docker's log-rotation mechanism deterministically after discovering the app itself logs almost nothing per request, confirmed AWS-era secrets are already gone, corrected eight files' worth of stale AWS-EC2 deploy-target documentation, and (in a same-day follow-on continuation) fixed and live-verified `cleanup-old-images`' two-bug Docker Hub pruning failure — Phase 5 is now fully complete.**

## Performance

- **Duration:** ~90 min (continuation session; Task 1's context was read by a prior agent that returned a checkpoint) + a later same-day continuation closing Task 3 Part C
- **Started:** 2026-08-17 (this continuation session)
- **Completed:** 2026-08-17 (all 3 tasks, including Task 3 Part C's two follow-on bug-fix commits)
- **Tasks:** 3 of 3 fully complete
- **Files modified:** 8 (docs, this plan) + 1 (`.github/workflows/deploy.yml`, Task 3 Part C's follow-on fixes)

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
4. **Task 3 Part C: Docker Hub tag-pruning bug fixes** (same-day continuation, after this SUMMARY's first version was committed at `74139a3`) — `8a31d85` (JWT auth-exchange fix) and `faacda4` (pagination fix), both live-verified: `8a31d85`'s own live run (CI run 32016633112) surfaced the pagination gap, `faacda4`'s live run (CI run 32017867204) confirmed the full 32-tag backlog cleared with zero delete failures.

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
- **Task 3 Part C deferred, then closed same-day via CI-only fixes:** no Docker Hub credentials or console access existed in this session, and a known auth bug in `cleanup-old-images`' DELETE calls (filed in `.planning/todos/pending/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md`) complicated it further. Rather than requiring interactive Docker Hub console access, the fix was made entirely through the CI job's own code (the JWT exchange and pagination follow-`next` loop), each verified by reading that job's live run output — no credentials were read or requested by the agent at any point. Not attempted-and-guessed; found, fixed, and confirmed live.

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

None — no external service configuration required. Task 3 Part C ended up needing no Docker Hub console access either; both bugs were fixable and verifiable entirely from the CI job's own code and logs.

## Next Phase Readiness

- **INFRA-05, INFRA-07, and INFRA-08 are all fully proven by measurement** — no further work needed on network reachability, log-rotation bounds, or credential/tag decommissioning.
- **Phase 5 (Infra Migration) is now fully complete** — all 6 plans done, all 8 phase requirements (INFRA-01 through INFRA-08) satisfied. This was also the last phase of milestone v1.2 (Infra Migration & Schema Registry) per `.planning/ROADMAP.md`.
- Pending todo `.planning/todos/pending/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md` should be closed with this resolution.

---
*Phase: 05-infra-migration*
*Completed: 2026-08-17*

## Self-Check: PASSED

All 8 modified files confirmed present on disk (`docs/INFRA_RUNBOOK.md`, `.claude/CLAUDE.md`,
`.planning/codebase/STACK.md`, `README.md`, `docs/LOCAL_DEV.md`,
`docs/plans/backend-modernization/02-optimistic-locking-ddl.sql`,
`docs/plans/backend-modernization/README.md`, `docs/plans/backend-modernization/STATUS.md`).
All 3 task commits (`a067b57`, `1a0e676`, `f1e6d62`) confirmed present in `git log --oneline --all`.
