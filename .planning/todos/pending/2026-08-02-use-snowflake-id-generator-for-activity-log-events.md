---
created: 2026-08-02T13:30:00.000Z
title: Use Snowflake ID generator for activity log events
area: backend
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/event/
  - src/main/java/com/vrudenko/kanban_board/entity/ActivityLogEntity.java
---

## Problem

`eventId` (the business dedupe key on `ActivityEvent`/`ActivityLogEntity`, distinct from the row's own `BaseEntity` ULID primary key) is currently a `java.util.UUID`. UUIDs are random and carry no time-ordering, which costs index locality on the `eventId` unique constraint and gives no free ordering signal for debugging/tracing event sequences.

## Solution

Switch `eventId` generation to a Snowflake-style ID (time-ordered, roughly-sortable, still effectively-unique across producers) instead of `UUID.randomUUID()`. See the related general note about adopting this as the project's default ID-generation strategy — this todo should probably follow whatever that broader decision lands on, not be done in isolation with a one-off scheme.
