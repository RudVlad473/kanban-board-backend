---
created: 2026-08-02T13:30:00.000Z
resolved: 2026-08-09
resolves_phase: 6
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

## Resolution

Closed by **Phase 6 Plan 07 (GAP-07, completed 2026-08-09)**, folded into the phase at the user's
explicit request as a topically-independent seventh deliverable riding alongside the six mock-up
gap-doc items.

The todo's own premise turned out to be inexact, and the finding is worth preserving: the todo
speculated this "should probably follow whatever the broader decision on project-wide ID generation
strategy lands on" — but that broader decision had already been made, just narrowly scoped.
`config/RandFlakeGenerator.java` already implements exactly what this todo asks for (a 41-bit
timestamp above 23 random bits, Base36-encoded, custom epoch), and was already the sole ID-generation
algorithm behind every entity primary key in the project via `@RandFlakeId`. This work did not adopt
a new scheme — it extended the one the project already had, through a new `config/EventIdGenerator`
component wrapping `RandFlakeGenerator.generateRandflake()` for the one non-entity-id caller that
needed it.

Delivered: `ActivityLogEntity.eventId` (and the whole event package's `ActivityEvent` sealed
interface, all six event records, all six Avro schemas, `ActivityLogRepository`,
`ActivityLogResponseDTO`, and all six event-publish call sites in `BoardService`/`ColumnService`/
`TaskService`) moved from `UUID` to `String` as one atomic compile unit. `V6__change_activity_log_event_id_to_varchar.sql`
retypes the column and rebuilds `uk_activity_log_event_id` around it. Proven, not assumed:
`EventIdGeneratorTest` proves distinctness (1000 rapid calls) and generation-order sorting;
`FlywaySchemaProvenanceTest` proves six migrations, the surviving unique constraint, and the new
character-varying column type, all read from the live catalog; and — the one claim this plan
explicitly refused to take on faith — a local registry was seeded with all six subjects' pre-change
(`uuid`-logicalType) schemas, then `./gradlew registerSchemas` was run with the post-change (plain
string) generated schemas, confirming the registry accepted all six as a genuine new version under
real BACKWARD compatibility (not merely an assumption that dropping the logical type would be
harmless). The full suite (278 tests) is green, including the pre-existing republish-dedupe E2E case
in `ActivityLogIdempotencyE2ETest`, which now exercises the new String key type unchanged.

No second time-ordered ID implementation exists anywhere in `src/main` — verified by
`grep -rn "ThreadLocalRandom" src/main/java` returning exactly `RandFlakeGenerator.java`.
