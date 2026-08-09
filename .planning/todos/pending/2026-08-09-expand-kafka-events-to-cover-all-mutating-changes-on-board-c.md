---
created: 2026-08-09T21:14:57.244Z
title: Expand Kafka events to cover all mutating changes on board/column/task/subtask
area: backend
severity: major
files:
  - src/main/java/com/vrudenko/kanban_board/event/BoardCreatedEvent.java
  - src/main/java/com/vrudenko/kanban_board/event/ColumnCreatedEvent.java
  - src/main/java/com/vrudenko/kanban_board/event/ColumnDeletedEvent.java
  - src/main/java/com/vrudenko/kanban_board/event/TaskCreatedEvent.java
  - src/main/java/com/vrudenko/kanban_board/event/TaskDeletedEvent.java
  - src/main/java/com/vrudenko/kanban_board/event/TaskMovedEvent.java
  - src/main/java/com/vrudenko/kanban_board/service/BoardService.java
  - src/main/java/com/vrudenko/kanban_board/service/ColumnService.java
  - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
  - src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java
---

## Problem

The `kanban.activity` Kafka pipeline (Avro events → Schema Registry → activity-log consumer,
built in the Phase 4 Schema Registry work) only emits events for a subset of the mutations a
user can actually make. As of this todo, the only event classes that exist are:
`BoardCreatedEvent`, `ColumnCreatedEvent`, `ColumnDeletedEvent`, `TaskCreatedEvent`,
`TaskDeletedEvent`, `TaskMovedEvent` — all published from `BoardService`, `ColumnService`, and
`TaskService`.

That leaves real, user-facing mutations with no activity trail at all:

- **Board**: update (rename, description change) and delete — no `BoardUpdatedEvent` /
  `BoardDeletedEvent`.
- **Column**: rename/reorder beyond creation and deletion — no `ColumnUpdatedEvent` (column
  reordering may already exist as a distinct operation per `ColumnOrderingE2ETest`; confirm
  whether it needs its own event or folds into an update event).
- **Task**: update (title/description/status changes) — no `TaskUpdatedEvent` (only
  create/delete/move currently emit).
- **Subtask**: `SubtaskService` publishes no events whatsoever — create, update, delete, and
  completion-toggle on subtasks are entirely invisible to the activity log.

The 07.1 phase currently in progress adds optimistic locking to Board (`@Version` + V7
migration) and closes several other Board/Task/Auth inconsistencies flagged from the frontend
integration — this gap was noticed adjacent to that work but is out of scope for it.

## Solution

TBD. Likely shape:
1. Add the missing event classes (`BoardUpdatedEvent`, `BoardDeletedEvent`, `ColumnUpdatedEvent`,
   `TaskUpdatedEvent`, `SubtaskCreatedEvent`, `SubtaskUpdatedEvent`, `SubtaskDeletedEvent`, and any
   completion-toggle event) following the existing `ActivityEvent` pattern.
2. Wire `ApplicationEventPublisher.publishEvent(...)` calls into the corresponding service methods
   (`BoardService`, `ColumnService`, `TaskService`, `SubtaskService`) at the same point existing
   create/delete events are published — likely after the transactional write succeeds.
3. Extend `ActivityEventAvroMapper` (and the underlying Avro schema, subject to the project's
   BACKWARD compatibility mode — see the related `d-02-backward-non-transitive-vs-replay-from-zero`
   todo) to serialize the new event types.
4. Extend `ActivityEventPublicationTest` and the activity-log E2E coverage
   (`e2e/activity/ActivityReadE2ETest`) to assert the new event types are captured end-to-end.
