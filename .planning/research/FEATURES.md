# Feature Research

**Domain:** Event-driven activity log / audit trail for a Kanban board API (Kafka-backed)
**Researched:** 2026-08-01
**Confidence:** MEDIUM (web-sourced patterns, cross-checked across multiple independent sources; one item — Spring Kafka DLT config — corroborated directly against official docs.spring.io reference)

## Context

This research covers the **new** v1.1 surface only: the per-board activity log (`GET /boards/{boardId}/activity`) and the event pipeline that feeds it (Kafka producer in Task/Board/Column services, `ActivityLogConsumer`, DLT), plus the `PATCH /tasks/{taskId}/move` endpoint that the log's first real event depends on. It assumes everything already validated in PROJECT.md (ownership verification chain, optimistic locking on Task/Column, session auth) as a given foundation, not something to re-research.

## Feature Landscape

### Table Stakes (Users Expect These)

Features a Trello/Jira-style activity feed is broken without.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Per-board activity feed, newest-first | This is the entire point of the feature — Jira's per-item History tab and Trello's card activity feed are both reverse-chronological. A feed showing oldest-first or unordered reads as broken. | LOW | `ORDER BY createdAt DESC` (or id DESC, since ULIDs are lexicographically time-sortable — reuse the existing ULID convention as a natural sort key instead of a separate timestamp index). |
| Human-readable action description ("Jane moved Task X to Done") | This is the literal feature described in the epic spec and what Trello/Jira users recognize. A feed of raw event JSON is not a feature, it's a debug log. | LOW–MEDIUM | Drives the `ActivityLogEntity.action` / `detail` split: `action` as a stable enum/string (`TASK_MOVED`), `detail` as the pre-rendered human string OR structured fields the API layer renders. Decide once, don't do both loosely. |
| Ownership-scoped access (only board owner can view its log) | Every other endpoint in this codebase enforces ownership via `OwnershipVerifierService`; an activity log that skips this would be the one inconsistent endpoint and a real access-control gap. | LOW | Directly reuses `OwnershipVerifierService.verifyOwnershipOfBoard` — already in the epic spec's plan. No new authz pattern needed. |
| Pagination | Board activity is unbounded and append-only; Jira/Trello both paginate history. Returning the full history on every request degrades linearly with board age. | LOW–MEDIUM | See dedicated pagination discussion below — this is the one place the epic spec is underspecified (says "paginated (`Pageable`)" without picking offset vs cursor). |
| Coverage of the core mutation types named in the spec (create, move, delete for Task; create for Column/Board) | Users expect the feed to reflect what actually happened; a feed that logs some actions and silently skips others (e.g. logs creates but not deletes) reads as buggy, not intentionally scoped. | LOW (each event is a thin publish call) | The epic spec explicitly lists `TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`. Table-stakes bar is: whatever's in that list must all land in the log, no partial coverage. |
| At-least-once delivery with no duplicate log rows on redelivery | Kafka's default delivery semantics are at-least-once; a naive consumer will double-log on any retry/rebalance. A feed with visible duplicate entries ("Jane moved Task X to Done" appearing twice) looks broken to a user even though the underlying event system is working as designed. | MEDIUM | This is the idempotent-consumption requirement already in the epic spec (UUID `eventId` + `existsByEventId` pre-check) — confirmed by research as the standard pattern, not a Kafka novelty. See Pitfalls research for the transactional-boundary details. |
| Poison-message isolation (one bad event doesn't stop the whole feed from updating) | If a single malformed/unexpected event permanently blocks the consumer, every board's activity log silently stops updating until someone notices and manually intervenes — invisible failure mode, worst kind for a feature whose entire value is "trustworthy history." | MEDIUM | Dead-letter topic (`kanban.activity.dlt`) via `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`, already scoped in the epic. |

### Differentiators (Competitive Advantage / Portfolio Value)

Not required by end users of a toy kanban app, but the actual point of this milestone per PROJECT.md's Core Value and the epic's stated rationale ("legitimate reason for Kafka to exist in a CRUD app").

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Event-driven (Kafka) write path instead of synchronous audit-row insert | Demonstrates decoupling the primary write path (task/board/column mutation) from a slower/optional side effect, replay/audit value, and a natural extension point for future consumers (notifications, webhooks) without touching `TaskService` again — this is the explicit "explanation to have afterward" in the epic spec. | MEDIUM–HIGH | The actual differentiator isn't the activity log UI (that's table stakes for a kanban app) — it's that it's event-sourced. Keep the sync-vs-async tradeoff articulable: what breaks if Kafka is down (answer: task mutations still succeed, activity log lags — this must be true, and should be a deliberate design property, not an accident). |
| Idempotent consumer with a demonstrable redelivery test | Being able to show — not just claim — "duplicate delivery doesn't duplicate the log row" is a concrete, interview-defensible artifact (the epic explicitly frames this as "the concrete 'at-least-once + idempotency' story worth being able to tell"). | LOW (once the base pattern is in) | The differentiator is the *test*, not just the code: a Testcontainers-based test that publishes the same `eventId` twice and asserts one row. |
| Dead-letter topic with inspectable failed messages | Shows operational maturity beyond "happy path Kafka consumer." | LOW–MEDIUM | Given this project's scope (no separate DLT consumer/replay tooling planned), the differentiator is bounded: configuring the DLT and proving a poison message lands there, not building a full DLT-replay admin feature (see Anti-Features). |
| `PATCH /tasks/{taskId}/move` as a real, independently useful endpoint | Currently `TaskEntity.column` can only be set at creation — this is a genuinely missing CRUD capability the activity-log work happens to unlock, not manufactured Kafka-bait. Standalone value even without the Kafka work. | LOW–MEDIUM | Needs the same explicit-version-check optimistic-locking pattern already established in `TaskService.updateById` (compare `dto.getVersion()` before mutating) — reuse the existing convention, don't invent a new one for this endpoint. |
| Cursor-based (keyset) pagination on the activity feed | Research confirms cursor/keyset pagination is now the recommended default for fast-changing, append-only, unbounded feeds specifically (vs. offset, which is fine for small/static datasets) — activity logs are the textbook example. Doing this instead of naive `Pageable` offset paging is a small extra step that's also more technically correct for this exact data shape. | LOW–MEDIUM | See dependency/decision note below — this is the one place worth deviating from the epic spec's literal "`Pageable`" wording if it's cheap to do (Spring Data supports keyset pagination natively via `Pageable` + `ScrollPosition`/`Window` in recent versions, so it may not even cost extra). Flag for roadmap: confirm Spring Boot 3.5.0's Spring Data JPA version supports `ScrollPosition`-based keyset pagination before committing to it; fall back to offset `Pageable` (already idiomatic Spring Data, zero new concepts) if not cleanly available — either choice is defensible, but pick one deliberately rather than defaulting to offset without considering it. |

### Anti-Features (Commonly Requested, Often Problematic — Out of Scope Here)

| Feature | Why It Seems Appealing | Why Problematic for This Scope | Alternative |
|---------|------------------------|----------------------------------|-------------|
| Field-level diff logging (old value → new value for every field on every entity) | Jira's History tab does this; feels "more complete" than coarse action logging. | Massive scope increase — needs a generic diffing layer across Task/Column/Board, doubles the event payload design work, and isn't asked for by the epic spec (which specifies coarse events like `TaskMovedEvent`, not field-diff events). Directly conflicts with PROJECT.md's scoping discipline (one-epic-per-PR). | Coarse, action-typed events as already scoped (`TaskCreatedEvent`, `TaskMovedEvent`, etc.) with a short human-readable `detail` string. Defer field-diffs to a future milestone if ever needed. |
| Separate microservice for the activity-log consumer | "More real" event-driven architecture; looks impressive. | Explicitly called out as out of scope in PROJECT.md: "the in-process `@KafkaListener` already demonstrates the event-driven pattern; a possible later epic, not this one." Extracting a deployable service adds infra (separate deployment, service discovery, network boundary) with no proportional payoff for a portfolio-scoped feature. | In-process `@KafkaListener` in a new `activitylog` package, as already scoped. |
| DLT replay/reprocessing admin tooling (endpoint or job to requeue DLT messages back to the main topic) | Natural-feeling "complete the story" follow-on to having a DLT at all. | Not mentioned in the epic spec; adds a whole second consumer + an admin-surface security question (who's allowed to trigger replay?) that doesn't exist elsewhere in this API. Real scope creep risk given the "Explanation to have afterward" in the epic is about explaining *what* at-least-once + DLT means, not about building operator tooling. | Configure the DLT, prove poison messages land there via the integration test. Manual/`kafka-console-consumer` inspection is sufficient for a portfolio project; note it as a natural extension point in the "explanation to have afterward" instead of building it. |
| Real-time push (WebSocket/SSE) of new activity-log entries to connected clients | "Live updating feed" is a nice UX and fits the "event-driven" framing narratively. | Not in the epic spec at all; introduces a second delivery mechanism (WebSocket session management) layered on top of the Kafka work, doubling this milestone's surface area for a feature nobody asked for. Session-based auth + WebSocket auth is its own non-trivial problem this codebase hasn't solved yet. | Plain polling `GET /boards/{boardId}/activity` — perfectly adequate for a kanban board's actual usage pattern, and what Trello/Jira's REST APIs also expose (their real-time UI layers are a separate, much larger subsystem not being replicated here). |
| Retention/archival policy for old activity-log rows (TTL, cold storage, etc.) | Real production audit systems need this eventually. | Zero signal this is needed at this project's scale (portfolio project, no real user growth curve); adds a scheduling/cleanup job with its own failure modes for a problem that doesn't exist yet. | Unbounded `ActivityLogEntity` table for now; note as a documented future concern if the project ever needs it, don't build against a hypothetical. |
| Generic/pluggable event schema (single `ActivityEvent` with a `Map<String,Object> payload` instead of typed records) | Feels more "flexible" and avoids defining N event classes. | Loses compile-time safety and directly contradicts the epic spec's explicit instruction to define typed records (`TaskCreatedEvent`, `TaskMovedEvent`, etc.) in a dedicated `event` package — also makes the Kafka JSON (de)serialization story messier (type headers, `TypeMapper` config) for no real gain at this event-type count (5 events). | Typed sealed-ish record hierarchy as scoped; keep the `Map`/generic approach as something to reconsider only if the event count grows into the dozens. |

## Feature Dependencies

```
OwnershipVerifierService.verifyOwnershipOfBoard (existing)
    └──required by──> GET /boards/{boardId}/activity (authz)

TaskEntity.@Version optimistic locking pattern (existing, Phase 1)
    └──reused by──> PATCH /tasks/{taskId}/move (must not bypass the version-check convention)

PATCH /tasks/{taskId}/move (new)
    └──produces──> TaskMovedEvent
                       └──required by──> ActivityLogConsumer's first real, demoable end-to-end case

spring-kafka dependency + docker-compose kafka service (new)
    └──required by──> KafkaTemplate publish calls in TaskService/BoardService/ColumnService
    └──required by──> @KafkaListener ActivityLogConsumer
    └──required by──> Testcontainers Kafka integration test

Domain event records (TaskCreatedEvent, TaskMovedEvent, TaskDeletedEvent, BoardCreatedEvent, ColumnCreatedEvent)
    └──required by──> ActivityLogConsumer (must map every event type to an ActivityLogEntity row)

ActivityLogEntity + ActivityLogRepository (new)
    └──required by──> ActivityLogConsumer (persistence target)
    └──required by──> GET /boards/{boardId}/activity (read path)

eventId (UUID) on every domain event
    └──required by──> idempotent consumption (ActivityLogRepository.existsByEventId check)

DefaultErrorHandler + DeadLetterPublishingRecoverer (new)
    └──enhances──> ActivityLogConsumer (poison-message isolation, does not block main feature)

Pagination strategy decision (offset Pageable vs cursor/keyset)
    └──affects──> GET /boards/{boardId}/activity response shape and ActivityLogRepository query method signature
```

### Dependency Notes

- **`PATCH /tasks/{taskId}/move` requires the existing `@Version` optimistic-locking convention:** this is the first genuinely new mutating endpoint since Phase 1 established explicit version-check-before-mutate on `TaskService.updateById`. It should follow the identical pattern (compare caller-supplied version before mutating, throw `OptimisticLockingFailureException` on mismatch) rather than introducing a second update path with different locking behavior — otherwise the endpoint becomes the one place a stale-move race is silently allowed.
- **`ActivityLogConsumer` requires all 5 event types to be handled, not just `TaskMovedEvent`:** the integration test in the epic spec only exercises the move-task path end-to-end, but the consumer's mapping logic needs a case (or matching strategy) for all 5 record types from day one, or `TaskCreatedEvent`/`BoardCreatedEvent`/`ColumnCreatedEvent`/`TaskDeletedEvent` publishes will either throw, get dropped, or (in the worst case) reach the DLT immediately because the consumer doesn't recognize them.
- **Pagination strategy decision affects the repository query, not just the controller:** if cursor/keyset pagination is chosen, `ActivityLogRepository` needs a query shaped around `(createdAt, id) < cursor` rather than a plain `findByBoardId(Pageable)`. This choice is cheap to make now and expensive to change after the endpoint ships with real data, so it belongs in phase planning, not left implicit.
- **DLT setup is additive, not blocking:** the dead-letter topic and its `DeadLetterPublishingRecoverer` can be built after the core publish→consume→persist path works — it wraps failure handling around an already-working consumer, it isn't a prerequisite for the consumer to exist. Sequence it after the happy path in phase planning.

## MVP Definition

### Launch With (v1.1, this milestone)

Everything already scoped in the epic spec — confirmed by this research as the right table-stakes + differentiator set, nothing more:

- [ ] `docker-compose.yml` (postgres + kafka KRaft + app) — enables everything else to be developed/tested locally
- [ ] `spring-kafka` dependency + `KafkaTemplate<String, Object>` publishing to `kanban.activity`
- [ ] 5 typed domain event records (`TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`), each with `userId`, entity id(s), timestamp, and a UUID `eventId`
- [ ] `PATCH /tasks/{taskId}/move` — real missing feature, reuses existing version-check convention
- [ ] `ActivityLogConsumer` (`@KafkaListener`) mapping all 5 event types to `ActivityLogEntity`
- [ ] `GET /boards/{boardId}/activity`, paginated, ownership-verified via existing `OwnershipVerifierService`
- [ ] Idempotent consumption via UUID `eventId` + `existsByEventId` pre-check
- [ ] Dead-letter topic (`kanban.activity.dlt`) via `DefaultErrorHandler`
- [ ] Testcontainers-based Kafka integration test (publish → consume → persist, end-to-end)

### Add After Validation (not this milestone, but cheap to consider now)

- [ ] Cursor/keyset pagination instead of plain offset `Pageable`, if Spring Data JPA on Boot 3.5.0 supports it cleanly — worth a quick spike before committing during phase planning, since it's the one spec-underspecified decision (see Differentiators)
- [ ] Idempotency-key redelivery test as an explicit, separate test case beyond the one end-to-end Testcontainers test (publish same `eventId` twice, assert single row) — cheap to add once the base consumer exists, strengthens the "concrete at-least-once story" the epic wants to be able to tell

### Future Consideration (v2+, explicitly deferred)

- [ ] Field-level diff logging — defer until/unless a real need appears; not asked for by the epic
- [ ] Separate deployable microservice for the consumer — explicitly deferred in PROJECT.md
- [ ] DLT replay/reprocessing tooling — no current need, adds an admin-surface security question
- [ ] Real-time push (WebSocket/SSE) of activity updates — not in scope, doubles surface area
- [ ] Retention/archival policy for old activity rows — no signal of need at this project's scale

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|----------------------|----------|
| `PATCH /tasks/{taskId}/move` | HIGH (genuinely missing CRUD gap) | LOW–MEDIUM | P1 |
| Kafka event publish from Task/Board/Column services | MEDIUM (invisible to end users, high portfolio value) | MEDIUM | P1 |
| `ActivityLogConsumer` + `ActivityLogEntity` persistence | HIGH (this is the feature) | MEDIUM | P1 |
| `GET /boards/{boardId}/activity` (paginated, ownership-verified) | HIGH | LOW–MEDIUM | P1 |
| Idempotent consumption (`eventId` dedup) | MEDIUM (invisible when working, embarrassing when missing) | LOW–MEDIUM | P1 |
| Dead-letter topic | LOW (operational safety net, not user-facing) | LOW–MEDIUM | P1 (small, and explicitly scoped in the epic) |
| Cursor/keyset pagination (vs. offset) | LOW–MEDIUM (correctness/scale detail, not visible short-term) | LOW (if Spring Data supports it natively) / MEDIUM (if hand-rolled) | P2 |
| Testcontainers Kafka integration test | MEDIUM (confidence + the demoable artifact) | MEDIUM | P1 |
| Field-level diffs | LOW (nice-to-have completeness) | HIGH | P3 (deferred) |
| DLT replay tooling | LOW | MEDIUM–HIGH | P3 (deferred) |
| Real-time push | LOW–MEDIUM | HIGH | P3 (deferred) |

**Priority key:**
- P1: In this milestone (matches the epic spec's scoped task list)
- P2: Worth a deliberate decision during phase planning, low cost either way
- P3: Explicitly out of scope this milestone, revisit only if a real need emerges

## Competitor Feature Analysis

| Feature | Jira | Trello | Our Approach |
|---------|------|--------|--------------|
| Per-item activity/history | Per-issue History tab shows field-level changes (status, assignee, comments); separate system-level Audit Log for admin-level events (permission/project changes) | Per-card activity feed (comments, moves, member changes) is a core UI feature, though not deeply documented publicly; separate Automation/Butler logs for rule-trigger history | Single per-board feed (not per-card/per-task) with coarse, action-typed entries — narrower scope than Jira's two-tier system, matches Trello's card-feed spirit but scoped to board level per the epic |
| Delivery/consistency model | Synchronous, presumably transactional writes as part of the primary mutation (no public documentation of async/event-driven internals) | Same — no public indication of an event-driven pipeline; likely synchronous write-through | Deliberately async/event-driven (Kafka) — this is the point of the milestone, not a constraint imposed by matching competitors. The differentiator is architectural, not user-visible. |
| Pagination on activity history | Present (large boards paginate history), exact mechanism not publicly documented | Present in UI (infinite-scroll-style loading), exact API mechanism not publicly documented | Cursor/keyset pagination recommended by general REST API research for this data shape (append-only, fast-changing); plain offset `Pageable` acceptable fallback — see Differentiators |
| Idempotent/duplicate-safe writes | Not publicly documented (internal detail) | Not publicly documented (internal detail) | Explicit, demoable idempotency via UUID `eventId` + dedup check — a differentiator specifically because it's provable in this project's test suite, not because competitors visibly lack it |

## Sources

- [Audit activities in Jira | Atlassian Support](https://support.atlassian.com/jira-cloud-administration/docs/audit-activities-in-jira-applications/) — MEDIUM confidence (vendor docs, general web search)
- [Auditing in Jira: How to Track User and System Changes Effectively — Atlassian Community](https://community.atlassian.com/forums/App-Central-articles/Auditing-in-Jira-How-to-Track-User-and-System-Changes/ba-p/3079322) — MEDIUM confidence
- [View automation logs | Trello | Atlassian Support](https://support.atlassian.com/trello/docs/opening-the-command-log/) — MEDIUM confidence (covers Trello automation logs, not general card activity feed, which is not well-documented publicly — noted as a gap)
- [Kafka Idempotent Consumer & Transactional Outbox — Lydtech Consulting](https://www.lydtechconsulting.com/blog-kafka-idempotent-consumer.html) — MEDIUM confidence (practitioner blog, cross-checked against multiple independent sources)
- [Kafka Deduplication Patterns — Lydtech Consulting](https://www.lydtechconsulting.com/blog/kafka-deduplication-patterns---part-2-of-2) — MEDIUM confidence
- [Building Reliable Event-Driven Architectures with Kafka — Java Code Geeks](https://www.javacodegeeks.com/2025/09/understanding-event-driven-architectures-kafka-outbox-pattern-and-exactly-once-guarantees.html) — MEDIUM confidence
- [DeadLetterPublishingRecoverer (Spring for Apache Kafka 4.1.0 API) — docs.spring.io](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/DeadLetterPublishingRecoverer.html) — HIGH confidence (official framework documentation)
- [Handling Exceptions :: Spring Kafka — docs.spring.io](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html) — HIGH confidence (official framework documentation)
- [Pagination Best Practices in REST API Design — Speakeasy](https://www.speakeasy.com/api-design/pagination/) — MEDIUM confidence
- [API Pagination Best Practices: Cursor, Offset & Keyset Explained — Knit](https://www.getknit.dev/blog/api-pagination-best-practices) — MEDIUM confidence
- [Testing Applications :: Spring Kafka — docs.spring.io](https://docs.spring.io/spring-kafka/reference/testing.html) — HIGH confidence (official framework documentation)
- [Testing Kafka Applications: Testcontainers, Embedded Kafka, and Mocks — Conduktor](https://www.conduktor.io/blog/testing-kafka-testcontainers-embedded-mocks) — MEDIUM confidence
- Codebase inspection: `TaskService.java`, `TaskEntity.java`, `OwnershipVerifierService.java` (verified directly, HIGH confidence) — used to confirm existing `@Version` convention and ownership-verification chain that new endpoints must reuse
- `docs/plans/backend-modernization/01-kafka-activity-feed.md` (project epic spec, HIGH confidence — authoritative for this milestone's exact scope)
- `.planning/PROJECT.md` (HIGH confidence — authoritative for what's in/out of scope)

---
*Feature research for: event-driven activity log (Kafka) — kanban-board-backend v1.1*
*Researched: 2026-08-01*
