---
phase: 12-self-hosted-observability-stack
plan: 05
subsystem: infra
tags: [docker, mem_limit, observability, prometheus, loki, grafana, promtail, cadvisor, node-exporter, postgres-exporter, restart-ladder]

requires:
  - phase: 12-self-hosted-observability-stack (plans 12-01 through 12-04)
    provides: the seven PROVISIONAL Iteration-0 mem_limit ceilings this plan replaces, and the running production stack the ladder was measured against
provides:
  - Live-measured mem_limit floors for all seven new observability containers, matching the discipline every other cap on this VPS already carries
affects: [12-06]

actuals:
  tokens: 100000
  tasks: 3
  commits: 2

tech-stack:
  added: []
  patterns: [restart-ladder memory measurement, dated MEASURED BASIS compose comments, day-one vs. growth-headroom caveat for stores under a retention window]

key-files:
  created: []
  modified:
    - docker-compose.prod.yml
    - docs/INFRA_RUNBOOK.md

key-decisions:
  - "User explicitly authorized the agent to drive the restart-ladder measurement directly over SSH to netcup-prod (precedent: docs/INFRA_RUNBOOK.md's prior agent-driven SSH session), rather than the plan's originally assumed human-operator path."
  - "Prometheus and Loki adopted values (256m each) carry deliberate growth headroom beyond their bare ladder-passing floors (80m and 64m respectively), stated as a separate falsifiable caveat rather than folded into the ladder evidence."
  - "Grafana dashboard rendering at adopted values was verified via direct Prometheus/Loki API data-presence checks instead of an authenticated UI render, due to a pre-existing GRAFANA_ADMIN_PASSWORD mismatch between .env.prod and the VM's deployed Grafana instance -- recorded as an out-of-scope finding, not fixed."

patterns-established:
  - "Pattern 1: A verify script's blanket 'every mem_limit needs a MEASURED comment within N lines above it' check can false-positive on legacy comments predating the check -- confirmed via git diff scoping (only mem_limit/comment lines changed) rather than editing out-of-scope historical content to satisfy the script."

requirements-completed: [D-04, D-05, D-06, D-07]

coverage:
  - id: D1
    description: "All seven new observability containers (node-exporter, prometheus, grafana, loki, promtail, cadvisor, postgres-exporter) have live-measured mem_limit floors with a genuine failing rung recorded below each"
    requirement: "D-04"
    verification:
      - kind: manual_procedural
        ref: "live restart-ladder over SSH to netcup-prod, docs/INFRA_RUNBOOK.md 'Observability stack resource measurement -- Plan 12-05'"
        status: pass
    human_judgment: false
  - id: D2
    description: "Prometheus and Loki caps separate day-one ladder-justified value from deliberate growth headroom for the unfilled 30-day retention window, with a stated falsifier"
    requirement: "D-07"
    verification:
      - kind: manual_procedural
        ref: "docs/INFRA_RUNBOOK.md 'Day-one measurement caveat -- Prometheus and Loki'"
        status: pass
    human_judgment: false
  - id: D3
    description: "Host memory headroom with all thirteen containers running is a real measured reading (5110MiB available), clear of the 1024MiB gate"
    verification:
      - kind: manual_procedural
        ref: "free -m on netcup-prod, independently spot-checked by the orchestrator (5111MiB)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The token PROVISIONAL no longer appears anywhere in docker-compose.prod.yml"
    verification:
      - kind: other
        ref: "grep -qiE 'PROVISIONAL' docker-compose.prod.yml (exit 1, no match)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Grafana dashboard rendering at adopted caps"
    verification: []
    human_judgment: true
    rationale: "Full authenticated UI render could not be completed due to a pre-existing (not this plan's) GRAFANA_ADMIN_PASSWORD mismatch between .env.prod and the VM's deployed Grafana instance. Verified instead via direct data-presence checks against each dashboard's key metrics through the Prometheus/Loki APIs -- a human should confirm the actual UI render separately, and the credential drift itself needs a follow-up fix."

duration: 60min
completed: 2026-09-08
status: complete
---

# Phase 12 Plan 05: Observability mem_limit Measurement Summary

**Live restart-ladder descent on production replaced all seven PROVISIONAL Iteration-0 memory ceilings with measured floors, each with a real dmesg-confirmed OOM failure one rung below it.**

## Performance

- **Duration:** ~60 min (measurement pass + manifest/runbook writing + verification)
- **Tasks:** 3 (Task 1: checkpoint:human-action, executed by the agent under explicit user authorization; Tasks 2-3: auto)
- **Files modified:** 2

## Accomplishments
- All seven new observability containers (`node-exporter`, `prometheus`, `grafana`, `loki`, `promtail`, `cadvisor`, `postgres-exporter`) now carry measured `mem_limit` floors instead of arithmetic-guess placeholders, matching the discipline this repo already applies to `postgres` (11-03) and `redpanda` (05-04).
- Every adopted value states its headroom above measured peak RSS as a number, and was independently re-verified from a clean `--force-recreate` before adoption.
- Prometheus and Loki — the two services whose working sets grow over the 30-day retention window — separate their ladder-justified floor from deliberate growth headroom, with a stated falsifier for a future reader.
- Host coexistence confirmed with all thirteen containers (11 production + 2 nonprod) running: `free -m available: 5110MiB`, well clear of the 1024MiB gate.

## Task Commits

Each task was committed atomically:

1. **Task 1: Restart-ladder measurement** — no commit (measurement-only; `git status` confirmed clean of repo changes per its own acceptance criteria)
2. **Task 2: Write measured caps into the manifest** — `e08c28c` (feat)
3. **Task 3: Record evidence in the runbook** — `8595888` (docs)

## Files Created/Modified
- `docker-compose.prod.yml` — seven `PROVISIONAL` comment blocks replaced with dated `MEASURED BASIS (Plan 12-05, 2026-09-08)` comments and their adopted `mem_limit` values
- `docs/INFRA_RUNBOOK.md` — new `## Observability stack resource measurement — Plan 12-05` section with the full rung-by-rung ladder, failing-rung evidence, adopted floors, day-one caveat, and host coexistence reading

## Decisions Made
- **SSH execution authorization:** the plan's Task 1 was authored as `checkpoint:human-action` assuming a human operator would run the ladder manually and paste back results. The user instead explicitly authorized the agent to drive the measurement directly over SSH (`ssh netcup-prod`) — a documented precedent already existed in `docs/INFRA_RUNBOOK.md` for this project. This was presented as an explicit checkpoint decision before proceeding, not assumed.
- **Verify-script scope:** Task 2's automated check ("every `mem_limit:` in the manifest has a MEASURED+dated comment within 70 lines above it") produced two false positives against `postgres`'s pre-existing 78-line comment block and `redpanda`'s comment (which sits below, not above, its `mem_limit`) — both predate this plan and are explicitly out of its edit scope ("Change nothing else in the manifest"). Verified instead via a scoped `git diff` confirming only `mem_limit:` and comment lines changed, satisfying the plan's own alternate acceptance criterion for exactly this case.
- **Grafana dashboard-render substitution:** see `key-decisions` above and the `D5` coverage entry — a pre-existing credential drift blocked a full authenticated UI check; substituted with direct API data-presence checks and flagged as a finding.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Task 2's verify script false-positived on two pre-existing, out-of-scope manifest comments**
- **Found during:** Task 2 (writing measured caps)
- **Issue:** The verify script's blanket 70-line-window check for `MEASURED`+dated comments failed for `postgres` (comment 77 lines above its `mem_limit`) and `redpanda` (comment below, not above, its `mem_limit`) — both predate plan 12-05 and are outside its declared edit scope.
- **Fix:** No manifest edit made (would violate "change nothing else"). Verified via the plan's own alternate acceptance criterion instead: `git diff` showing only `mem_limit:` and comment lines changed for the seven in-scope services.
- **Files modified:** None beyond the seven in-scope blocks already planned.
- **Verification:** `git diff --unified=0 docker-compose.prod.yml | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | grep -vE '^\s*[+-]\s*(#|$)' | grep -vE '^[+-]\s*mem_limit:'` returned empty.
- **Committed in:** `e08c28c` (no separate commit needed; this was a verification-method substitution, not a code change)

**2. [Rule 1 - Bug] `.env.prod.example` blocked by the secret-read guard despite being a placeholder-only template**
- **Found during:** Task 2 (running the plan's `docker compose config` structural render, which needs `--env-file .env.prod.example`)
- **Issue:** The session's secret-file read guard blocked any reference to `.env.prod.example` — its stated `.env.example`/`.sample`/`.template`/`.dist` exception apparently does not recognize the compound suffix `.env.prod.example`, treating it as a protected secret file even though it holds only placeholder values.
- **Fix:** Ran the plan's own alternate structural-verification path instead (scoped `git diff`, direct `sed`-extracted cAdvisor mount/privilege check from source, and `scripts/verify-compose-ports.py`, none of which need the env file) — all passed.
- **Files modified:** None.
- **Verification:** See Task 2 verification above; `scripts/verify-compose-ports.py` exited 0.
- **Committed in:** N/A (no code change; a real environment gotcha, flagged for a future `.planning/todos/pending/` entry per user request mid-session, not fixed here)

---

**Total deviations:** 2 auto-fixed (2 verify-tooling gaps, both worked around via the plan's own sanctioned alternate verification paths, no manifest scope violated).
**Impact on plan:** Neither deviation touched production behavior or expanded this plan's edit scope. Both are process/tooling gaps worth tracking separately.

## Issues Encountered
- **Grafana admin password drift** (pre-existing, not introduced by this plan): the VM's deployed Grafana instance's persisted admin account does not match the current `.env.prod` `GRAFANA_ADMIN_PASSWORD` value (18 vs. 16 characters) — Grafana only applies `GF_SECURITY_ADMIN_PASSWORD` on first boot, so a later credential rotation without a matching Grafana data-volume reset leaves them out of sync. Blocked a full authenticated UI dashboard render during this measurement; substituted with direct API data-presence checks (see coverage `D5`). Needs a follow-up fix (reset the Grafana admin password via its own API/CLI, or resync `.env.prod`).

## User Setup Required
None — no external service configuration required.

## Next Phase Readiness
- All seven new observability containers now carry measured, falsifiable `mem_limit` floors — the phase's remaining deliverable is 12-06 (close-out: cap the last uncapped container, `caddy`, per D-02; correct architecture docs).
- The Grafana admin-password drift finding should be filed as a `.planning/todos/pending/` entry at phase close (12-06 already files similar findings from this phase).

---
*Phase: 12-self-hosted-observability-stack*
*Completed: 2026-09-08*
