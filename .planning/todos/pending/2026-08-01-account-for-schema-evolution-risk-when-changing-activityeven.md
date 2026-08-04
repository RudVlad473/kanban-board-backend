---
created: 2026-08-01T21:57:47.852Z
title: Account for schema evolution risk when changing ActivityEvent shapes
area: backend
severity: minor
resolves_phase: 4
files:
  - src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java
  - src/main/java/com/vrudenko/kanban_board/event/TaskCreatedEvent.java
  - src/main/java/com/vrudenko/kanban_board/event/TaskMovedEvent.java
  - src/main/java/com/vrudenko/kanban_board/event/TaskDeletedEvent.java
  - src/main/java/com/vrudenko/kanban_board/event/BoardCreatedEvent.java
  - src/main/java/com/vrudenko/kanban_board/event/ColumnCreatedEvent.java
---

## Problem

Kafka itself enforces no schema — the broker treats every message as opaque bytes, and all type
safety comes entirely from the producer and consumer being the same codebase using the same
`JsonSerializer`/`JsonDeserializer` conventions. The one realistic scenario where this breaks down:
a rolling deploy that renames/retypes a field on one of the `ActivityEvent` records
(`TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`)
while old-shape messages produced by the previous app version are still unconsumed in the
`kanban.activity` topic — the new consumer code can fail to deserialize them, sending them to the
dead-letter topic (`kanban.activity.dlt`) even though nothing is actually "poison," just
version-skewed.

Discussed during Phase 3 discuss-phase as the most plausible real trigger for the consumer's
dead-letter path (RELY-01/02) — flagged as a reminder, not an immediate action item.

## Solution

When planning any future change to an `ActivityEvent` record's shape or serialization:
- Prefer additive, backward-compatible changes (new optional fields) over renames/retypes where possible.
- If a breaking change is unavoidable, consider draining the topic (or accepting/monitoring a
  wave of DLT entries during the deploy window) rather than assuming zero in-flight messages.
- No schema registry is in place (out of scope for this project) — this is a process discipline to
  apply manually at the time such a change is planned, not something enforced by tooling today.
