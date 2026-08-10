---
created: 2026-08-10T20:00:00.000Z
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
