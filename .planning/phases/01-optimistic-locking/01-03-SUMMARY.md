---
phase: 01-optimistic-locking
plan: 03
subsystem: database
tags: [postgres, ddl, optimistic-locking, migration-bridge]

# Dependency graph
requires:
  - phase: 01-optimistic-locking (plan 01)
    provides: "@Version fields on TaskEntity and ColumnEntity that need a real backing column in Postgres"
provides:
  - "Ready-to-run manual DDL script (docs/plans/backend-modernization/02-optimistic-locking-ddl.sql) adding the version column to the real tasks/columns tables"
  - "Dated STATUS.md decision-log entry recording the one-way manual-run obligation and the defer-to-Epic-3-rejected rationale"
affects: []

# Actuals (#2632)
actuals:
  tokens: 650
  tasks: 1
  commits: 1

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Manual bridge DDL delivered as a standalone runnable .sql file (not prose), living beside the epic docs it supports, for a schema change ddl-auto won't apply automatically"

key-files:
  created:
    - docs/plans/backend-modernization/02-optimistic-locking-ddl.sql
  modified:
    - docs/plans/backend-modernization/STATUS.md

key-decisions:
  - "Checkpoint resolved by user: deliver as a standalone .sql file (deliver-sql-file option, recommended per D-07) rather than a documented command inside STATUS.md only"
  - "User explicitly accepted the one-way obligation to run the script manually via psql against the real Postgres database, right before merging/deploying this phase's PR"
  - "Script uses ADD COLUMN IF NOT EXISTS so an accidental second run is a no-op, addressing the LOCK-01 idempotency backstop (T-01-10)"

patterns-established:
  - "One-off manual bridge DDL for a schema gap not covered by ddl-auto: deliver as a runnable file with a header comment explaining scope, timing, and non-replacement of the future migration-tool epic"

requirements-completed: [LOCK-01]

coverage:
  - id: D1
    description: "Ready-to-run SQL script adding version bigint NOT NULL DEFAULT 0 to both tasks and columns tables in the real Postgres schema, with IF NOT EXISTS idempotency guard"
    requirement: "LOCK-01"
    verification:
      - kind: other
        ref: "grep assertion (plan's <verify>): both ALTER TABLE statements present with correct column/type/constraint shape in 02-optimistic-locking-ddl.sql"
        status: pass
    human_judgment: false
  - id: D2
    description: "STATUS.md decision-log entry recording the manual DDL deliverable, its one-way before-merge obligation (D-06), and the defer-to-Epic-3-rejected rationale (D-07)"
    requirement: "LOCK-01"
    verification:
      - kind: other
        ref: "grep assertion (plan's <verify>): STATUS.md contains flyway/epic 3/before merge/one-off language"
        status: pass
    human_judgment: false
  - id: D3
    description: "The operator (user) manually runs the delivered script against the real Postgres database before merge/deploy"
    human_judgment: true
    rationale: "This is an operational action outside the agent's control by explicit user instruction — the agent must not attempt DB connections or credential discovery. Only the user can confirm the script was actually run against the live database."

duration: 15min
completed: 2026-08-01
status: complete
---

# Phase 1 Plan 3: Manual Optimistic-Locking DDL Bridge Summary

**Delivered a standalone, idempotent `ALTER TABLE ... ADD COLUMN version bigint NOT NULL DEFAULT 0` SQL script for the real Postgres `tasks`/`columns` tables, plus a dated STATUS.md decision-log entry recording the one-way manual-run obligation before merge/deploy.**

## Performance

- **Duration:** ~15 min
- **Tasks:** 1
- **Files modified:** 2 (1 created, 1 modified)

## Accomplishments
- Created `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql` with both required `ALTER TABLE ... ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0` statements (tasks and columns), plus a header comment explaining scope (one-off Epic 2 bridge), timing (run manually via psql before merge/deploy), and non-replacement of Epic 3's Flyway work
- Appended a dated decision-log entry to `docs/plans/backend-modernization/STATUS.md` recording the deliverable, the one-way merge-time obligation (D-06 — master auto-deploys to EC2 on push, so a missed manual run breaks production on any Task/Column request), and the flag-raised-and-resolved rationale (D-07 — deferring to Epic 3 was considered and explicitly rejected because it would leave production broken between merge and Epic 3 landing)
- No SQL was executed by the agent — this plan is a deliverable-and-documentation-only scope, as instructed

## Task Commits

Each task was committed atomically:

1. **Task 1: Create the ready-to-run ALTER TABLE DDL script and record the one-off-bridge decision in STATUS.md** - `a7a9132` (docs)

## Files Created/Modified
- `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql` - New standalone runnable DDL script with both idempotent `ALTER TABLE` statements and an explanatory header comment
- `docs/plans/backend-modernization/STATUS.md` - Appended dated decision-log entry documenting the manual DDL bridge, its one-way obligation, and the defer-to-Epic-3-rejected rationale

## Decisions Made
- **Checkpoint resolution (pre-execution):** The blocking decision checkpoint at the start of this plan was resolved by the user before this agent was spawned: option `deliver-sql-file` (the recommended choice per D-07) was selected over `documented-command`, and the user explicitly acknowledged and accepted the one-way obligation to manually run the script via psql against the real Postgres database, immediately before merging/deploying this phase's PR. The user explicitly declined to have the agent discover DB credentials or execute the DDL itself.
- Used `ADD COLUMN IF NOT EXISTS` on both statements so an accidental second run of the script is a no-op, matching the threat register's idempotency mitigation (T-01-10) called out in the plan.

## Deviations from Plan

None - plan executed exactly as written (Task 1 only; the plan's blocking checkpoint had already been resolved prior to this agent's spawn, per the continuation instructions).

## Issues Encountered
None.

## User Setup Required

**External database change requires manual execution by the user.** This phase intentionally does not (and per explicit user instruction, must not) execute any SQL or connect to any database. Before merging/deploying this phase's PR:

1. Run `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql` manually via `psql` (or an equivalent client) against the real Postgres database.
2. Verify both `tasks.version` and `columns.version` columns exist afterward (e.g. `\d tasks` / `\d columns` in psql).
3. Only then merge/deploy — master auto-deploys to EC2 on every push, so skipping this step breaks any Task/Column request in production with a missing-column SQL error.

This is a one-way step (D-06) and was the subject of the blocking checkpoint already resolved by the user before this plan's Task 1 began.

## Next Phase Readiness

- Epic 2's optimistic-locking work (Plans 01–03) is now fully delivered: `@Version` on `TaskEntity`/`ColumnEntity`, the Column update endpoint, the 423->409 fix, E2E proof, and this manual DDL bridge deliverable.
- The one remaining action before this phase's PR can safely merge/deploy is entirely outside this agent's scope: the user running `02-optimistic-locking-ddl.sql` against the real Postgres database.
- Epic 3 (Flyway migrations) should treat this manual DDL as already-applied history, not something to silently re-run — flagged again in STATUS.md so it isn't lost.

---
*Phase: 01-optimistic-locking*
*Completed: 2026-08-01*

## Self-Check: PASSED

- FOUND: docs/plans/backend-modernization/02-optimistic-locking-ddl.sql
- FOUND: .planning/phases/01-optimistic-locking/01-03-SUMMARY.md
- FOUND: a7a9132 (Task 1 commit)
