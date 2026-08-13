---
phase: quick-260813-jrt
plan: 01
subsystem: docs
tags: [readme, architecture-docs, api-documentation, gsd-assembly]

# Dependency graph
requires: []
provides:
  - "README.md's ## API table documents all 16 route groups (24 method-level @*Mapping annotations plus filter-based /logout), including the 6 routes shipped since Phase 6 that were previously undocumented"
  - ".planning/codebase/ARCHITECTURE.md and .claude/CLAUDE.md both name and route-enumerate UserController, ActivityController and TaskMoveController"
  - "docs/ARCHITECTURE.md's optimistic-locking DTO list includes ReorderColumnRequestDTO"
affects: [docs, api-documentation, gsd-docs-update]

# Actuals (#2632)
actuals:
  tokens: 5138
  tasks: 2
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Additive table patch over full regeneration: only rows an exhaustive grep-derived enumeration proved stale are touched, preserving hand-written editorial Notes on the rest"
    - "Source-before-mirror edit order for GSD-assembled doc blocks: .planning/codebase/ARCHITECTURE.md edited first, .claude/CLAUDE.md's <!-- GSD:architecture-start/end --> mirror edited second, so a re-assembly is a no-op instead of a silent revert"

key-files:
  created: []
  modified:
    - README.md
    - .planning/codebase/ARCHITECTURE.md
    - .claude/CLAUDE.md
    - docs/ARCHITECTURE.md

key-decisions:
  - "Additive patch (not full table regeneration, not OpenAPI-derived generation) — the 24-annotation enumeration already proved the 11 untouched rows accurate, so regenerating would have destroyed hand-written editorial Notes for zero information gain"
  - "Theme row explicitly states identity comes from the session and shows no user-id path segment, so it cannot be misread as an id-bearing /users/{userId}/theme route (UserController's actual IDOR mitigation is structural, not documented as one)"
  - "Corrected the source todo's own imprecision rather than propagating it: there is exactly one reorder route (column reorder), not a plural task/column reorder pair — task repositioning already has its own PATCH /tasks/{taskId}/move route, already correctly documented pre-fix"

patterns-established:
  - "GSD-assembled doc edit order: when a file sits inside a <!-- GSD:*-start/end --> block generated from another source file, edit the source first and gate verification on both files agreeing, or the mirror edit is a no-op with a delay-fused revert at next assembly"

requirements-completed: [QUICK-260813-jrt]

coverage:
  - id: D1
    description: "README.md's API table documents all six routes shipped since Phase 6 (POST /boards, GET /boards/{boardId}/full, DELETE .../columns/{columnId}, PATCH .../reorder, GET/PUT /users/me/theme) and the false 'two gaps' paragraph is deleted"
    requirement: "QUICK-260813-jrt"
    verification:
      - kind: other
        ref: "grep-based parity gate: awk-counted API table rows == 18 (16 data + header + separator); grep -c 'addBoardByUserId' README.md == 0; presence checks for all 6 previously-missing routes; git diff --name-only -- src/ empty"
        status: pass
    human_judgment: false
  - id: D2
    description: ".planning/codebase/ARCHITECTURE.md (source) and .claude/CLAUDE.md (assembled mirror) both name UserController, ActivityController, TaskMoveController and enumerate every route in lockstep; docs/ARCHITECTURE.md's version-DTO list gains ReorderColumnRequestDTO"
    requirement: "QUICK-260813-jrt"
    verification:
      - kind: other
        ref: "grep-based parity gate: controller-name and route-substring presence checked in both files; GSD:architecture-start/end marker count == 2 (assembly boundary undisturbed); ReorderColumnRequestDTO presence in docs/ARCHITECTURE.md; git diff --name-only -- src/ empty"
        status: pass
    human_judgment: false

duration: 6min
completed: 2026-08-13
status: complete
---

# Quick Task 260813-jrt: Rebuild README.md's stale API table Summary

**Rebuilt README.md's API table from 13 to 16 rows covering all 24 controller `@*Mapping` annotations, deleted the paragraph falsely claiming board creation and column deletion have no HTTP route, and brought `.planning/codebase/ARCHITECTURE.md`, `.claude/CLAUDE.md`, and `docs/ARCHITECTURE.md` into route/controller parity.**

## Performance

- **Duration:** ~6 min
- **Completed:** 2026-08-13T12:25:55Z
- **Tasks:** 2
- **Files modified:** 4 (README.md, .planning/codebase/ARCHITECTURE.md, .claude/CLAUDE.md, docs/ARCHITECTURE.md) plus 1 todo moved

## Accomplishments
- README.md's `## API` table now has 16 data rows documenting every one of the 24 method-level `@*Mapping` annotations across the 7 controllers plus `AuthenticationController`, plus the filter-based `/logout` route — verified by a grep-based parity gate (`24-annotation → 16-row` re-derivation, not a trusted static count)
- Deleted the stale "two gaps worth naming" paragraph that falsely claimed board creation and column deletion had no HTTP route (both were closed by Phase 6, months before this fix)
- Fixed the source todo's own imprecision during planning rather than propagating it: confirmed exactly one reorder route (column reorder), not a plural task/column pair — no invented task-reorder row was added
- `.planning/codebase/ARCHITECTURE.md` and `.claude/CLAUDE.md` now both name `UserController`, `ActivityController`, `TaskMoveController` in their Component Responsibilities tables and enumerate every route in their Entry Points sections, edited source-before-mirror so the GSD assembly block cannot silently revert the fix at next re-assembly
- `docs/ARCHITECTURE.md` audited end-to-end and found accurate apart from one narrow gap: `ReorderColumnRequestDTO` (which carries `@NotNull Long version`) was missing from the optimistic-locking version-echo DTO list — added; nothing else in that file changed
- Closed the source todo (`.planning/todos/pending/2026-08-10-readme-api-table-missing-routes-shipped-since-phase-6.md`), moved to `completed/` with a Resolution section

## Task Commits

Each task was committed atomically:

1. **Task 1: Rebuild README.md's API table and drop the false gap paragraph** - `8cd6879` (docs)
2. **Task 2: Bring the cross-check targets to the same parity, source before mirror** - `373b7a5` (docs)
3. **Close the source todo (moved to completed/ with Resolution)** - `90cff91` (docs)

_Note: no PLAN.md/SUMMARY.md/STATE.md commit is included here — the orchestrator handles that separately per this quick task's constraints._

## Files Created/Modified
- `README.md` - `## API` table rebuilt to 16 rows (3 merged/extended, 3 new, 10 byte-identical); stale "two gaps" paragraph deleted
- `.planning/codebase/ARCHITECTURE.md` - Added `UserController`, `TaskMoveController`, `ActivityController` to Component Responsibilities; rewrote HTTP Controller Entry Points to cover every route
- `.claude/CLAUDE.md` - Mirrored both edits into the `<!-- GSD:architecture-start/end -->` assembled block, matching its flat-bullet, no-sub-heading rendering convention
- `docs/ARCHITECTURE.md` - Added `ReorderColumnRequestDTO` to the optimistic-locking version-echo DTO list; rest of file left unchanged (already accurate)
- `.planning/todos/completed/2026-08-10-readme-api-table-missing-routes-shipped-since-phase-6.md` - Moved from `pending/`, Resolution section added

## Decisions Made
- **Additive patch over full table regeneration** — the exhaustive 24-annotation enumeration done during planning already proved all 11 untouched rows accurate, so a full rewrite would have destroyed hand-written editorial Notes (concurrent-session limits, cascade behavior, pagination defaults, the `…` path-elision convention) for zero information gain. Rejected an OpenAPI-derived alternative too: `openapi.json` is deleted in the working tree and regenerating it means booting Postgres/Testcontainers for a docs-only change, with output that still needs the same hand-editing for editorial Notes.
- **Theme row wording is IDOR-conscious, not just accurate** — states the caller's identity comes from the session and shows no user-id path segment, so it can't be misread as `/users/{userId}/theme`. `UserController`'s actual mitigation is structural (no id parameter exists to tamper with); documenting it any other way would invite someone to "fix" the route to match a wrong doc.
- **Source-before-mirror edit order for the GSD-assembled block** — `.planning/codebase/ARCHITECTURE.md` edited first, `.claude/CLAUDE.md`'s mirror second, with a marker-count gate (`GSD:architecture-start`/`-end` == 2) confirming the assembly boundary itself wasn't disturbed. An edit to the mirror alone would look correct today and silently revert at the next `/gsd-docs-update`.

## Deviations from Plan

None - plan executed exactly as written. The plan's own `<planning_findings>` had already corrected the source todo's imprecision (singular vs. plural reorder routes) during the planning phase, so no in-flight correction was needed during execution.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Documentation is now self-consistent across `README.md`, `.planning/codebase/ARCHITECTURE.md`, `.claude/CLAUDE.md`, and `docs/ARCHITECTURE.md` for every route the application serves as of this quick task's execution. No blockers for subsequent work. One related but out-of-scope todo remains open and unaffected by this fix: `2026-08-11-add-openapi-breaking-change-detection-to-ci.md` (no CI guard exists today against a REST shape changing silently, unlike the Kafka pipeline's Schema Registry compatibility mode) — a different concern (schema-diff automation vs. this task's static prose accuracy) and not folded in here.

---
*Phase: quick-260813-jrt*
*Completed: 2026-08-13*

## Self-Check: PASSED

All 6 claimed files found on disk (README.md, .planning/codebase/ARCHITECTURE.md, .claude/CLAUDE.md, docs/ARCHITECTURE.md, the completed todo, this SUMMARY); pending todo copy confirmed removed; all 3 claimed commit hashes (8cd6879, 373b7a5, 90cff91) found in `git log --oneline --all`.
