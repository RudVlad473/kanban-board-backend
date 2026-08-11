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

## Resolution (quick task 260811-s5e, 2026-08-11)

Closed via a six-task audit → blocking-gate → tracer → horizontal-expand → test-sweep → doc-close
plan (Approach A, chosen over an autonomous single-pass or a design-memo-only split — see
`260811-s5e-PLAN.md`'s tradeoff analysis). Every genuinely open design fork this todo's own
"Solution: TBD" left unresolved was decided by the operator at a blocking checkpoint gate, not
guessed. Full evidence lives in
`.planning/quick/260811-s5e-expand-kafka-events-to-cover-all-mutatin/260811-s5e-FINDINGS.md`.

**Every event type added** (8 new, on top of the 6 that already existed): `SubtaskCreatedEvent`
(the tracer — `SubtaskService`, which published nothing at all, was deliberately chosen as the
proving slice), `BoardUpdatedEvent`, `BoardDeletedEvent`, `ColumnUpdatedEvent`,
`ColumnReorderedEvent`, `TaskUpdatedEvent`, `SubtaskUpdatedEvent`, `SubtaskDeletedEvent`. 14 event
types total, 14 Avro schemas, 14 `ActivityAction` values.

**How each fork was decided, and by whom:** the operator (coordinator), at Task 2's blocking gate,
selected "all recommended" after independently reviewing FINDINGS.md and confirming the reasoning —
explicitly calling out D-D's N+1/queue-overflow math and D-E's additive-only framing as the most
decisive.

- **D-A** (column reorder: dedicated event or folded into an update event?) → **A1**: dedicated
  `ColumnReorderedEvent` carrying `sourcePosition`/`targetPosition` as native Avro ints — the first
  non-opaque-identifier detail values in this codebase.
- **D-B** (subtask completion toggle: own event, a state field, or nothing?) → **B2**:
  `SubtaskUpdatedEvent` carries a server-derived, post-mutation `isCompleted` boolean, never echoed
  from the request DTO.
- **D-C** (what granularity do `*Updated` events carry?) → **C1**: identifiers only, matching every
  existing event exactly — no `changedFields` value.
- **D-D** (cascade deletes: fan out per child, or one event for the requested delete only?) →
  **D1**: matches the precedent Task 1's audit empirically confirmed already existed (`ColumnDeletedEvent`'s
  cascaded children and `TaskDeletedEvent`'s cascaded subtasks were already silent) — every cascade
  path now carries a Javadoc note explaining the silence is deliberate.
- **D-E** (`TaskMovedEvent`'s position asymmetry: fix now, or defer?) → **E1**: deferred, filed as
  `.planning/todos/pending/2026-08-11-taskmovedevent-position-asymmetry-not-fixed-in-s5e-fork-d-e.md`
  — keeps this quick task additive-subjects-only (no existing `.avsc` modified).

**RecordNameStrategy finding, re-verified empirically (not assumed):** confirmed by file:line
(`application.properties:79,107`, `AvroSchemaRegistrar.java:82-83`) that each new event type is a
brand-new Schema Registry subject at version 1, never a new version of an existing subject — so the
`2026-08-06-d-02-backward-non-transitive-vs-replay-from-zero.md` todo's non-transitivity concern is
demonstrably out of scope for every subject this quick task added. One caveat surfaced and handled
at the Task 2 gate rather than silently reasoned past: `git log --diff-filter=M -- src/main/avro/`
does **not** return nothing, contradicting d-02's own "schemas never modified" premise — a
correction note was appended to that todo (not a resolution of its actual question, per the
operator's explicit instruction to keep scope boundaries clean) — see
`260811-s5e-FINDINGS.md` Section 3 for the full writeup.

**Measured suite impact** (`260811-s5e-FINDINGS.md`, Task 5 section): 398 → 417 tests (+19, zero
shrinkage), 368s → 411s (+43s, +11.7%, exceeding the documented ~18s run-to-run variance but fully
explained by legitimately more work — +19 tests, `SubtaskService.save` now publishing on every
fixture-setup subtask creation across ~19 shared-base test classes, 3 new real-broker Kafka E2E
tests). Zero test failures in either measurement; `kafkaPublishExecutor` did not saturate — no
`TaskRejectedException` in either run, so the threat model's named fallback (raise `queueCapacity`,
or make the async dispatch non-fatal) was not needed.

**Other deferred findings, each filed as its own todo rather than fixed here:**
- `.planning/todos/pending/2026-08-11-unbounded-activity-log-growth-rate-materially-accelerated-by-s.md`
  — `activity_log` has no retention policy, and subtask-completion events materially accelerate its
  growth rate.
- `.planning/todos/pending/2026-08-11-taskservice-deleteallbycolumnid-has-zero-production-callers.md`
  — pre-existing dead code found while auditing, out of this quick task's own scope to fix.

`./gradlew spotlessCheck` and the full `./gradlew test` both pass green on the final commit; every
commit passed the pre-commit hook's `fastTest` gate (no red commit reached git).
