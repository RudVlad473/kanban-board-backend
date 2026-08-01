# Phase 3: Activity Log Consumer, Reliability & Read API - Context

**Gathered:** 2026-08-02
**Status:** Ready for planning

<domain>
## Phase Boundary

Build the consumer half of the Kafka activity feed: a `@KafkaListener`-based `ActivityLogConsumer` that persists all 5 event types idempotently into a new `ActivityLogEntity`, a dead-letter topic for poison messages with a proving test, and a paginated `GET /boards/{boardId}/activity` read endpoint. Covers ACTLOG-01/02/03, READ-01/02, RELY-01/02, TEST-01/02. Does not cover producer-side changes (Phase 2, done), production Kafka deployment (KAFKA-V2-01, deferred to v2), or cursor/keyset pagination (PAGE-V2-01, deferred to v2 — this phase ships offset `Pageable`).

</domain>

<decisions>
## Implementation Decisions

### Activity log detail format
- **D-01:** The `detail` field stores raw structured identifiers only (e.g. `{"taskId":"...","columnId":"..."}`), never a pre-rendered human-readable string. The consumer runs on a listener thread with no `SecurityContext` and cannot safely re-verify ownership to look up task/column/board names — human-readable rendering ("Jane moved Task X to Done") is deferred to the frontend, which already has the relevant names loaded. — **Reversibility:** costly — switching to pre-rendered strings later means re-deriving names for every already-persisted row (a backfill), or living with a format inconsistency between old and new rows.
- **D-02:** `action` is a fixed string enum, one of exactly `TASK_CREATED`, `TASK_MOVED`, `TASK_DELETED`, `BOARD_CREATED`, `COLUMN_CREATED`, mapped 1:1 from the event's Java class name (`ActivityEvent` implementor).
- **D-03:** `detail`'s event-specific ids are stored as a JSON string in a single column, not as separate nullable columns per possible id — one flexible column instead of a wide, mostly-null table, since each event type populates a different subset of ids.

### Consumer retry/backoff before DLT
- **D-04:** The consumer retries a failing message 3 times with a short fixed backoff (~1s, `DefaultErrorHandler` + `FixedBackOff`) before routing it to the dead-letter topic. Applies uniformly to whatever exception the listener throws — Spring Kafka already special-cases deserialization failures as effectively non-retryable.
- **D-05:** The expected duplicate-`eventId` case (ACTLOG-03's idempotency requirement) MUST be caught inside the consumer as a silent no-op — it is not a failure and must never be allowed to throw into the retry/DLT path. — **Reversibility:** costly — if this isn't handled explicitly, every redelivered duplicate would exhaust 3 retries and land on the DLT, defeating the point of idempotent consumption and polluting the dead-letter topic with non-poison messages.
- **D-06:** RELY-02's poison-message test publishes a malformed/unparseable JSON payload (a genuine, deterministic deserialization failure), not a well-formed event rigged to always throw via a test-only failure-injection hook.

### Topic creation strategy
- **D-07:** Both `kanban.activity` and `kanban.activity.dlt` are created via explicit `NewTopic` `@Bean` definitions, not broker auto-create. A typo'd topic name fails loudly (topic doesn't exist) instead of silently auto-creating a stray topic with default settings; the beans also self-document the topic list.
- **D-08:** Both topics get exactly 1 partition, matching the actual topology (single-broker KRaft, single consumer instance, no parallelism benefit from more partitions).

### Response shape for the read endpoint
- **D-09:** `GET /boards/{boardId}/activity` returns a plain Spring Data `Page<ActivityLogResponseDTO>` — idiomatic, includes `totalElements`/`totalPages`/pageable metadata for free, no custom wrapper DTO to write and maintain. This is the first paginated endpoint in this codebase; no existing convention to match instead.
- **D-10:** Each list item DTO carries `eventId`, `action`, `detail` (raw JSON), `userId`, `createdAt`. `boardId` is deliberately omitted from each item — it's already known from the URL path and would be redundant on every row.

### Claude's Discretion
- Exact `ActivityLogEntity` field names/types beyond what's specified (e.g., whether `detail` is `String` or `@Lob` on the JPA side) — planner/researcher to confirm the right column type for a JSON string in this codebase's existing Postgres/Hibernate setup.
- Exact retry/backoff tuning knobs beyond "3 retries, ~1s fixed" (e.g., whether to use `FixedBackOff(1000L, 3)` literally or something equivalent) — implementation detail.
- `ActivityLogConsumer`'s package location (`activitylog` per the epic spec, already implied by REQUIREMENTS.md ACTLOG-02) and internal class structure (single consumer class vs. split per concern) — planner's call.
- Whether `ActivityLogEntity` extends `BaseEntity` (ULID id) with a separate unique `eventId` column, or uses `eventId` (UUID) directly as the primary key — both viable, research (Phase 2 milestone-level ARCHITECTURE.md) flagged this as an open question, still unresolved — planner/researcher to decide during Phase 3 planning.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Epic spec (source of truth for scope)
- `docs/plans/backend-modernization/01-kafka-activity-feed.md` — the literal spec this milestone implements (activity log, DLT, testing requirements)

### Project-level requirements
- `.planning/PROJECT.md` — Core Value, Current Milestone (v1.1), Active requirements
- `.planning/REQUIREMENTS.md` — ACTLOG-01/02/03, READ-01/02, RELY-01/02, TEST-01/02 (this phase's requirements)
- `.planning/ROADMAP.md` — Phase 3 goal, success criteria, dependency on Phase 2

### Milestone-level research (grounded findings)
- `.planning/research/SUMMARY.md`, `ARCHITECTURE.md`, `PITFALLS.md`, `FEATURES.md` — Phase 3-relevant sections: idempotent-consumer pattern, DLT/`DeadLetterPublishingRecoverer` details, pagination strategy discussion (already resolved to offset `Pageable` per D-09/REQUIREMENTS.md), open question on `ActivityLogEntity` id strategy

### Prior phase context (established conventions this phase reuses)
- `.planning/phases/02-kafka-foundation-domain-events-move-endpoint/02-CONTEXT.md` — the sealed `ActivityEvent` contract, `@Async` Kafka publish pattern, Kafka-down resilience decisions (D-01/D-02 from Phase 2)
- `.planning/phases/02-kafka-foundation-domain-events-move-endpoint/02-01-SUMMARY.md`, `02-02-SUMMARY.md`, `02-03-SUMMARY.md` — the 5 event records' exact shapes, `KafkaEventPublisher`, `KafkaTopics.ACTIVITY`, the local docker-compose stack this phase's Testcontainers test builds on top of

### Deferred/related items (do not re-litigate, but be aware of)
- `.planning/todos/pending/2026-08-01-account-for-schema-evolution-risk-when-changing-activityeven.md` — schema evolution risk on `ActivityEvent` shapes, relevant if this phase's work reveals any shape issues
- `.planning/seeds/SEED-001-add-a-confluent-schema-registry-avro-protobuf-in-front-of-th.md` — future Schema Registry idea, out of scope for this phase

### Codebase maps
- `.planning/codebase/TESTING.md` — test conventions (AAA, `@Nested`, `Assertions.catchException`, no mocks, `AbstractAppTest`/`AbstractAppE2ETest` base classes) this phase's tests must follow
- `.planning/codebase/ARCHITECTURE.md` — `OwnershipVerifierService.verifyOwnershipOfBoard` signature and ownership-verification chain the read endpoint reuses

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `OwnershipVerifierService.verifyOwnershipOfBoard(userId, boardId)` (`src/main/java/com/vrudenko/kanban_board/service/OwnershipVerifierService.java`, line ~33) — returns `Pair<UserEntity, BoardEntity>`, the exact ownership check `GET /boards/{boardId}/activity` must reuse unmodified.
- `ActivityEvent` sealed interface + its 5 implementing records (`event/` package, from Phase 2) — the wire contract the consumer deserializes.
- `GlobalExceptionHandler` — already correctly maps `AppAccessDeniedException`→401, `AppEntityNotFoundException`→404; no new exception types needed for this phase's read endpoint.
- `BaseEntity` (`entity/BaseEntity.java`) — ULID `id` via `@RandFlakeId`, the existing convention every other entity uses; open question (Claude's Discretion above) whether `ActivityLogEntity` follows this or uses `eventId` directly as PK.

### Established Patterns
- No paginated endpoint exists anywhere in this codebase yet — `GET /boards/{boardId}/activity` is the first. No existing `Pageable`/`Page<T>` usage to mirror; this phase establishes the convention.
- DTO layering convention: `{ObjectName}ResponseDTO` for reads — `ActivityLogResponseDTO` should follow this naming.
- Test conventions (from `.planning/codebase/TESTING.md`): `@Nested` inner classes per operation, AAA comments, `Assertions.catchException()` for exception paths (never `assertThrows`), no Mockito/mocking — real Spring wiring only (matches Phase 2's `RecordingActivityEventListener` precedent).
- `AbstractAppTest`/`AbstractAppE2ETest` base classes provide fixture creation and HTTP test helpers; Phase 2 already extended `AbstractAppTest` with `createColumnForUser` for cross-user fixtures — likely reusable for read-endpoint ownership tests (unowned board → 401).

### Integration Points
- New `activitylog` package (per epic spec + REQUIREMENTS.md ACTLOG-02 naming): `ActivityLogConsumer`, `ActivityLogEntity`, `ActivityLogRepository`, `ActivityLogService`, `ActivityController` (or similar split — planner's call per Claude's Discretion).
- `ActivityLogConsumer` consumes from `KafkaTopics.ACTIVITY` (`kanban.activity`, already defined in Phase 2's `constant/KafkaTopics.java`).
- Testcontainers-based E2E test builds on the local docker-compose Kafka stack pattern already proven in Phase 2 Plan 03 (real broker, no mocks).

</code_context>

<specifics>
## Specific Ideas

- User specifically walked through the full architecture rationale during discussion (why Kafka decouples the write path from logging, why the consumer persists into Postgres rather than serving reads from Kafka directly, why in-process consumer isn't an anti-pattern at this scale, and what KRaft is/why it replaces ZooKeeper) — these are understanding-building exchanges, not implementation decisions, but confirm the user has a clear mental model of what this phase is building and why.

</specifics>

<deferred>
## Deferred Ideas

- **Confluent Schema Registry (Avro/Protobuf)** — captured as SEED-001, triggers at a future Kafka-related milestone (v1.2/v2.0+), not this phase.
- **Schema evolution risk on `ActivityEvent` shapes** — captured as a pending todo, relevant only if/when event record shapes change in the future, not actionable within this phase.
- Production (EC2) Kafka deployment (KAFKA-V2-01) and cursor/keyset pagination (PAGE-V2-01) — already tracked as v2-deferred in REQUIREMENTS.md, re-confirmed here as out of scope.

### Reviewed Todos (not folded)
- "Create sequence diagram documenting full system flow for frontend handoff" — matched Phase 3 with score 0.6 (keyword overlap on kafka/activity/feed) but its own trigger condition ("once ALL functional epics are finished") explicitly hasn't fired yet — Epics 3-7 remain after this milestone. Left deferred.

</deferred>

---

*Phase: 3-Activity Log Consumer, Reliability & Read API*
*Context gathered: 2026-08-02*
