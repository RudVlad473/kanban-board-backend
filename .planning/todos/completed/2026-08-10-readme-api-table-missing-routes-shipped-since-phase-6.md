---
created: 2026-08-10T20:00:00.000Z
resolved: 2026-08-13
resolves_phase: quick-260813-jrt
title: README.md's API table is missing routes shipped since Phase 6 (board creation, column delete, reorder, theme)
area: docs
severity: minor
files:
  - README.md
---

## Problem

Found while reconciling documentation at the end of phase 07.1 (Task 4 of `07.1-09-PLAN.md`), but
**not caused by phase 07.1** — this staleness predates it, dating back to Phase 6 (Mock-up Feature
Gap Closure, GAP-01..07). Deliberately not fixed as part of 07.1's own doc-reconciliation task,
since that task's scope is claims *this phase* invalidated, and re-verifying the full route table
against source is a bigger job than a drive-by fix.

`README.md`'s `## API` table and the paragraph immediately below it are stale:

- The paragraph reads: *"Two gaps worth naming rather than hiding: board creation exists as
  `UserService.addBoardByUserId` but is not exposed over HTTP yet (only tests reach it), and
  columns have no delete route — the cascade is only reachable by deleting the board."* Both gaps
  were closed by Phase 6: `POST /boards` (GAP-01, `06-02-PLAN.md`) and
  `DELETE /boards/{boardId}/columns/{columnId}` (GAP-02, `06-03-PLAN.md`) both exist and are
  routed today.
- The API table itself has no row for `POST /boards`, no row for
  `DELETE /boards/{boardId}/columns/{columnId}`, no row for the task/column reorder endpoints
  (GAP-03, `06-04-PLAN.md`), and no row for the per-user theme endpoint(s) on `UserController`
  (GAP-05, `06-06-PLAN.md`). `GET /boards/{boardId}/full` (GAP-04, `06-05-PLAN.md`) is also absent.

## Solution

Re-derive the full, current route list directly from `ApiPaths.java` plus every `@*Mapping`
annotation in `src/main/java/com/vrudenko/kanban_board/controller/` (do not trust this todo's list
as exhaustive — it names what was noticed in passing, not a systematic audit) and rebuild the API
table and its surrounding prose to match. Delete the now-false "two gaps" paragraph entirely once
the table itself lists both routes. Cross-check `docs/ARCHITECTURE.md` and `.claude/CLAUDE.md` for
the same gap while in there, since both documents also predate Phase 6 in places.

## Resolution

Closed by quick task **260813-jrt**. A systematic enumeration (not a trust of this todo's own list,
per its own caveat) found **24 method-level `@*Mapping` annotations across 8 classes** — one more
class than the todo's four named controllers implied, since `TaskMoveController` and
`ActivityController` also exist and were previously undocumented anywhere.

`README.md`'s `## API` table rebuilt from 13 to 16 data rows via an additive patch (not a full
regeneration — the 11 rows the enumeration proved accurate kept their hand-written Notes text
byte-identical): `POST /boards` merged onto the existing `/boards` row, `DELETE .../columnId`
merged onto the existing column `PUT` row, and three new rows added for
`GET /boards/{boardId}/full`, `PATCH .../columns/{columnId}/reorder`, and
`GET`/`PUT /users/me/theme`. The stale "two gaps" paragraph was deleted in full, with nothing
written in its place — the table is now the only statement. The theme row was deliberately worded
to state the caller's identity comes from the session, so it cannot be misread as an id-bearing
`/users/{userId}/theme` route (`UserController`'s actual, structural IDOR mitigation).

One correction to the todo's own framing, caught during planning: it speculates the table "may
also be missing task/column reorder endpoints" (plural) — there is exactly **one** reorder route,
and it reorders a column (`PATCH /boards/{boardId}/columns/{columnId}/reorder`); task repositioning
already has its own route (`PATCH /tasks/{taskId}/move`, added post-Phase-6 and already correctly
documented pre-fix). No task-reorder row was invented.

Both cross-check targets the todo named were addressed:

- `docs/ARCHITECTURE.md` — audited and found accurate; it has no route table and no stale gap
  prose, and every route named in its sequence diagrams was already correct. One narrow, genuine
  gap found beyond the todo's own scope: its optimistic-locking DTO list (the DTOs requiring the
  client to echo `version`) named five DTOs but omitted `ReorderColumnRequestDTO`, which also
  carries `@NotNull Long version` — the identical failure mode this todo is about, one entry
  smaller. Added.
- `.claude/CLAUDE.md` — found stale (`Component Responsibilities` and `HTTP Controller Entry
  Points` both predated Phase 6, omitting `UserController`, `ActivityController` and
  `TaskMoveController` entirely) and carrying an assembly trap: that section is generated from
  `.planning/codebase/ARCHITECTURE.md` inside a `<!-- GSD:architecture-start/end -->` block, so an
  edit to the mirror alone would have been silently reverted at the next re-assembly. Fixed in
  lockstep, source (`.planning/codebase/ARCHITECTURE.md`) first: both files' Component
  Responsibilities table gained the three missing controllers, and both files' Entry Points section
  now enumerates every route (board GET/POST/PUT/DELETE/full, column GET/POST/PUT/DELETE/reorder,
  task GET/PUT/DELETE/subtask-create, subtask GET/PUT/DELETE, the flat task-move route, the board
  activity feed, the two theme routes, and the three auth routes). Marker count re-verified at 2
  after the edit, so the assembly boundary itself was not disturbed.

Verification was grep-based, not build-based: `./gradlew spotlessCheck`/`test` target
`src/**/*.java` and would pass identically on a wrong table, so the real gates were a controller
re-derivation (24 mappings, matching the enumeration exactly), a stale-string check
(`addBoardByUserId` now absent from `README.md`), presence checks for all six previously-missing
routes across all three touched docs, and a `git diff --name-only -- src/` emptiness check
(confirmed empty — this was a docs-only change, two commits, four files: `README.md`,
`.planning/codebase/ARCHITECTURE.md`, `.claude/CLAUDE.md`, `docs/ARCHITECTURE.md`).
