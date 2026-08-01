# Phase 3: Activity Log Consumer, Reliability & Read API - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-02
**Phase:** 3-Activity Log Consumer, Reliability & Read API
**Areas discussed:** Activity log detail format, Consumer retry/backoff before DLT, Topic creation strategy, Response shape for the read endpoint

---

## Activity log detail format

| Option | Description | Selected |
|--------|-------------|----------|
| Structured identifiers only | Raw ids only, human-readable rendering deferred to frontend | ✓ |
| Pre-rendered human string | Consumer resolves names via direct repository lookups | |

**User's choice:** Structured identifiers only.
**Notes:** User asked a clarifying question first — whether the Spring Boot app hosts Kafka itself. Answered: no, Kafka runs as a separate docker-compose service; the app is a client (producer + consumer). User then asked why events are persisted at all / what the listener accomplishes — answered with the full pipeline explanation (mutation → async publish → Kafka topic → consumer persists → Postgres → read endpoint queries Postgres, never Kafka directly). After that context, user confirmed the recommended option.

| Option | Description | Selected |
|--------|-------------|----------|
| Fixed string enum per event type | One of 5 values, mapped 1:1 from event class | ✓ |
| Something else | | |

**User's choice:** Fixed string enum per event type.

| Option | Description | Selected |
|--------|-------------|----------|
| JSON string column | Event-specific ids as a compact JSON string in one column | ✓ |
| Flat nullable columns | Separate nullable columns per possible id | |

**User's choice:** JSON string column.

---

## Consumer retry/backoff before DLT

**Detour:** User asked whether having producer + consumer in the same service is an anti-pattern, and about the real performance cost of a listener thread. Answered: not an anti-pattern at this scale (already an explicit, discussed decision in PROJECT.md/REQUIREMENTS.md to defer a separate microservice); listener runs on its own background thread via blocking `poll()`, not a busy-loop; the one real shared resource is the JVM's DB connection pool under heavy concurrent load, a non-issue at this project's scale.

| Option | Description | Selected |
|--------|-------------|----------|
| 3 retries, short fixed backoff | DefaultErrorHandler + FixedBackOff (~1s) | ✓ |
| 0 retries — dead-letter immediately | | |
| More retries with exponential backoff | | |

**User's choice:** 3 retries, short fixed backoff (after a clarifying detour on what "retrying" actually means here — see below).

| Option | Description | Selected |
|--------|-------------|----------|
| Malformed/unparseable JSON payload | Genuine non-retryable deserialization failure | ✓ |
| A valid event that always throws in the consumer | Requires test-only failure injection hook | |

**User's choice:** Malformed/unparseable JSON payload.

**Notes:** User asked what "retrying" means — can consumption fail, or DB writes? Answered: both are real failure points (deserialization before the listener runs, vs. DB write failure inside it), both get the same uniform retry-then-DLT treatment by default; flagged that the expected duplicate-eventId case must be caught as a silent no-op, never allowed to throw into this path. User then asked how likely deserialization failures actually are in this system — answered: low in normal operation (same codebase serializes/deserializes), most plausible real trigger is rolling-deploy schema evolution. User asked to capture that as a reminder (done — pending todo) and asked whether Kafka has any built-in schema/encapsulation logic (answered: no, vanilla Kafka is schema-less; all safety comes from shared codebase convention). User then asked to plant a seed for adding a Confluent Schema Registry in a future milestone (done — SEED-001). After these detours, user confirmed both recommended options.

---

## Topic creation strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Explicit NewTopic @Bean | Declared topics, explicit partition/replication | ✓ |
| Broker auto-create | Zero code, relies on auto.create.topics.enable | |

**User's choice:** Explicit NewTopic @Bean.

| Option | Description | Selected |
|--------|-------------|----------|
| 1 partition each | Matches actual single-broker/single-consumer topology | ✓ |
| More than 1 partition | Future-proofs for scaling, no current benefit | |

**User's choice:** 1 partition each.

**Notes:** User asked for an explanation of what KRaft is and why it's used instead of "plain Kafka." Answered: KRaft replaces the historical ZooKeeper dependency with Kafka's own built-in Raft-based metadata management; chosen here per the original epic spec (no separate Zookeeper service) and because Kafka 4.x (the version in use) removed ZooKeeper mode entirely.

---

## Response shape for the read endpoint

| Option | Description | Selected |
|--------|-------------|----------|
| Plain Spring Data Page<T> | Idiomatic, built-in pagination metadata | ✓ |
| Custom slim DTO wrapper | More control, more code to maintain | |

**User's choice:** Plain Spring Data Page<T>.

| Option | Description | Selected |
|--------|-------------|----------|
| action, detail, createdAt, eventId (+ userId) | Mirrors persisted entity, boardId omitted as redundant with URL path | ✓ |
| Something else | | |

**User's choice:** action, detail, createdAt, eventId, userId.

---

## Claude's Discretion

- `ActivityLogEntity` field types (e.g. `detail` column type for JSON string storage)
- Exact retry/backoff API usage (`FixedBackOff(1000L, 3)` or equivalent)
- `activitylog` package internal class structure
- `ActivityLogEntity` id strategy: `BaseEntity`/ULID with a separate unique `eventId` column, vs. `eventId` as the primary key directly — still an open question from Phase 2 milestone research, not resolved during this discussion

## Deferred Ideas

- Confluent Schema Registry (Avro/Protobuf) — planted as SEED-001, future milestone
- Schema evolution risk on ActivityEvent shapes — captured as a pending todo
- Sequence diagram for frontend handoff — reviewed, not folded (trigger condition not yet met)
