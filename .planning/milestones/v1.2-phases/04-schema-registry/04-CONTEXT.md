# Phase 4: Schema Registry - Context

**Gathered:** 2026-08-04
**Status:** Ready for planning

<domain>
## Phase Boundary

Introduce a Kafka Schema Registry (Avro) in front of the existing 5-event-type activity-log pipeline, closing the schema-evolution risk flagged during v1.1 (SEED-001). Covers SCHEMA-01 through SCHEMA-06: one Avro schema per `ActivityEvent` type, a mapping layer between the plain sealed-interface records and Avro-generated `SpecificRecord`s, wiring Confluent's Avro serde into the existing producer/consumer, an explicitly configured compatibility mode, dead-letter-topic re-verification under Avro, and a real-historical-data rehearsal before cutover. Entirely buildable and verifiable against the existing local docker-compose stack — no dependency on the Oracle Cloud VM, Neon, or GitHub Actions changes (that's Phase 5). Does not cover any deploy-target/infra work, and does not cover the pre-merge schema-compatibility CI check or documented compatibility-mode rationale (both explicitly deferred to v2 per REQUIREMENTS.md).

</domain>

<decisions>
## Implementation Decisions

### Schema registry / producer resilience
- **D-01:** A Schema Registry failure (unreachable registry, or a schema rejected by the compatibility check) is treated exactly like a Kafka-broker-down failure already is under v1.1's D-01/D-02: the HTTP mutation always succeeds regardless of what fails inside the async, post-commit publish path — logged via the existing `whenComplete` error callback in `KafkaEventPublisher`, never swallowed, never blocks the caller. One resilience policy for the whole publish path (broker down, registry down, or schema rejected), not a special case carved out for registry-specific failures. — **Reversibility:** costly — narrowing this later to fail loudly on schema rejection specifically would mean threading a new failure classification through the same `whenComplete` callback and deciding what "loud" means (metric? alert? still just a log line at higher severity?), without an existing precedent to extend.

### Compatibility mode
- **D-02:** The activity-log topic's schema subject(s) use **BACKWARD** compatibility, explicitly configured (not left at the registry's out-of-the-box default). Matches this project's actual deployment topology — producer (`KafkaEventPublisher`) and consumer (`ActivityLogConsumer`/`KafkaConsumerConfig`) live in the same deployable and always ship together in one merge, so there is no independent-upgrade-order scenario FULL's extra strictness would protect against. Also required if the append-only activity feed is ever replayed from the beginning: old messages must stay readable by newer schemas. — **Reversibility:** costly — tightening to FULL later would require auditing every schema change made under BACKWARD for forward-compatibility, and could retroactively reject changes already made.

### Schema granularity
- **D-03:** One Avro schema per event type — 5 separate `.avsc` files, one per `TaskCreatedEvent`/`TaskMovedEvent`/`TaskDeletedEvent`/`BoardCreatedEvent`/`ColumnCreatedEvent` — not a single union schema covering all 5. Mirrors the existing Java structure exactly (5 sealed-interface records → 5 schemas, 1:1); adding a 6th event type later means adding one new schema file, the same shape of change as adding a 6th record today. Each subject evolves independently under D-02's BACKWARD compatibility. — **Reversibility:** costly — collapsing to a union schema later means consolidating 5 registered subjects into 1, which is itself a compatibility-sensitive schema change on an already-live topic.

### Claude's Discretion
- Exact Avro schema field types/logical types for each event's fields (e.g. whether `timestamp` maps to Avro's `timestamp-millis` logical type vs a plain `long`) — planner/researcher's call, informed by `ActivityEvent`'s existing `Instant`-typed field.
- Per-field required-vs-optional-with-default classification for each of the 5 event types (SCHEMA-06's rehearsal-before-cutover work) — this is the bulk of the actual schema-authoring effort; requires reading each record's fields against real historical event shapes, not a decision to make in the abstract here.
- Mapping-layer implementation shape: hand-authored mapper classes (MapStruct-style, matching this codebase's existing Entity↔DTO convention) vs. Avro's reflection-based `@Union` serialization — research flagged this as a genuinely open design question with no existing pattern to copy; planner/researcher decides during phase planning.
- Exact subject-naming-strategy configuration (e.g. `TopicNameStrategy` vs `RecordNameStrategy`) given all 5 schemas currently publish to the same `kanban.activity` topic under D-03's one-schema-per-type split — planner/researcher's call, informed by which strategy actually supports 5 independently-evolving subjects on one topic.
- Whether to stand up a standalone local Schema Registry container for Phase 4's local verification, or temporarily point at a local single-node Redpanda instance (whose built-in registry is what Phase 5 will use in production anyway) — both work for local-only verification; planner's call.

### Folded Todos
- **"Account for schema evolution risk when changing ActivityEvent shapes"** (`.planning/todos/pending/2026-08-01-account-for-schema-evolution-risk-when-changing-activityeven.md`, already tagged `resolves_phase: 4`) — this phase's entire purpose (explicit versioned schemas + enforced compatibility mode) is the structural fix for the risk this todo describes: a rolling deploy changing an `ActivityEvent` field's shape while old-shape messages are still unconsumed could previously dead-letter valid messages, since Kafka itself enforces no schema. D-02's BACKWARD compatibility mode is the direct answer.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project-level requirements
- `.planning/PROJECT.md` — Core Value, Current Milestone (v1.2), Validated requirements from v1.0/v1.1
- `.planning/REQUIREMENTS.md` — SCHEMA-01 through SCHEMA-06 (this phase's requirements); INFRA-01..08 (Phase 5); v2-deferred SCHEMA-V2-01/02 (pre-merge compat CI check, documented rationale — explicitly out of scope here)
- `.planning/ROADMAP.md` — Phase 4 goal, success criteria, dependency relationship with Phase 5

### Milestone-level research (grounded findings)
- `.planning/research/SUMMARY.md` — executive summary, phase-ordering rationale (Schema Registry first), the two flagged risk clusters
- `.planning/research/STACK.md` — Redpanda v26.2.x built-in registry, Confluent `kafka-avro-serializer`/Apache Avro/`gradle-avro-plugin` versions and Maven repo requirement (`https://packages.confluent.io/maven/`, not on Maven Central)
- `.planning/research/FEATURES.md` — table stakes vs differentiators vs anti-features for the schema-registry work specifically; the explicit rejection of a generic/pluggable envelope format and of FULL-mode-as-default
- `.planning/research/ARCHITECTURE.md` — the concrete integration points (`KafkaEventPublisher`'s `ProducerFactory`, `KafkaConsumerConfig`'s deserializer chain) and the confirmed finding that no tooling maps a Java sealed interface to Avro automatically
- `.planning/research/PITFALLS.md` — Pitfalls 9-12 (compatibility-mode misconfiguration, Avro strict-field-default breakage, DLT byte-fidelity regression under Avro, historical-data rehearsal) — all four map directly onto this phase's success criteria

### Prior phase context (established conventions this phase must not break)
- `.planning/milestones/v1.1-phases/02-kafka-foundation-domain-events-move-endpoint/02-CONTEXT.md` — D-01/D-02 Kafka-down resilience precedent this phase's D-01 extends to schema-registry failures
- `.planning/milestones/v1.1-phases/03-activity-log-consumer-reliability-read-api/03-CONTEXT.md` — D-01/D-04/D-05 consumer decisions (raw structured `detail` field, retry/backoff before DLT, idempotent-duplicate-as-silent-no-op) that Phase 4's Avro cutover must not regress
- `.planning/milestones/v1.1-phases/03-activity-log-consumer-reliability-read-api/03-VERIFICATION.md` — the 14 previously-verified observable truths for the existing JSON pipeline, useful as a re-verification checklist under Avro

### Deferred/related items (do not re-litigate, but be aware of)
- `.planning/seeds/SEED-001-add-a-confluent-schema-registry-avro-protobuf-in-front-of-th.md` — the original seed this phase promotes to real scope
- `.planning/todos/pending/2026-08-01-account-for-schema-evolution-risk-when-changing-activityeven.md` — folded into this phase's decisions above

### Codebase maps
- `.planning/codebase/TESTING.md` — test conventions (AAA, `@Nested`, `Assertions.catchException`, no mocks, real Testcontainers Kafka) this phase's new Avro-poison-message test and historical-data rehearsal must follow

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ActivityEvent` sealed interface (`event/ActivityEvent.java`) + its 5 implementing records — plain, dependency-free, no Lombok/JPA/Spring annotations by design (shared producer/consumer contract). The mapping layer (Claude's Discretion above) translates between these and Avro `SpecificRecord`s without touching this interface's shape.
- `KafkaEventPublisher` (`config/KafkaEventPublisher.java`) — the sole `org.springframework.kafka` touchpoint in `src/main`; D-01's resilience extension applies to this class's existing `whenComplete` error-logging callback, not a new mechanism.
- `ActivityLogConsumer.deriveActionAndDetailIds` (`activitylog/ActivityLogConsumer.java`) — the exhaustive, no-default-arm `switch` over `ActivityEvent` that must keep working unchanged once events arrive as Avro-deserialized objects via the new mapping layer.
- `application.properties` lines 49-79 — current `JsonSerializer`/`JsonDeserializer` + `ErrorHandlingDeserializer` + `spring.json.trusted.packages`/`use.type.headers` config that gets replaced with Avro serde config.

### Established Patterns
- Async, post-commit-only Kafka publish via `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async("kafkaPublishExecutor")` — the mechanism D-01's resilience extension rides on top of; no new dispatch mechanism needed.
- Exhaustive sealed-interface `switch` with no `default` arm, both in the consumer's `deriveActionAndDetailIds` and in `ActivityEvent`'s own `permits` clause — the compile-time-safety property this phase must preserve through the Avro mapping layer, not weaken.
- Dead-letter topic uses a byte-preserving custom `DelegatingByTypeSerializer` (`KafkaConsumerConfig`) so poison messages keep their original bytes — SCHEMA-05 requires this guarantee be re-verified (not assumed to carry over) once the main path serializes as Avro instead of JSON.

### Integration Points
- New Avro schema files (`.avsc`, one per event type per D-03) + Gradle Avro plugin codegen output — new, analogous to how `build/generated` already holds MapStruct-generated mapper implementations.
- New mapping-layer classes (exact shape per Claude's Discretion) at the publish boundary (inside or alongside `KafkaEventPublisher`) and consume boundary (inside or alongside `ActivityLogConsumer`).
- `application.properties`' Kafka producer/consumer serializer/deserializer properties — modified to Confluent's `KafkaAvroSerializer`/`KafkaAvroDeserializer` plus `schema.registry.url` pointing at whatever local registry Phase 4 verifies against (see Claude's Discretion).

</code_context>

<specifics>
## Specific Ideas

No specific UI/behavioral references from this discussion — the three areas discussed (registry-down resilience, compatibility mode, schema granularity) were architectural/policy decisions, captured directly above.

</specifics>

<deferred>
## Deferred Ideas

- **Pre-merge schema-compatibility CI check** (registry `/compatibility` API call before merge) — explicitly deferred to v2 (SCHEMA-V2-01) during requirements definition; mirrors the already-planned Phase 5 pre-merge DDL check pattern but not required for the pipeline to function.
- **Documented compatibility-mode rationale** (Javadoc-style paragraph on the BACKWARD choice, matching `KafkaEventPublisher`'s D-01/D-02 convention) — explicitly deferred to v2 (SCHEMA-V2-02) during requirements definition; this CONTEXT.md's D-02 already captures the rationale for downstream agents, so the deferred item is specifically the in-code Javadoc polish, not the decision itself.

### Reviewed Todos (not folded)
- "Use Snowflake ID generator for activity log events" — matched Phase 4 with score 0.6 (keyword overlap on activity/log/events) but is an ID-generation strategy choice unrelated to schema registry/Avro work. Left deferred, unrelated to this phase's scope.
- "Bump Java version from 21 to 25", "Create sequence diagram for frontend handoff", "Add dependency vulnerability scan", "Enable virtual threads", "Evaluate PMD/Checkstyle/SpotBugs" — all matched Phase 4 with low scores (0.4-0.6) on generic keyword overlap (build/deploy/main/stack), none topically related to schema registry work. Left deferred, no action taken.

</deferred>

---

*Phase: 4-Schema Registry*
*Context gathered: 2026-08-04*
</content>
