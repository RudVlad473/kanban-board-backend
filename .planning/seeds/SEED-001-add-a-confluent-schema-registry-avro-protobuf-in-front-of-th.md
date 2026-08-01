---
id: SEED-001
status: dormant
planted: 2026-08-01
planted_during: v1.1 Kafka Activity Feed, Phase 3 (Activity Log Consumer, Reliability & Read API)
trigger_when: next Kafka-related milestone after v1.1 (v1.2/v2.0), or the one after that — whichever milestone actually revisits the Kafka event pipeline next, depending on what gets cut from the current backend modernization plan
scope: medium
---

# SEED-001: Add a Confluent Schema Registry (Avro/Protobuf) in front of the kanban.activity Kafka topic

## Why This Matters

Discussed during Phase 3 discuss-phase: vanilla Apache Kafka enforces no schema at all — the broker
treats every message as opaque bytes. The only reason producer and consumer currently agree on
shape is that they're the same codebase using the same `JsonSerializer`/`JsonDeserializer`
convention. That's fine today, but it has two real weaknesses:

1. **Rolling-deploy schema evolution risk** — a field rename/retype on any `ActivityEvent` record,
   deployed while old-shape messages are still unconsumed, can dead-letter valid (non-poison)
   messages. (See the related todo:
   `.planning/todos/pending/2026-08-01-account-for-schema-evolution-risk-when-changing-activityeven.md`.)
2. **No compatibility enforcement if a second consumer ever appears** — PROJECT.md already notes a
   separate deployable microservice for the activity-log consumer is a "possible later epic." The
   moment there's more than one independently-deployed consumer of `kanban.activity` (or a second
   producer), convention-based JSON agreement stops being sufficient — a schema registry
   (Avro/Protobuf with Confluent's registry) gives real, enforced compatibility checking at
   publish/consume time instead of hoping nobody drifts.

## When to Surface

**Trigger:** next Kafka-related milestone after v1.1, or the one after that — whichever milestone
actually revisits the Kafka event pipeline next, depending on what gets cut from the current
backend modernization plan's remaining epics.

This seed will surface during `/gsd-new-milestone` when the milestone scope matches (Kafka,
schema, event contracts, or a second Kafka consumer/producer).

## Scope Estimate

**Medium** — new infrastructure component (Confluent Schema Registry as a docker-compose service),
`build.gradle`/Kafka producer-consumer config changes to switch from `JsonSerializer`/
`JsonDeserializer` to Avro or Protobuf serializers, defining/migrating the 5 existing
`ActivityEvent` record shapes into schema definitions, and re-verifying the full producer→consumer
pipeline (including the Testcontainers-based end-to-end test from this milestone).

## Breadcrumbs

- `src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java` and its 5 implementing records
  — the schemas that would need to be formalized
- `src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java` — current `JsonSerializer`-based producer
- `docker-compose.yml` — would gain a `schema-registry` service
- `.planning/todos/pending/2026-08-01-account-for-schema-evolution-risk-when-changing-activityeven.md` — the related, more immediate reminder this seed would eventually supersede
- `.planning/PROJECT.md` — "Out of Scope" already notes the separate-microservice-consumer idea this seed's second motivation ties into

## Notes

Raised by the user directly during Phase 3 discuss-phase, in response to a clarifying question
about whether Kafka has any built-in encapsulation/schema-validation logic (it doesn't).
