# Requirements: Kanban Board Backend — Kafka Activity Feed

**Defined:** 2026-08-01
**Core Value:** Deliver a real, event-driven per-board activity log (Kafka + consumer + idempotent persistence), plus the genuinely-missing "move task between columns" endpoint, as Epic 1 of the backend modernization plan.

## v1 Requirements

Requirements for this milestone. Each maps to roadmap phases.

### Kafka Infrastructure

- [ ] **KAFKA-01**: `docker-compose.yml` at repo root provides `postgres`, `kafka` (native KRaft image, no Zookeeper), and the app itself, so `docker compose up` gives a full local dev environment
- [ ] **KAFKA-02**: `spring-kafka` and `org.testcontainers:kafka` (+ `spring-boot-testcontainers`) are added to `build.gradle`, version-managed by Spring Boot 3.5.0's BOM (no explicit version pins)

### Domain Events

- [ ] **EVENT-01**: Five typed domain event records (`TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`) exist in a new `event` package, each carrying `userId`, the relevant entity id(s), a timestamp, and a UUID `eventId`
- [ ] **EVENT-02**: `TaskService`, `BoardService`, and `ColumnService` publish the corresponding event to the `kanban.activity` topic after each successful mutation, via `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)` — not a raw `KafkaTemplate.send()` call inside the `@Transactional` method — so a rolled-back transaction never produces a ghost event and a committed transaction never silently drops one

### Move Task Endpoint

- [ ] **MOVE-01**: `PATCH /tasks/{taskId}/move` moves a task to a target column, wired through `TaskService`, and publishes `TaskMovedEvent`
- [ ] **MOVE-02**: `PATCH /tasks/{taskId}/move` reuses the existing explicit `@Version` check-before-mutate convention (compare caller-supplied version before mutating, reject stale versions) rather than introducing a second, differently-behaved update path
- [ ] **MOVE-03**: `PATCH /tasks/{taskId}/move` rejects (400/403) a move where the target column belongs to a different board than the task's current board

### Activity Log Consumer & Persistence

- [ ] **ACTLOG-01**: A new `ActivityLogEntity`/`ActivityLogRepository` persists `boardId`, `userId`, `action`, `detail`, `createdAt`, and a UUID `eventId` with a database-level `UNIQUE` constraint on `eventId`
- [ ] **ACTLOG-02**: A `@KafkaListener`-based `ActivityLogConsumer` in a new `activitylog` package consumes `kanban.activity` and maps all five event types (not just `TaskMovedEvent`) to an `ActivityLogEntity` row
- [ ] **ACTLOG-03**: Redelivering an event with an already-seen `eventId` does not create a duplicate `ActivityLogEntity` row — idempotency is enforced by the database unique constraint, with `existsByEventId` as a fast-path check, not the sole safety net

### Read API

- [ ] **READ-01**: `GET /boards/{boardId}/activity` returns the board's activity log, newest-first, authorized via the existing `OwnershipVerifierService.verifyOwnershipOfBoard`
- [ ] **READ-02**: `GET /boards/{boardId}/activity` is paginated using standard Spring Data `Pageable` (offset-based, consistent with every other list endpoint in this codebase)

### Reliability

- [ ] **RELY-01**: A dead-letter topic (`kanban.activity.dlt`) via `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` isolates a poison message so it does not block the consumer from processing subsequent events
- [ ] **RELY-02**: A test intentionally fails a message and asserts it lands on `kanban.activity.dlt` — the dead-letter path is proven, not just configured

### Testing

- [ ] **TEST-01**: A Testcontainers-based integration test publishes a `TaskMovedEvent` end-to-end through a real embedded Kafka broker and asserts the corresponding `ActivityLogEntity` row appears
- [ ] **TEST-02**: A redelivery test publishes an event with the same `eventId` twice and asserts exactly one `ActivityLogEntity` row is created

## v2 Requirements

Deferred to a future release. Tracked but not in this milestone's roadmap.

### Kafka Follow-ups

- **KAFKA-V2-01**: Production (EC2) Kafka deployment — this milestone ships Kafka for local dev and Testcontainers-based tests only; standing up a production broker (networking, listener config for a public host, state persistence) is a separate future decision
- **PAGE-V2-01**: Cursor/keyset pagination on `GET /boards/{boardId}/activity` — research flags this as more technically correct for an unbounded, append-only feed, but this milestone ships offset `Pageable` for consistency with the rest of the API; revisit if activity-log scale becomes a real concern

### Carried Forward (from v1.0)

- **FULL-01..03**: `GET /boards/{boardId}/full` nested board→columns→tasks→subtasks read endpoint — still deferred from the v1.0 milestone, not part of this Kafka-focused milestone either

## Out of Scope

Explicitly excluded from this milestone. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Field-level diff logging (old value → new value per field) | Not asked for by the epic spec; needs a generic diffing layer across Task/Column/Board — disproportionate scope increase for coarse, action-typed events |
| Separate deployable microservice for the activity-log consumer | Explicitly deferred in PROJECT.md; the in-process `@KafkaListener` already demonstrates the event-driven pattern |
| DLT replay/reprocessing admin tooling | Not in the epic spec; adds a second consumer plus an admin-surface security question with no current need |
| Real-time push (WebSocket/SSE) of activity updates | Not in the epic spec; doubles this milestone's surface area (new delivery mechanism, WebSocket auth) for a feature not requested |
| Retention/archival policy for old activity rows | No signal this is needed at this project's scale; adds a scheduling/cleanup job for a hypothetical problem |
| Generic/pluggable event schema (`Map<String,Object>` payload instead of typed records) | Contradicts the epic's explicit instruction to define typed records; loses compile-time safety for no gain at 5 event types |
| Epics 3–7 (Flyway/OpenAPI polish, Redis, Testcontainers as a project-wide H2 replacement, Observability, Kubernetes) | Separate epics, not part of this milestone's scope — deferred to future milestones |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| KAFKA-01 | TBD | Pending |
| KAFKA-02 | TBD | Pending |
| EVENT-01 | TBD | Pending |
| EVENT-02 | TBD | Pending |
| MOVE-01 | TBD | Pending |
| MOVE-02 | TBD | Pending |
| MOVE-03 | TBD | Pending |
| ACTLOG-01 | TBD | Pending |
| ACTLOG-02 | TBD | Pending |
| ACTLOG-03 | TBD | Pending |
| READ-01 | TBD | Pending |
| READ-02 | TBD | Pending |
| RELY-01 | TBD | Pending |
| RELY-02 | TBD | Pending |
| TEST-01 | TBD | Pending |
| TEST-02 | TBD | Pending |

**Coverage:**

- v1 requirements: 16 total
- Mapped to phases: 0 (pending roadmap creation)
- Unmapped: 16 ⚠️ (filled by roadmapper)

---
*Requirements defined: 2026-08-01*
*Last updated: 2026-08-01 after requirements definition*
