# Epic 1 — Kafka + event-driven activity feed

[← back to plan index](README.md) · Effort: 1–2 weeks · Priority: **Highest**

**Why this first:** Kafka is the single highest-value gap — required in a large minority of Java
postings overall and close to default in the Polish banking/fintech/consultancy segment that
dominates local hiring. Everything else on the list is cheaper to close or lower-frequency.

**The feature, not just the plumbing:** an **audit/activity log per board** — "Jane moved Task X to
Done", "Jane created Column Y" — a real kanban feature (Trello/Jira both have this), implemented as
an event-driven side effect instead of a synchronous write. This gives you a legitimate reason for
Kafka to exist in a CRUD app, which is exactly the objection interviewers raise about resume-driven
tech ("why does a kanban board need Kafka?") — have this answer ready either way, but it's stronger
with the feature framing.

## Tasks

- Add `docker-compose.yml` at repo root (currently absent — only a `Dockerfile` exists) with
  services: `postgres`, `kafka` (use `apache/kafka` native KRaft image, no separate Zookeeper),
  and the app itself, so `docker compose up` gives a full local dev environment.
- Add `spring-kafka` to `build.gradle`.
- Define domain events as simple records in a new `com.vrudenko.kanban_board.event` package:
  `TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`,
  `ColumnCreatedEvent` — each carrying `userId`, the relevant entity id(s), and a timestamp.
- In `TaskService`, `BoardService`, `ColumnService`: after each successful mutating operation
  (`save`, `updateById`, `deleteById`, and the not-yet-existing "move task to another column"
  operation — see below), publish the corresponding event via a `KafkaTemplate<String, Object>`
  to a topic named `kanban.activity`.
- **New feature this unlocks:** currently there's no "move task between columns" endpoint —
  `TaskEntity.column` can only be set at creation. Add `PATCH /tasks/{taskId}/move` with a
  `MoveTaskRequestDTO { targetColumnId }`, wired through `TaskService`, that publishes
  `TaskMovedEvent`. This is a real missing feature, not manufactured Kafka-bait.
- Add a `@KafkaListener`-based `ActivityLogConsumer` in a new
  `com.vrudenko.kanban_board.activitylog` package that consumes `kanban.activity`, maps each
  event to a new `ActivityLogEntity` (`boardId`, `userId`, `action`, `detail`, `createdAt`), and
  persists it via a new `ActivityLogRepository`.
- Add `GET /boards/{boardId}/activity` returning the log, paginated (`Pageable`), authorized via
  the existing `OwnershipVerifierService.verifyOwnershipOfBoard`.
- Handle **idempotent consumption**: give each event a UUID `eventId`; before inserting, check
  `ActivityLogRepository.existsByEventId(...)` so redelivery doesn't double-log. This is the concrete
  "at-least-once + idempotency" story interviewers ask for.
- Configure a dead-letter topic (`kanban.activity.dlt`) via `DefaultErrorHandler` so a poison
  message doesn't block the consumer.
- **Testing:** add `org.testcontainers:kafka` (this is also how you formalize Testcontainers —
  see [Epic 5](05-testcontainers.md)) and write one integration test that publishes a
  `TaskMovedEvent` end-to-end through a real embedded Kafka broker and asserts the
  `ActivityLogEntity` row appears.

## Interview-ready explanation to have afterward

Why Kafka over a direct synchronous write (decoupling the write path from a slower/optional side
effect; replay/audit value; a natural place to later fan out to notifications/webhooks without
touching `TaskService` again), what "at least once" means for your consumer, and how your
idempotency key prevents duplicate log rows on redelivery.
