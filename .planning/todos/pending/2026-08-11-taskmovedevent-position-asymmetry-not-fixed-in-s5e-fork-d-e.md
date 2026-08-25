---
created: 2026-08-11T21:00:00.000Z
title: TaskMovedEvent carries no position, unlike the new ColumnReorderedEvent (fork D-E, resolved E1)
area: backend
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/event/TaskMovedEvent.java
  - src/main/avro/AvroTaskMovedEvent.avsc
  - src/main/java/com/vrudenko/kanban_board/service/TaskService.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

`TaskService.moveToColumn` also serves same-column reordering (there is no separate reorder
endpoint for tasks, unlike columns as of quick task 260811-s5e), but `TaskMovedEvent` carries only
`sourceColumnId`/`targetColumnId` — no position. A task reorder within one column therefore
publishes an event saying it moved from column X to column X, with no position information at all.

This asymmetry became visible specifically because 260811-s5e added `ColumnReorderedEvent`
(fork D-A, resolved A1), which does carry `sourcePosition`/`targetPosition` as native Avro ints.
Columns can now report a reorder's positions; tasks structurally cannot.

## Why it was not fixed in 260811-s5e (fork D-E, resolved E1)

Explicitly evaluated and deliberately deferred at that quick task's Task 2 checkpoint. Two options
were on the table:

- **E1 (chosen)** — leave it, file this todo.
- **E2** — add `sourcePosition`/`targetPosition` to `AvroTaskMovedEvent` now.

E2 is achievable (a defaulted field addition is BACKWARD-compatible, and
`SchemaCompatibilityE2ETest` already proves that control case) but it is the **only** change that
would have touched an existing Avro subject in that quick task — `AvroTaskMovedEvent` would become
version 2, which is the one place BACKWARD compatibility and the
`2026-08-06-d-02-backward-non-transitive-vs-replay-from-zero.md` todo's non-transitivity concern
genuinely apply. Keeping 260811-s5e additive-subjects-only (every new subject at version 1, zero
existing subjects modified) was worth more than closing this cosmetic asymmetry in the same pass —
see that quick task's `260811-s5e-FINDINGS.md` Section 3 for the full reasoning, including a
caveat: the premise that no existing subject had ever been modified turned out to already be false
before that quick task even started (`eventId`'s GAP-07 type change, 2026-08-09) — worth rereading
before treating "this would be the first" as literally true.

## Solution

Not yet decided. When picked up:

1. Add `sourcePosition`/`targetPosition` (or equivalent) fields to `AvroTaskMovedEvent.avsc` with
   Avro defaults, so the change is BACKWARD-compatible under the existing subject.

2. Update `TaskMovedEvent`, `ActivityEventAvroMapper` (both directions), `ActivityLogConsumer`'s
   detail map, and `HistoricalActivityEventReconstructor` together.

3. Confirm `SchemaCompatibilityE2ETest` still passes for the now-versioned `AvroTaskMovedEvent`
   subject (version 2, BACKWARD against version 1).

4. Re-read the d-02 todo's correction note (2026-08-11) before deciding compatibility mode — this
   would be the first genuine multi-version subject in this codebase's history, making d-02's
   BACKWARD-vs-BACKWARD_TRANSITIVE question live for the first time rather than moot.
