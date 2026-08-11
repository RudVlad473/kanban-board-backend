---
created: 2026-08-11T21:00:00.000Z
title: TaskService.deleteAllByColumnId is dead code — zero production callers
area: backend
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
  - src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java
---

## Problem

Found while auditing the mutating surface for quick task 260811-s5e (`260811-s5e-FINDINGS.md`
Section 5, item 4). `TaskService.deleteAllByColumnId(String userId, String columnId)`
(`TaskService.java`) is a public method with zero production callers — every real cascade-delete
path goes through `ColumnService.deleteAllByBoardId`'s package-private
`deleteAllByColumn(ColumnEntity)` overload directly, which skips the redundant ownership
re-verification `deleteAllByColumnId` performs on top of it.

The only reference to `deleteAllByColumnId` anywhere in the codebase is `TaskServiceTest`, whose
own comment (`TaskServiceTest.java:31`) states it "guards against the N+1 previously in
`deleteAllByColumnId`: it used to re-verify ownership" — i.e. the test exists to document a
performance property of a method nothing in production actually calls.

Not fixed as part of 260811-s5e itself: this is a pre-existing discrepancy discovered during that
quick task's audit, not one of its files or its mutating-surface scope (`deleteAllByColumnId`
publishes no `ActivityEvent`, so it was never a GAP-CLOSE-HERE row).

## Solution

Not yet decided. Candidates for whoever picks this up:

1. **Delete it** — remove `TaskService.deleteAllByColumnId` and update/remove the
   `TaskServiceTest` cases exercising it, following the precedent of quick task 260802-q6n (which
   removed a similarly dead, unverified `SubtaskService.findById(String)` overload rather than
   exempting it).
2. **Keep it, document why** — if there's a reason to preserve a public, ownership-verifying
   variant for future callers (e.g. a future API surface that deletes all tasks in a column
   without already holding a verified `ColumnEntity`), add a Javadoc note explaining that intent
   rather than leaving it silently unused.

Low priority — genuinely dead code with a passing test, not a live defect.
