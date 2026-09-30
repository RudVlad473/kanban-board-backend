---
phase: 11-migrate-database-from-neon-to-self-hosted-postgres
plan: 08
subsystem: infra
tags: [postgres, docker-compose, cgroup, memory-profile, pgbench, oom]

requires:
  - phase: 11-migrate-database-from-neon-to-self-hosted-postgres
    provides: "the self-hosted postgres:16 service (11-01), its measured but internally
      inconsistent mem_limit/shared_buffers pairing (11-03)"
provides:
  - "An internally consistent postgres mem_limit (256m) / shared_buffers (64MB) pairing,
    mechanically enforced by docker-compose.prod.yml's own verify invariant"
  - "Concurrent-backend (24 total / 21 non-idle) live-VM validation evidence, replacing the
    sequential-burst methodology 11-03 used"
  - "A superseded-in-place 11-03 engine-profile verdict with a forward pointer, so the runbook
    never silently contradicts current state"
affects: [11-migrate-database-from-neon-to-self-hosted-postgres, future-infra-cap-changes]

actuals:
  tokens: 5000
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Mechanically-enforced config invariants: a Python verify script recomputes the
      shared_buffers/mem_limit relationship from the manifest's own values rather than trusting
      an assertion in a comment."
    - "anon+shmem (not raw cgroup memory.peak) as the trusted OOM-risk proxy under a benchmark
      that deliberately drives sustained I/O — raw cgroup totals saturate to ~100% of any cap
      under sustained page-cache pressure regardless of actual OOM risk."

key-files:
  created: []
  modified:
    - docker-compose.prod.yml
    - docs/INFRA_RUNBOOK.md

key-decisions:
  - "Adopted mem_limit: 256m with shared_buffers=64MB (halved from 128MB), leaving work_mem and
    max_connections unchanged — the ceiling had to move because max_connections x work_mem alone
    (100MB) already exceeded the old 64m cap regardless of shared_buffers."
  - "Trusted the cgroup anon+shmem breakdown (~84MB, 32.8% of the adopted cap), not the raw
    memory.peak counter (which saturates to ~100% of any cap under sustained I/O due to
    reclaimable page cache), for the plan's 60%-of-cap acceptance gate."
  - "Superseded the Plan 11-03 'Engine profile after measurement (D-11)' verdict in place with a
    dated forward pointer rather than deleting or rewriting it."

requirements-completed: [D-08, D-11]

coverage:
  - id: D1
    description: "postgres mem_limit and shared_buffers are an internally consistent pair,
      mechanically checked (not merely asserted) against the pre-fix values failing the check"
    requirement: "D-11"
    verification:
      - kind: other
        ref: "Task 2 verify script (python3 invariant recompute) — exit 0, printed 'TASK 2 PASS'"
        status: pass
    human_judgment: false
  - id: D2
    description: "docs/INFRA_RUNBOOK.md records the concurrent-load validation methodology,
      counter-evidence run, configurations tested, adopted profile/invariant, and host
      coexistence, with the Plan 11-03 verdict annotated rather than deleted"
    requirement: "D-08"
    verification:
      - kind: other
        ref: "Task 3 verify script (subsection/value-agreement checks) — exit 0, printed 'TASK 3 PASS'"
        status: pass
    human_judgment: false
  - id: D3
    description: "Task 1's live-VM concurrent-backend validation (24 total / 21 non-idle
      backends, counter-evidence OOM reproduction, adopted-profile survival) was performed
      directly by the orchestrator over SSH against the production Netcup VM"
    verification: []
    human_judgment: true
    rationale: "This is a live production maintenance-window action (repeated database
      recreation, a deliberate OOM-reproduction attempt) that this worktree executor did not and
      could not perform — it was carried out by the orchestrator directly over SSH, outside this
      worktree's scope. Recorded here for traceability; no automated verification artifact exists
      inside this repository for the live-VM action itself, only its downstream documentation
      (D2) and manifest correction (D1)."

duration: ~20min
completed: 2026-08-26
status: complete
---

# Phase 11 Plan 08: Postgres memory profile correction Summary

**Fixed an internally-inconsistent postgres cgroup cap (`mem_limit: 64m` vs.
`shared_buffers=128MB`) by adopting `mem_limit: 256m` / `shared_buffers=64MB`, mechanically
enforced by a recomputed invariant, backed by a live concurrent-backend validation that reproduced
the original defect as a real OOM kill.**

## Performance

- **Duration:** ~20 min (Tasks 2 and 3 only — Task 1 was a separate live-VM session run directly
  by the orchestrator)
- **Completed:** 2026-08-26
- **Tasks:** 2 of 3 (Tasks 2 and 3; Task 1 performed by the orchestrator, not this executor — see
  below)
- **Files modified:** 2

## Important: Task 1 was performed by the orchestrator, not this executor

This plan's Task 1 (`checkpoint:human-action`, gate `blocking`) is the live maintenance-window
validation against the production Netcup VPS (`netcup-prod`, root@159.195.114.230). Per this
plan's dispatch instructions, **the orchestrator ran Task 1 directly over SSH against the live VM
before this worktree agent was spawned** — this executor did not SSH anywhere, did not touch the
production database, and did not re-run any live-VM validation. This executor's scope was Tasks 2
and 3 only: applying the already-validated corrected values to the repository's
`docker-compose.prod.yml`, and documenting the already-gathered evidence in
`docs/INFRA_RUNBOOK.md`.

The evidence package used for Tasks 2 and 3 (provided verbatim by the orchestrator, reproduced in
full in `docs/INFRA_RUNBOOK.md`'s new "Postgres memory profile correction — Plan 11-08" section)
covers: a pre-window `pg_dump` safety net of both databases; an unplanned live reproduction of the
CR-02 defect as a real kernel OOM-kill against the unfixed `64m`/`shared_buffers=128MB` pairing;
three concurrent-load sub-runs at `256m`, `384m`, and the final adopted `256m` configuration; the
methodological finding that raw cgroup `memory.peak` saturates to ~100% of any cap under sustained
I/O and that `anon+shmem` (~84MB, 32.8% of the adopted 256MB cap) is the correct reading for the
plan's 60%-of-cap gate; and post-window health/cleanup confirmation (both public endpoints 200,
both databases 8/8 Flyway rows, scratch database dropped, VM manifest restored to match the
repository pending this commit).

## Accomplishments

- Corrected `docker-compose.prod.yml`'s `postgres` service: `mem_limit: 64m` → `256m`,
  `shared_buffers=128MB` → `64MB` (halved, not raised); `work_mem` and `max_connections` left
  exactly as they were.
- Added a dated `CORRECTION (Plan 11-08, 2026-08-26)` comment block directly beneath the existing
  `MEASURED BASIS (Plan 11-03, 2026-08-26)` block — superseding it in place rather than deleting
  it — citing CR-02/gap 1, stating the invariant with the adopted numbers, and summarizing the
  concurrent re-validation and counter-evidence outcome.
- Updated the D-11 comment above the `command:` list to describe the profile as it now is.
- Recomputed the plan's own invariant check (`shared_buffers <= mem_limit/4` and
  `shared_buffers + max_connections*work_mem <= 0.85*mem_limit`) directly against the manifest —
  passes for the adopted pair (`64+100=164MB`, 64.1% of `256MB`), and fails against the pre-fix
  pair, confirmed by hand before committing.
- Appended a new `## Postgres memory profile correction — Plan 11-08 (2026-08-26)` section to
  `docs/INFRA_RUNBOOK.md` with all ten required subsections: why the 11-03 floor was re-opened,
  why lowering `shared_buffers` alone could not close it, the concurrent workload used to
  re-validate, the counter-evidence run, the configurations-tested table, the adopted profile and
  its invariant (including the anon+shmem correction), the rung below the adopted value, host
  coexistence, and the measurement date.
- Annotated the Plan 11-03 section's `### Engine profile after measurement (D-11)` subsection in
  place with a dated forward pointer to this correction, rather than rewriting or deleting the
  original "Unchanged" verdict.
- Updated the `## Database — self-hosted PostgreSQL` current-state table's `Engine settings` and
  `mem_limit` rows to the adopted values, pointing at the new section.

## Task Commits

Each task was committed atomically:

1. **Task 2: Apply the consistent profile to the manifest and enforce the invariant
   mechanically** - `dd053df` (fix)
2. **Task 3: Record the correction in the runbook and supersede the prior measurement verdict in
   place** - `8bf4f40` (docs)

Task 1 (the live-VM checkpoint) was performed by the orchestrator directly over SSH before this
worktree agent was dispatched; it is not represented by a commit in this worktree.

## Files Created/Modified

- `docker-compose.prod.yml` - `postgres` service's `mem_limit`/`shared_buffers` corrected to an
  internally consistent pair; dated `CORRECTION` comment added; D-11 comment above `command:`
  updated.
- `docs/INFRA_RUNBOOK.md` - new "Postgres memory profile correction — Plan 11-08" section; Plan
  11-03 engine-profile verdict annotated with a forward pointer; current-state table updated.

## Decisions Made

- **Adopted `mem_limit: 256m` / `shared_buffers=64MB`** over the alternatives considered in the
  plan (keeping `64m` and lowering `shared_buffers` further; removing `mem_limit` entirely) —
  it is the only pairing where the arithmetic closes given `max_connections=25` and
  `work_mem=4MB` already demand 100MB on their own.
- **Trusted the `anon+shmem` cgroup breakdown, not the raw `memory.peak` counter**, for the plan's
  60%-of-cap gate — the raw counter saturated to ~100% of every tested cap (`256m` and `384m`
  alike) purely from reclaimable page cache under sustained I/O, which the evidence package's
  `memory.stat` breakdown showed was not actually at risk of forcing an OOM-kill (`anon+shmem`
  held ~84MB constant across every configuration and dataset size tested).
- **Superseded, not deleted**, the Plan 11-03 "Engine profile after measurement (D-11)" verdict —
  matches this repository's established convention (see also the 11-06 Neon decommission record's
  own annotate-in-place pattern) that a reader landing on old text should be pointed forward, not
  left with silently stale information.

## Deviations from Plan

None — plan executed exactly as written for Tasks 2 and 3. Task 1 was executed by the orchestrator
directly over SSH (as instructed for this dispatch) rather than by this worktree executor; this is
not a deviation from the plan's own text, but a deviation from this executor's normal task-loop
scope, called out explicitly above and in this SUMMARY's frontmatter (`coverage` entry D3).

## Issues Encountered

The Task 2 verify script's own `awk`-based comment-extraction check (`grep -qF 'CR-02'` against a
window bounded by the first line containing `mem_limit:`) initially failed because the new
correction comment's prose referenced "a `mem_limit` of 64m" using the literal string
`mem_limit:` before the real `mem_limit: 256m` line, truncating the extracted window early and
cutting off the `CR-02` citation that came after it. Fixed by rewording that one sentence to avoid
the literal `mem_limit:` token in prose (`"a 64m cap paired with shared_buffers=128MB"` instead).
No functional change to the correction's content — purely a wording adjustment to satisfy the
verify script's text-extraction boundary.

## User Setup Required

None — no external service configuration required. The corrected values take effect on the VM
only after the next real deploy (Task 1's evidence confirms the VM's own manifest copy was
restored to match the pre-window repository state before the maintenance window closed).

## Next Phase Readiness

`11-VERIFICATION.md` truth #4 ("Postgres runs an internally consistent, deliberately conservative
memory/connection profile...") can now be re-checked from repository state
(`docker-compose.prod.yml`'s recomputed invariant) plus the runbook's recorded concurrent-load
evidence, and should flip from FAILED to VERIFIED on the next verification pass. No blockers for
subsequent phases; the next production deploy is what makes the corrected values live.

---
*Phase: 11-migrate-database-from-neon-to-self-hosted-postgres*
*Completed: 2026-08-26*

## Self-Check: PASSED

- FOUND: `docker-compose.prod.yml`
- FOUND: `docs/INFRA_RUNBOOK.md`
- FOUND: `.planning/phases/11-migrate-database-from-neon-to-self-hosted-postgres/11-08-SUMMARY.md`
- FOUND commit `dd053df` (Task 2)
- FOUND commit `8bf4f40` (Task 3)
- FOUND commit `014cb3e` (SUMMARY, prior revision)
