---
created: 2026-08-01T21:57:47.852Z
resolved: 2026-08-06
resolves_phase: 4
title: Account for schema evolution risk when changing ActivityEvent shapes
area: backend
severity: minor
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

## Resolution

Closed by **Phase 4 (Schema Registry, completed 2026-08-04)**, which anticipated this todo by name
— see `.planning/phases/04-schema-registry/04-CONTEXT.md:33`, which cites this file's path and
calls D-02's BACKWARD compatibility "the direct answer" to the risk described above. Verified
against the shipped code on 2026-08-06 rather than taken on the CONTEXT.md claim alone.

All three Solution bullets are now subsumed by tooling:

1. **"Prefer additive, backward-compatible changes over renames/retypes"** — no longer advice, it
   is enforced. `AvroSchemaRegistrar` sets BACKWARD explicitly on each of the 5 subjects *before*
   that subject's first registration (`AvroSchemaRegistrar.java:81-85`), so no subject is ever
   ungoverned. `spring.kafka.producer.properties.auto.register.schemas=false`
   (`application.properties:74`) means the producer can only look schemas up, never register — so
   a drifted producer schema fails loudly instead of silently minting a new version. The
   `registerSchemas` Gradle task (`build.gradle:196`) is the only writer. A rename/retype that
   breaks backward compatibility now fails at the build/CI registration step, not in production.
   `SchemaCompatibilityE2ETest.EnforcementTest` proves both halves: a new *required* field is
   rejected with the registry's conflict status, and — as the control that keeps that rejection
   meaningful rather than tautological — a new *defaulted* field is accepted against the same
   subject.

2. **"Drain the topic or accept a DLT wave on a breaking change"** — the specific trigger this
   describes is closed. A new consumer reading old-shape in-flight messages is precisely what
   BACKWARD guarantees, and SCHEMA-06 rehearsed the 5 new schemas against real historical
   `activity_log` rows (not fixtures derived from the same record definitions), so the guarantee
   was exercised against data nobody designed for the new schemas.

3. **"No schema registry is in place"** — factually false as of Phase 4.

### One residual finding, filed separately

D-02's rationale offers a second justification for BACKWARD: *"required if the append-only activity
feed is ever replayed from the beginning."* Non-transitive BACKWARD does not deliver that property
— it checks version N against N−1 only. This does **not** reopen the risk this todo describes
(which is about the immediately-previous shape, exactly what BACKWARD covers), and the real risk is
low because Phase 4's own `04-04-PLAN.md` already treats topic replay-from-zero as unavailable. It
is a rationale inconsistency rather than a defect, so it is tracked on its own rather than holding
this todo open: see
`.planning/todos/pending/2026-08-06-d-02-backward-non-transitive-vs-replay-from-zero.md`.
