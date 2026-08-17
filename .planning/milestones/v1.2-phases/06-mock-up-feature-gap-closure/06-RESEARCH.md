# Phase 6: Mock-up Feature Gap Closure - Research

**Researched:** 2026-08-08
**Domain:** Spring Boot 3.5.0 / Java 21 REST API extension — new routes, a new position-ordering
scheme, a nested read DTO, a new Avro event type, and an in-place ID-scheme swap on an existing
column
**Confidence:** HIGH (all 7 items grounded directly in this repository's own code, read this
session; the two genuinely external questions — integer-position renumbering pitfalls and
MapStruct mapper composition — are CITED against external sources, not ASSUMED)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Ordering (task & column position)**
- D-01: Build ordering fully — a position field AND working reorder endpoints for both tasks and
  columns — even though the mock-up's own visual pass found no grab handle/drag shadow/drop
  indicator; build to conventional Kanban expectations regardless.
- D-02: Position scheme is a simple `Integer position` column with renumber-on-insert (shift
  subsequent siblings' indices in the same transaction) — not fractional/gap-based keys
  (LexoRank-style). Fractional keys explicitly rejected as disproportionate complexity.
- D-03: Ordering scope covers both `TaskEntity.position` (within a column) and
  `ColumnEntity.position` (within a board).
- D-04: Task move and task reorder are one endpoint, not two: extend `MoveTaskRequestDTO` with a
  `targetPosition` field alongside the existing `targetColumnId`/`version`.

**Column deletion**
- D-05: Deleting a column cascades to its tasks and subtasks — no reassignment option. Reuses
  `TaskService.deleteAllByColumn`'s existing batched delete directly.
- D-06: Column deletion publishes a new `ColumnDeletedEvent` to the activity log, mirroring
  `TaskDeletedEvent`'s shape: new sealed record (added to `ActivityEvent`'s permits list), a new
  `.avsc` Avro schema, and both switch arms in `ActivityEventAvroMapper`.
- D-07: No non-empty-column guard. `DELETE /boards/{boardId}/columns/{columnId}` always cascades
  once ownership is verified, regardless of task count.

**Board creation**
- D-08: Client submits initial columns via create-then-batch-add: `POST /boards` with just
  `{name}`, then one `POST /boards/{boardId}/columns` call per initial column.
  `SaveBoardRequestDTO` does NOT grow a nested columns list. `UserService.addBoardByUserId` gets
  wired onto a new `BoardController` mapping with no DTO changes.
- D-09: Add board-name uniqueness validation per user, applying to both `POST /boards` (create)
  and `PUT /boards/{boardId}` (rename). Exact validation mechanics (case sensitivity, HTTP
  status/error shape) are Claude's discretion.

**Theme persistence**
- D-10: Build full server-side theme persistence in Phase 6 — a field on
  `UserEntity`/`UserResponseDTO` plus a read/write endpoint — even though no frontend exists yet.
- D-11: Theme is represented as an enum, `LIGHT`/`DARK` — not a free string.
- D-12: Default value for users with no explicit preference is `LIGHT` (not nullable).

### Claude's Discretion
- Exact HTTP status/error shape for the board-name-uniqueness conflict (D-09) — align with
  `GlobalExceptionHandler`'s existing conflict conventions (409, matching optimistic-locking) or
  use a 400 field-validation error — planner's call.
- Exact renumbering mechanics for `position` inserts/moves (D-02) — e.g. whether every sibling
  after the insertion point shifts by exactly 1, or a larger gap strategy within the "simple
  integer" constraint — planner's call, must stay within D-02's no-fractional-keys boundary.
- Whether `ColumnDeletedEvent`'s Avro schema is registered as a wholly new subject or extends an
  existing one — follow Phase 4's "one schema per event type" convention (D-03 in
  `04-CONTEXT.md`) rather than deciding fresh here.
- Nested DTO class names/package structure for `GET /boards/{boardId}/full` — follow the existing
  `dto/{domain}_dto/` package convention; confirm exact naming (e.g. `BoardFullResponseDTO`) and
  whether MapStruct can compose the existing per-entity mappers or needs a hand-written
  aggregation step.
- Whether `SubtaskService.updateById` needs the `entityManager.flush()` call that
  `TaskService.updateById`/`ColumnService.updateById` already have — should mirror the existing
  pattern exactly; planner confirms exact placement.

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within the six gap-doc items plus the one explicitly-folded, topically
unrelated Snowflake-ID todo (treated as a seventh, independent deliverable riding along in this
phase, not related work to the other six).

### Folded Todo (treated as a locked, in-scope deliverable per explicit user request)
"Use Snowflake ID generator for activity log events" — switch `ActivityLogEntity.eventId` (the
activity-log dedupe key) from `UUID.randomUUID()` to a Snowflake-style time-ordered ID. Folded in
at the user's explicit request despite a topical mismatch flagged during discussion; its own file
scope is `event/` and `entity/ActivityLogEntity.java`. Per the todo's own text it "should probably
follow whatever broader decision on project-wide ID generation strategy lands on" — flag to the
user if that broader decision hasn't been made before implementing this in isolation (see this
research's finding that `RandFlakeGenerator` already IS that broader decision, currently scoped
only to entity primary keys).
</user_constraints>

<phase_requirements>
## Phase Requirements

`.planning/REQUIREMENTS.md` has no requirement IDs for this phase yet (it is currently scoped
entirely to the v1.2 Infra Migration & Schema Registry milestone). This research proposes a new
`GAP-01`..`GAP-07` block, one per candidate feature named in the phase description, for the
planner to add to REQUIREMENTS.md's traceability table.

| ID | Description | Research Support |
|----|-------------|-------------------|
| GAP-01 | Expose `POST /boards`, wiring `UserService.addBoardByUserId` onto `BoardController`; add board-name uniqueness validation for both create and rename (D-08/D-09) | Don't Hand-Roll table (uniqueness check); Code Examples (exception-type recommendation) |
| GAP-02 | Add `DELETE /boards/{boardId}/columns/{columnId}`, cascading to tasks/subtasks via `TaskService.deleteAllByColumn`, publishing a new `ColumnDeletedEvent` with its own Avro schema (D-05/D-06/D-07) | Architecture Patterns — Pattern 2 (exact mechanical steps, cites every file to touch) |
| GAP-03 | Build `position` field + reorder endpoints for both `TaskEntity` and `ColumnEntity`; simple `Integer position` with renumber-on-insert; `MoveTaskRequestDTO` gains `targetPosition` (D-01..D-04) | Architecture Patterns — Pattern 4; Common Pitfalls (concurrency); Open Question 1 |
| GAP-04 | `GET /boards/{boardId}/full` — single nested read replacing the four-round-trip fan-out; deliberately breaks the flat-DTO convention | Architecture Patterns — Pattern 3 (MapStruct composition + query-count analysis) |
| GAP-05 | Per-user theme persistence — `theme` enum (LIGHT/DARK, default LIGHT) on `UserEntity`/`UserResponseDTO` plus a read/write endpoint; new `UserController` (D-10..D-12) | Code Examples — UserController recommendation |
| GAP-06 | `UpdateSubtaskRequestDTO`/`SubtaskResponseDTO`/`SubtaskEntity` gain `@Version Long version`; explicit compare-then-409 in `SubtaskService.updateById`; the `entityManager.flush()` call Task/Column updates already have | Architecture Patterns — Pattern 1 (exact code to mirror) |
| GAP-07 | Switch `ActivityLogEntity.eventId` from `UUID.randomUUID()` to a Snowflake-style time-ordered ID generator (folded todo, independent of GAP-01..06) | Don't Hand-Roll table (reuse `RandFlakeGenerator`); Pitfalls 2, 3, 4 |

</phase_requirements>

## Summary

This phase is unusually low-risk on the "what library do we need" axis — **zero new external
dependencies are required for any of the 7 items**. Every one of the six gap-doc features plus the
folded Snowflake-ID todo is buildable with tools already in `build.gradle` (Spring Data JPA,
MapStruct, the existing `RandFlakeGenerator`, the existing Avro/Confluent Schema Registry
pipeline). The work is entirely mechanical composition of patterns this codebase has already
proven out in Phase 1–5: the explicit version-compare-then-409-then-flush idiom
(`ColumnService.updateById`/`TaskService.updateById`), the batched cascade-delete idiom
(`TaskService.deleteAllByColumn`), the sealed-interface-event + one-`.avsc`-per-type idiom
(`ActivityEvent`/`ActivityEventAvroMapper`), and the ownership-chain-first idiom
(`OwnershipVerifierService`).

The one item that is genuinely greenfield — task/column ordering (D-01..D-04) — has no existing
pattern to copy from this codebase, so this research leans on external sources (CITED, not
ASSUMED) for the concurrency pitfalls of the "simple integer, renumber-on-insert" scheme the user
already locked in via D-02. The single most important cross-cutting finding, verified by reading
`application.properties`/`application-test.properties` directly, is that **`spring.jpa.hibernate.ddl-auto=validate`
is the default in both the main and test profiles** (only the local-dev `docker-compose.yml`
overrides it to `update`) — every entity field this phase adds (`TaskEntity.position`,
`ColumnEntity.position`, `SubtaskEntity.version`, `UserEntity.theme`) is a **hard compile-and-boot
requirement to also write a new Flyway migration** (`V5__*.sql`), not an optional nicety. Skipping
the migration does not fail silently; it fails Spring context startup in every test, in the same
way `FlywaySchemaProvenanceTest` already proves V1–V4 do.

**Primary recommendation:** treat this phase as one Flyway migration (`V5`) plus six focused,
independently testable slices layered directly onto the existing service/controller/mapper
pattern — no new architectural layer, no new library, no framework upgrade.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Board creation route (GAP-01) | API / Backend | Database / Storage (new uniqueness constraint) | Pure REST wiring onto an existing, already-transactional service method |
| Column deletion + cascade + event (GAP-02) | API / Backend | Database / Storage (cascade delete), Kafka (new event) | Mirrors the existing board-delete cascade and the existing event-publish pattern exactly |
| Task/column ordering (GAP-03) | API / Backend | Database / Storage (new `position` column, renumber transaction) | Ordering state must be server-authoritative and transactionally consistent — cannot live client-side |
| Full-board nested read (GAP-04) | API / Backend | Database / Storage (eager-fetch query shape) | A dedicated read-optimized query + DTO composition; no new tier, but a deliberate exception to the flat-DTO convention |
| Per-user theme persistence (GAP-05) | API / Backend | Database / Storage (new `theme` column) | Small, single-user-scoped preference; server persistence only because D-10 asked for cross-device sync |
| Subtask optimistic locking (GAP-06) | API / Backend | Database / Storage (new `version` column) | Mirrors the already-proven Column/Task locking pattern; no new tier |
| Snowflake-style activity-log `eventId` (GAP-07) | Database / Storage | API / Backend (event package, all 3 publishing services) | An ID-generation-strategy change to a stored/streamed identifier, not a new capability — touches the persistence layer's column type and the Kafka/Avro wire type equally |

## Standard Stack

### Core

No new libraries. Every dependency this phase needs is already declared in `build.gradle`:

| Library | Version (verified in repo) | Purpose in this phase | Why no alternative needed |
|---------|---------|---------|--------------|
| Spring Data JPA / Hibernate | via Spring Boot 3.5.0 BOM | `Integer position` column, renumber-on-insert transactions, new `@Version` field on `SubtaskEntity` | Same ORM already used for every other entity/version field in this codebase |
| MapStruct | 1.5.3 | Nested `BoardFullResponseDTO` composition (GAP-04) via mapper `uses` | Already the sole Entity↔DTO mapping tool; see Architecture Patterns below for the exact composition mechanism |
| Apache Avro / `gradle-avro-plugin` | 1.12.1 / 1.9.1 (confirmed in `build.gradle` comments, Phase 4) | New `AvroColumnDeletedEvent.avsc` (GAP-02) | Established Phase 4 pipeline — one schema per event type (D-03) |
| `io.confluent:kafka-avro-serializer` | 7.8.9 (confirmed in `build.gradle`) | Registering/publishing the new `ColumnDeletedEvent` subject | Same producer/consumer/registrar path every existing event already uses |
| `RandFlakeGenerator` (in-repo, `config/RandFlakeGenerator.java`) | n/a (project code) | GAP-07's Snowflake-style `eventId` — **this is already a time-ordered, Base36-encoded Snowflake-style generator**, currently used only for `BaseEntity.id` | Directly satisfies the todo's ask ("switch `eventId` generation to a Snowflake-style ID") with zero new code beyond exposing a static/callable entry point — see Pitfall 4 |

**No `Installation` section is needed** — nothing to add to `build.gradle`.

### Alternatives Considered

| Instead of | Could use | Tradeoff | Verdict |
|------------|-----------|----------|---------|
| Simple `Integer position` + renumber-on-insert (D-02, locked) | Fractional/gap-based keys (LexoRank-style) | Fractional keys avoid renumbering writes entirely but add a new concept (string/decimal sort keys, periodic rebalancing) this codebase has never needed | Rejected by the user in CONTEXT.md D-02 — not re-litigated here |
| `RandFlakeGenerator` reused for `eventId` (GAP-07) | A dedicated `Snowflake4j`/`snowflake-id` library | An external library adds a dependency for something the codebase already implements in ~15 lines; the todo's own text says this "should probably follow whatever [the] broader decision on project-wide ID generation strategy lands on" — reusing the existing generator *is* that broader decision already made | Recommend reuse; flag as Claude's-discretion-equivalent for the planner since CONTEXT.md did not pre-decide the exact mechanism |
| Hand-written aggregation service for GAP-04 | MapStruct `uses` composition | Both work; MapStruct `uses` is less code and stays inside the existing mapping convention | Recommend MapStruct `uses`, detailed below |

## Package Legitimacy Audit

**Not applicable — no new external packages are introduced by this phase.** Every library used
(Spring Data JPA, MapStruct, Avro/Confluent tooling) is already present in `build.gradle` and was
verified there by direct read this session. No `npm view`/`pip index`/`cargo search`-equivalent
check applies; there is nothing new to check against a registry.

## Architecture Patterns

### System Architecture Diagram

```
                    ┌─────────────────────────────────────────────┐
                    │              HTTP Client                     │
                    └───────────────────────┬───────────────────────┘
                                             │
        ┌────────────────────────────────────┼────────────────────────────────────┐
        │                                     ▼                                    │
        │   POST /boards (GAP-01)   DELETE /boards/{id}/columns/{id} (GAP-02)      │
        │   PATCH /tasks/{id}/move + targetPosition (GAP-03)                       │
        │   PATCH /boards/{id}/columns/{id}/reorder (GAP-03)                       │
        │   GET /boards/{id}/full (GAP-04)   GET/PUT /users/me/theme (GAP-05)      │
        │   PUT .../subtasks/{id} + version (GAP-06)                               │
        │                     BoardController / ColumnController /                 │
        │                     TaskMoveController / SubtaskController / (new)       │
        │                     UserController                                      │
        └────────────────────────────────────┬────────────────────────────────────┘
                                             │  @PreAuthorize("isAuthenticated()")
                                             ▼
        ┌────────────────────────────────────────────────────────────────────────┐
        │  OwnershipVerifierService.verifyOwnershipOf{Board,Column,Task,Subtask}   │
        │  — every mutating/reading call re-derives from this chain (CODE_STYLE#2) │
        └────────────────────────────────────┬────────────────────────────────────┘
                                             ▼
        ┌────────────────────────────────────────────────────────────────────────┐
        │   BoardService / ColumnService / TaskService / SubtaskService / UserService│
        │   • GAP-01: UserService.addBoardByUserId (already implemented) + new     │
        │     name-uniqueness check                                                │
        │   • GAP-02: ColumnService.deleteById → reuses TaskService.deleteAllByColumn│
        │     → publishes ColumnDeletedEvent (after commit)                        │
        │   • GAP-03: renumber-on-insert transaction (shift siblings' `position`)  │
        │   • GAP-04: BoardService.findFullById → JOIN-FETCH query → MapStruct     │
        │     `uses`-composed BoardFullResponseDTO                                 │
        │   • GAP-05: UserService.updateTheme                                      │
        │   • GAP-06: SubtaskService.updateById + explicit version-compare + flush │
        └───────────┬────────────────────────────────────────────┬────────────────┘
                    │ (JPA)                                       │ (Spring event, AFTER_COMMIT)
                    ▼                                              ▼
        ┌───────────────────────┐               ┌──────────────────────────────────┐
        │  PostgreSQL (Flyway V5)│               │ KafkaEventPublisher → Avro serde  │
        │  tasks.position         │               │ → kanban.activity topic →         │
        │  columns.position       │               │ Redpanda Schema Registry          │
        │  subtasks.version        │               │ (new AvroColumnDeletedEvent       │
        │  users.theme              │               │  subject, RecordNameStrategy)     │
        │  boards uk(user_id,name)  │               └──────────────────────────────────┘
        │  activity_log.event_id     │
        │  (uuid → varchar, GAP-07)   │
        └───────────────────────────┘
```

### Recommended Project Structure

No new packages. New files land in the existing per-domain layout:

```
src/main/java/com/vrudenko/kanban_board/
├── controller/
│   ├── BoardController.java        # + POST /boards (GAP-01)
│   ├── ColumnController.java       # + DELETE /{columnId} (GAP-02), + reorder (GAP-03)
│   ├── TaskMoveController.java     # MoveTaskRequestDTO + targetPosition (GAP-03)
│   └── UserController.java         # NEW — theme read/write (GAP-05)
├── service/
│   ├── ColumnService.java          # deleteById() replaces the "// TODO: implement delete logic"
│   ├── TaskService.java            # position renumbering on save/move
│   ├── SubtaskService.java         # version-compare + flush (GAP-06)
│   └── UserService.java            # updateTheme()
├── dto/
│   ├── board_dto/BoardFullResponseDTO.java   # NEW, nested (GAP-04)
│   ├── column_dto/ColumnFullResponseDTO.java # NEW, nested (GAP-04)
│   ├── task_dto/TaskFullResponseDTO.java     # NEW, nested (GAP-04)
│   ├── column_dto/ReorderColumnRequestDTO.java # NEW (GAP-03)
│   └── user_dto/UpdateThemeRequestDTO.java   # NEW (GAP-05)
├── entity/
│   ├── TaskEntity.java             # + position
│   ├── ColumnEntity.java           # + position
│   ├── SubtaskEntity.java          # + version
│   └── UserEntity.java             # + theme (ThemePreference enum)
├── mapper/
│   └── BoardFullMapper.java        # NEW — @Mapper(uses = {ColumnMapper.class, TaskMapper.class, SubtaskMapper.class})
├── event/
│   └── ColumnDeletedEvent.java     # NEW record, added to ActivityEvent's permits list
├── event/avro/
│   └── ActivityEventAvroMapper.java # + ColumnDeletedEvent switch arm (toAvro/toDomain)
├── constant/
│   └── ApiPaths.java               # + USERS, REORDER, THEME path segments
src/main/avro/
└── AvroColumnDeletedEvent.avsc     # NEW (GAP-02, mirrors AvroTaskDeletedEvent.avsc exactly)
src/main/resources/db/migration/
└── V5__<name>.sql                  # NEW — every field above in one migration
```

### Pattern 1: Explicit version-compare-then-409-then-flush (GAP-06)

**What:** Before mutating, compare the caller-supplied version against the just-loaded managed
entity; if it differs, throw `OptimisticLockingFailureException` (→ `409` via
`GlobalExceptionHandler`). After `repository.save(entity)`, call `entityManager.flush()` so the
response DTO carries Hibernate's newly-incremented `@Version`, not the stale pre-update value.

**When to use:** Every `Update*RequestDTO`-driven mutation on a `@Version`-guarded entity — this
is CODE_STYLE.md rule 6's fixed shape, and `SubtaskService.updateById` currently violates it (no
`version` field at all).

**Example — read directly from `service/TaskService.java:103-132`:**
```java
// dto.getVersion() is read ONLY here, for this stale-write precondition check — it is
// never assigned onto `task`. The version value that actually gets persisted is generated
// entirely by Hibernate's own @Version increment mechanism when the UPDATE statement runs
// (forced below via entityManager.flush()), independent of whatever value the client sent.
if (!task.getVersion().equals(dto.getVersion())) {
    throw new OptimisticLockingFailureException(
            "Task was modified by another request, please refetch.");
}
if (Optional.ofNullable(dto.getTitle()).isPresent()) {
    task.setTitle(dto.getTitle());
}
taskRepository.save(task);
entityManager.flush();
return taskMapper.toTaskResponseDTO(task);
```
`SubtaskService.updateById` (`service/SubtaskService.java:49-65`) currently has neither the
version-compare guard nor the `entityManager.flush()` call — GAP-06 is exactly adding both, in the
same order, to this one method.

### Pattern 2: Cascade delete + after-commit event publish (GAP-02)

**What:** `ColumnService` gains a `deleteById(userId, columnId)` that (a) verifies ownership via
`OwnershipVerifierService.verifyOwnershipOfColumn`, (b) calls the existing
`taskService.deleteAllByColumn(column)` (already batches subtask+task deletes via bulk JPQL —
see `service/TaskService.java:245-257`), (c) deletes the column row itself, (d) publishes a new
`ColumnDeletedEvent`.

**When to use:** This is the one new mutation this phase introduces that needs a brand-new event
type — every other item (GAP-01, GAP-03, GAP-05, GAP-06) either reuses an existing event
(`BoardCreatedEvent` already fires from `UserService.addBoardByUserId`) or needs no activity-log
entry at all (theme, ordering, subtask-locking are not in the `ActivityAction` enum's existing
scope and CONTEXT.md does not ask for one).

**Example — the exact shape to add, modeled on `TaskDeletedEvent` (`event/TaskDeletedEvent.java`):**
```java
// event/ColumnDeletedEvent.java
public record ColumnDeletedEvent(
        UUID eventId, String userId, String boardId, String columnId, Instant timestamp)
        implements ActivityEvent {}
```
Then: add `ColumnDeletedEvent` to `ActivityEvent`'s `permits` clause
(`event/ActivityEvent.java:16-20`, currently `TaskCreatedEvent, TaskMovedEvent, TaskDeletedEvent,
BoardCreatedEvent, ColumnCreatedEvent`), add a switch arm to **both** `toAvro` and `toDomain` in
`event/avro/ActivityEventAvroMapper.java` (the class's own Javadoc, read this session, explains
why it is a hand-written `switch`, not a MapStruct `@Mapper` — do not "fix" that), add
`AvroColumnDeletedEvent.avsc` to `src/main/avro/` byte-for-byte mirroring
`AvroTaskDeletedEvent.avsc`'s field list (`eventId` as `{"type":"string","logicalType":"uuid"}`,
`userId`/`boardId`/`columnId` as plain `"string"`, `timestamp` as
`{"type":"long","logicalType":"timestamp-millis"}` — verified verbatim from
`src/main/avro/AvroTaskDeletedEvent.avsc:1-13`), and add
`AvroColumnDeletedEvent.getClassSchema()` to `AvroSchemaRegistrar.SCHEMAS`
(`config/AvroSchemaRegistrar.java:51-57`) so `./gradlew registerSchemas` and
`AbstractKafkaContainerTest`'s static initializer both pick it up automatically — this list is the
**one and only place** a new event type must be added for registration; nothing else needs to
change in `AvroSchemaRegistrar`, since `RecordNameStrategy` (confirmed in
`application.properties:79,107`) derives the Confluent subject name from
`schema.getFullName()`, not a hardcoded string. Add a `ActivityAction.COLUMN_DELETED` enum value
and a `case ColumnDeletedEvent e -> ...` switch arm to
`ActivityLogConsumer.deriveActionAndDetailIds` (`activitylog/ActivityLogConsumer.java:78-107`),
mirroring the existing `TaskDeletedEvent` arm's `columnId`/`taskId` detail-map shape (drop
`taskId`, keep `columnId`).

### Pattern 3: MapStruct mapper composition for a nested read DTO (GAP-04)

**What:** MapStruct's `@Mapper(uses = {...})` attribute lets one mapper interface delegate nested
element mapping to other, already-existing mapper interfaces — it does **not** require a
hand-written aggregation service. `[CITED: mapstruct.org/documentation/stable/reference/html/]`
When mapper A declares `uses = ColumnMapper.class` and needs to map a `List<ColumnEntity>` field
to a `List<ColumnFullResponseDTO>` field, MapStruct generates a loop that converts each element,
invoking a method it finds on the used mapper (or generates a delegating call if the used mapper's
existing method almost matches). This composes cleanly through all four levels
(Board→Column→Task→Subtask) as three separate `uses` mappers, since each level is a simple
one-hop `List<X> → List<Y>` conversion, not a fan-out MapStruct has to invent logic for.

**When to use:** Exactly GAP-04's `BoardFullResponseDTO`. A hand-written aggregation service
(fetch board, fetch columns, fetch tasks per column, fetch subtasks per task, manually assemble)
is the alternative and would work, but reintroduces the N+1 fan-out this endpoint exists to
eliminate unless it is paired with a single JOIN-FETCH query anyway — so the DTO-assembly question
and the query-shape question are separable, and MapStruct's `uses` composition is the
lower-effort choice for the DTO-assembly half.

**Nested DTO shape (new files, following `dto/{domain}_dto/` convention):**
```java
// dto/board_dto/BoardFullResponseDTO.java
public class BoardFullResponseDTO implements BaseId, BaseBoard {
    private String id;
    private String name;
    private List<ColumnFullResponseDTO> columns;
}
// dto/column_dto/ColumnFullResponseDTO.java — extends flat ColumnResponseDTO's shape + tasks
public class ColumnFullResponseDTO implements BaseId, BaseColumn {
    private String id;
    private String name;
    private Long version;
    private List<TaskFullResponseDTO> tasks;
}
// dto/task_dto/TaskFullResponseDTO.java — extends flat TaskResponseDTO's shape + subtasks
public class TaskFullResponseDTO implements BaseId, BaseTask {
    private String id;
    private String title;
    private String description;
    private Long version;
    private List<SubtaskResponseDTO> subtasks;   // SubtaskResponseDTO is already a leaf, reuse as-is
}
```
```java
// mapper/BoardFullMapper.java
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE,
    uses = {ColumnFullMapper.class})
public interface BoardFullMapper {
    BoardFullResponseDTO toBoardFullResponseDTO(BoardEntity entity);
}
// mapper/ColumnFullMapper.java
@Mapper(..., uses = {TaskFullMapper.class})
public interface ColumnFullMapper {
    ColumnFullResponseDTO toColumnFullResponseDTO(ColumnEntity entity);
}
// mapper/TaskFullMapper.java
@Mapper(..., uses = {SubtaskMapper.class})   // reuses the EXISTING SubtaskMapper — leaf level, no new type
public interface TaskFullMapper {
    TaskFullResponseDTO toTaskFullResponseDTO(TaskEntity entity);
}
```
Field name matching: `BoardEntity.column` (List<ColumnEntity>, note the singular field name —
verified `entity/BoardEntity.java:31`) must map to `BoardFullResponseDTO.columns` via an explicit
`@Mapping(source = "column", target = "columns")`, since MapStruct matches by name and the
entity's field is (confirmed, oddly) singular `column` while every other DTO in this domain uses
the plural. Same applies to `ColumnEntity.task` (`entity/ColumnEntity.java:32`) →
`ColumnFullResponseDTO.tasks`.

**Query-count implication (relevant to this project's `countQueries()` convention):** A naive
`boardFullMapper.toBoardFullResponseDTO(boardRepository.findById(id).get())` call, with no
eager-fetch query, costs **1 (board) + 1 (columns for board) + N (tasks, one per column) + M
(subtasks, one per task)** queries under the existing `@OneToMany(mappedBy = ...)` LAZY-by-default
associations (verified: neither `BoardEntity.column`, `ColumnEntity.task`, nor
`TaskEntity.subtasks` carries an explicit `fetch = FetchType.EAGER` or `@EntityGraph` anywhere —
grepped this session, zero matches for `EAGER`/`@Fetch`/`JOIN FETCH`/`@EntityGraph` in
`src/main/java`). A single JPQL query with a chained `LEFT JOIN FETCH` —
`SELECT DISTINCT b FROM BoardEntity b LEFT JOIN FETCH b.column c LEFT JOIN FETCH c.task t LEFT
JOIN FETCH t.subtasks s WHERE b.id = :boardId` — collapses this to exactly 1 `PreparedStatement`.
This is a **linear chain of three different `List` associations at three different nesting
depths**, not sibling collections hanging off one parent, so it does **not** trigger Hibernate's
`MultipleBagFetchException` (that restriction is specifically about two-or-more `List` collections
fetched from the *same* parent in one query — e.g. `board.column` + some other list also on
`BoardEntity` — which does not apply here). `DISTINCT` is required to de-duplicate the
row-multiplication a multi-level `JOIN FETCH` produces. `AbstractAppTest.countQueries(Runnable)`
(`src/test/java/.../AbstractAppTest.java:224-229`, already read this session) is the existing,
sanctioned way to assert this endpoint costs exactly 1 `PreparedStatement`, not N+1.

### Pattern 4: Position renumber-on-insert (GAP-03, D-02)

**What:** A plain `Integer position` column per (`TaskEntity`, scoped to its column;
`ColumnEntity`, scoped to its board), maintained by shifting every sibling at or after the
insertion/target point by +1 (insert) or by a signed shift (reorder), all inside the same
`@Transactional` method that performs the insert/move.

**Concurrency pitfall — CITED, not something this codebase's existing `@Version` field already
covers:** `[CITED: begriffs.com/posts/2018-03-20-user-defined-order.html]` The "shift neighbors"
approach has two known failure modes distinct from the single-row optimistic-locking problem
`@Version` already solves:
1. **Unique-constraint ordering sensitivity**, if a `UNIQUE(column_id, position)` constraint is
   added: shifting a contiguous range of rows one at a time can transiently collide (moving item 3
   to position 4 before item 4 has been moved to position 5 hits the constraint mid-transaction).
   The fix is either a `DEFERRABLE INITIALLY DEFERRED` unique constraint (checked at commit, not
   per-statement) or doing the shift as a single batched `UPDATE ... SET position = position + 1
   WHERE column_id = ? AND position >= ?` statement (no intermediate per-row state to collide on).
   **Recommend the single-batched-UPDATE form** — it sidesteps the ordering-sensitivity problem
   entirely and matches this codebase's existing preference for bulk JPQL over per-row loops (see
   `TaskService.deleteAllByColumn`'s batched-delete precedent).
2. **Cross-request race, not covered by `@Version`:** `TaskEntity.version`/`ColumnEntity.version`
   protect a *single row's* read-then-write cycle (the existing "client read at version N, someone
   else already wrote N+1" scenario `TaskService.updateById`'s Javadoc documents). They do **not**
   protect two *different* concurrent inserts into the same column both computing "next position =
   current max + 1" from a stale read — this is a genuine gap the planner must decide how to close
   (row-level lock via `SELECT ... FOR UPDATE` on the column's task set before computing the
   insertion point, or accept the race as low-probability given this project's single-user,
   session-scoped concurrency profile — no evidence in this codebase of concurrent-editor support
   beyond the existing 2-session-per-user ceiling). **Flag as an open question for the planner**,
   not resolved here — CONTEXT.md's Claude's Discretion section explicitly leaves "exact
   renumbering mechanics" open.
3. **Isolation level:** this codebase sets no explicit `@Transactional(isolation = ...)` anywhere
   (verified: grepped `src/main/java` for `Isolation`/`SERIALIZABLE`/`READ_COMMITTED`, zero
   matches) — everything runs at Postgres's default `READ COMMITTED`. The cited source notes
   `READ COMMITTED` does not by itself prevent the concurrent-insert race in point 2 above.

**Where position lives in the request shape (per D-04, locked):**
`MoveTaskRequestDTO` (`dto/task_dto/MoveTaskRequestDTO.java`, currently `targetColumnId` +
`version` only, read verbatim this session) gains one field:
```java
@Getter @Setter @Builder @EqualsAndHashCode @JsonInclude(JsonInclude.Include.NON_NULL)
public class MoveTaskRequestDTO {
    @NotBlank private String targetColumnId;
    @NotNull private Long version;
    private Integer targetPosition;   // NEW — nullable: omitted means "append at end"
}
```
Column reorder needs its own new endpoint/DTO (no existing DTO to extend — `UpdateColumnRequestDTO`
carries only `name`/`version`); CONTEXT.md does not pre-decide this shape, so a new
`ReorderColumnRequestDTO { @NotNull Long version; @NotNull Integer targetPosition; }` on a new
`PATCH /boards/{boardId}/columns/{columnId}/reorder` route (mirroring `TaskMoveController`'s
flat-route-outside-the-nested-class-mapping precedent, since `ColumnController`'s class-level
mapping is already board-nested and Spring's additive path composition applies the same way it
does to `TaskMoveController`'s Javadoc explanation) is the natural analog — planner's call per
CONTEXT.md's Claude's Discretion.

### Anti-Patterns to Avoid

- **Fractional/LexoRank position keys:** explicitly rejected by the user (D-02) — do not
  reintroduce as a "cleaner" alternative during planning.
- **A generic/pluggable Avro envelope covering all event types:** already rejected in this
  project's own `REQUIREMENTS.md` Out of Scope table for the same reason D-03 gives here (loses
  compile-time exhaustiveness) — `ColumnDeletedEvent` must get its own `.avsc`, not reuse or
  generalize an existing one.
- **Direct `repository.findById()` in a new service method** (CODE_STYLE.md rule 2) — every new
  method in `ColumnService`/`TaskService`/`SubtaskService`/`UserService` this phase adds must go
  through `findById(userId, id)` → `OwnershipVerifierService`, exactly like every existing method;
  `LayeringArchTest` mechanically fails the build otherwise.
- **`orElseThrow` for `Optional` unwrapping** (CODE_STYLE.md rule 7) — use the `isEmpty()`-then-throw
  guard form in any new code (e.g. a new uniqueness-check query in `BoardService`).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Nested board→column→task→subtask DTO assembly (GAP-04) | A manual `BoardFullResponseDTO` builder that loops and calls each flat mapper by hand | MapStruct `@Mapper(uses = {...})` composition (Pattern 3 above) | MapStruct already generates this exact loop-and-delegate shape for every other List field in this codebase; hand-rolling it here breaks the "MapStruct owns Entity↔DTO" convention documented in CLAUDE.md's Key Abstractions |
| Snowflake-style time-ordered ID (GAP-07) | A new ID-generation class, or a third-party Snowflake library | The existing `RandFlakeGenerator.generateRandflake()` (`config/RandFlakeGenerator.java:32-41`) — already exactly this: timestamp bits + random bits, Base36-encoded, currently wired only to `BaseEntity.id` via `@RandFlakeId` | Duplicating this logic in a second class, or pulling in an external library, is strictly worse than exposing the existing one as a callable dependency — see Pitfall 4 for the wiring nuance (it is currently a Hibernate `IdentifierGenerator`, not a plain injectable service) |
| Board-name-uniqueness check (GAP-01, D-09) | A custom `@Unique` Bean Validation annotation with a DB-querying validator | A plain repository method (`boardRepository.existsByUserIdAndName(userId, name)`) called from the service layer, mirroring the pattern of `OwnershipVerifierService`'s existing plain-Java guard clauses | This codebase has zero DB-querying custom validators anywhere (`dto/annotation/` only holds pure-syntax validators — `@BoardName`, `@TaskTitle`, etc., none of which touch a repository); a query-backed `ConstraintValidator` would be the first of its kind and would need its own DI wiring, whereas a service-layer check is one `if` statement using an existing convention |
| Position renumbering shift logic | A hand-rolled loop issuing one `UPDATE` per sibling row | A single bulk `@Modifying @Query` JPQL/SQL statement (`UPDATE TaskEntity t SET t.position = t.position + 1 WHERE t.column.id = :columnId AND t.position >= :fromPosition`) | Matches this codebase's own precedent (`SubtaskRepository.deleteAllByTaskIdIn`'s Javadoc explicitly prefers one bulk statement over N per-row calls, for the same reason: statement count and race-window size both shrink) |

**Key insight:** every "don't hand-roll" item above has a same-shape precedent already merged into
this codebase. The discipline this phase needs is finding and copying that precedent exactly, not
inventing a new one — which is also why this phase carries unusually low architectural risk
despite touching 7 independent features.

## Common Pitfalls

### Pitfall 1: New entity fields without a matching Flyway migration break test/prod boot, not just a runtime query

**What goes wrong:** Adding `TaskEntity.position`, `ColumnEntity.position`, `SubtaskEntity.version`,
or `UserEntity.theme` without a corresponding Flyway `V5` column addition fails **every** Spring
context startup in the test suite (and in the real deploy profile), not merely the one test that
exercises the new field.
**Why it happens:** `spring.jpa.hibernate.ddl-auto=validate` is the default in both
`application.properties:30` and `application-test.properties:23` (both verified by direct read
this session) — Hibernate compares the entity model against the live schema at boot and refuses to
start on any mismatch. Only `docker-compose.yml`'s local-dev override sets `ddl-auto=update`,
which silently papers over a missing migration during manual local testing but does **not** apply
in CI or in `./gradlew test`.
**How to avoid:** Write `V5__<name>.sql` in the same task/commit as the entity field change, using
the exact `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT ...` shape `V2__add_optimistic_locking_version_columns.sql`
already established (`ALTER TABLE tasks ADD COLUMN version bigint NOT NULL DEFAULT 0;`, verified
verbatim). `FlywaySchemaProvenanceTest.FlywayHistory.shouldRecordFourSuccessfulMigrations_whenContextStarts`
(`src/test/java/.../FlywaySchemaProvenanceTest.java:38-49`) currently hardcodes `IN ('1','2','3','4')`
and `isEqualTo(4)` — this phase's V5 migration means this specific test method needs updating to
`5`/`('1','2','3','4','5')` or it will start failing (a real, mechanical consequence, not a
hypothetical).
**Warning signs:** `ApplicationContext` fails to start in any new test class with a Hibernate
`SchemaManagementException` mentioning the new column name.

### Pitfall 2: `ColumnDeletedEvent`'s Avro `eventId` type must stay `{"type":"string","logicalType":"uuid"}` if GAP-07 lands in the same phase but a different task

**What goes wrong:** If GAP-02's `ColumnDeletedEvent` schema is authored assuming the *old*
`UUID`-typed `eventId`, but GAP-07 (Snowflake-style `eventId`, likely a plain `String`) lands
first or in the same PR, the two tasks can produce an inconsistent schema — five old-style events
plus one new-style event, or worse, a half-migrated `ActivityEvent` interface that doesn't compile.
**Why it happens:** GAP-07 changes `ActivityEvent.eventId()`'s return type from `UUID` to `String`
(verified: `event/ActivityEvent.java:21` currently declares `UUID eventId();`) — a change to the
**shared sealed interface** every one of the (soon to be 6) event records implements. Any new
record added in the same phase before that interface changes will compile against the old `UUID`
signature; if it's added after, it must be authored against `String` from the start.
**How to avoid:** Sequence GAP-07 (interface + all 6 records + entity + DTO + repository + all 6
`.avsc` files) as either fully before or fully after GAP-02 within the plan's wave ordering — not
interleaved. Given GAP-07 is explicitly "independent" and "folded in... despite a topical
mismatch" per CONTEXT.md, doing GAP-07 in its own wave (touching all *existing* events) and GAP-02
in a separate wave (adding the *new* event, against whichever `eventId` type GAP-07 leaves behind)
is the safer ordering — see the Folded Todo section of CONTEXT.md for the "flag to the user"
caveat this carries regardless of ordering.
**Warning signs:** A compile error in a record implementing `ActivityEvent` with a type mismatch
on `eventId`; or, if compiling cleanly but semantically wrong, `AvroSchemaRegistrar` registering
5 schemas with `eventId: uuid-logical-type` and 1 with plain `string` (or vice versa) — an
internally inconsistent event package.

### Pitfall 3: `ActivityLogResponseDTO.eventId` is a public API contract, not just an internal type

**What goes wrong:** GAP-07 changing `eventId` from `UUID` to `String` also changes
`ActivityLogResponseDTO.eventId`'s JSON type in the `GET /boards/{boardId}/activity` response body
(`dto/activity_dto/ActivityLogResponseDTO.java:24`, verified) — this is a client-visible breaking
change to an already-shipped, documented endpoint (SCHEMA-01..06 shipped in Phase 4), not an
internal refactor.
**Why it happens:** `ActivityLogEntity.eventId` (`UUID`, `entity/ActivityLogEntity.java:60`) is
mapped straight through to the response DTO with no translation layer — verified no
`ActivityLogMapper` conversion logic exists beyond direct field mapping.
**How to avoid:** Treat this as a deliberate, documented breaking change (there is no frontend
consumer yet per this project's own README/architecture docs, so the blast radius is currently
zero) — call it out explicitly in the plan rather than letting it happen as a side effect of the
`UUID`→`String` type change elsewhere.
**Warning signs:** none at compile time if `eventId`'s type is changed consistently everywhere —
this is a semantic/contract risk, not a build-breaking one, which is exactly why it's easy to miss.

### Pitfall 4: `RandFlakeGenerator.generateRandflake()` is currently an instance method on a Hibernate `IdentifierGenerator`, not a general-purpose injectable service

**What goes wrong:** The three event-publishing services (`BoardService`, `ColumnService`,
`TaskService` — all three verified via grep to call `UUID.randomUUID()` directly, at
`service/BoardService.java:101`, `service/ColumnService.java:84`, and three call sites in
`service/TaskService.java:61,171,202`) cannot simply call
`new RandFlakeGenerator().generateRandflake()` cleanly as a `@Service`-style dependency —
`RandFlakeGenerator` implements Hibernate's `IdentifierGenerator` interface and is wired via the
`@RandFlakeId` annotation mechanism (`config/RandFlakeId.java`), not Spring's `@Autowired` DI.
**Why it happens:** `generateRandflake()` is a public instance method with no injected/shared
state (its own Javadoc, read this session, confirms "this generator holds no shared mutable
state"), so it is safe to call from anywhere via a `new RandFlakeGenerator()` — but doing that at 5
different call sites duplicates the instantiation rather than sharing one bean.
**How to avoid:** Extract a small static helper (or a trivial `@Component` wrapper) exposing
`generateRandflake()` for non-entity-id callers, rather than either (a) instantiating
`RandFlakeGenerator` inline at each of the 5 call sites, or (b) writing a second, parallel ID
generator that duplicates the same 15 lines of logic. This is a small design decision the planner
should make explicitly, not leave implicit.
**Warning signs:** a second `Instant.now()`-plus-`ThreadLocalRandom` implementation appearing
anywhere outside `config/RandFlakeGenerator.java` is the signal this wasn't reused.

### Pitfall 5: `ColumnEntity`/`TaskEntity`'s `@OneToMany` field names are singular (`column`, `task`), not plural

**What goes wrong:** Writing `board.getColumns()` or `column.getTasks()` in new GAP-04 code
(instead of `board.getColumn()`/`column.getTask()`) is a compile error, and it's an easy mistake
because every other codebase convention in this project uses plural collection names.
**Why it happens:** `BoardEntity.column` (`entity/BoardEntity.java:31`, `List<ColumnEntity>
column`) and `ColumnEntity.task` (`entity/ColumnEntity.java:32`, `List<TaskEntity> task`) are both
verified, existing, singular field names on `List`-typed fields — an established (if awkward)
inconsistency already in the codebase, not something this phase should "fix" as a drive-by
(renaming a JPA field with an existing `mappedBy` reference and getter/setter surface is out of
this phase's scope and risks an unrelated regression).
**How to avoid:** When writing the new `BoardFullMapper`/`ColumnFullMapper`, use
`@Mapping(source = "column", target = "columns")` and `@Mapping(source = "task", target =
"tasks")` explicitly rather than relying on MapStruct's name-matching to find the field — verified
in Pattern 3 above.
**Warning signs:** MapStruct compile-time warning about an unmapped target property (`columns`/
`tasks`) on the new DTOs, since the source field name doesn't match by default.

## Code Examples

### Board-name uniqueness check (GAP-01, D-09) — new repository method + service guard

```java
// repository/BoardRepository.java — add one derived-query method
public interface BoardRepository extends JpaRepository<BoardEntity, String> {
    List<BoardEntity> findAllByUserId(String userId);
    boolean existsByUserIdAndName(String userId, String name);   // NEW
}
```
```java
// service/BoardService.java or UserService.addBoardByUserId — guard clause, CODE_STYLE.md rule 7 shape
if (boardRepository.existsByUserIdAndName(user.getId(), dto.getName())) {
    throw new AppDuplicateBoardNameException(dto.getName());   // NEW exception type, see below
}
```

### GlobalExceptionHandler has no existing "duplicate resource" pattern — a new exception type is needed

`GlobalExceptionHandler` (read verbatim this session, `handler/GlobalExceptionHandler.java`)
already maps `OptimisticLockingFailureException` → `409` and `AppEntityNotFoundException`/
`AppAccessDeniedException` → `404`/`401` respectively, but has **no existing handler for a
"resource already exists" case** — grepped, zero matches for `Duplicate`/`Conflict`/`AlreadyExists`
anywhere in `src/main`. Two options, both consistent with the existing `App*Exception` naming
convention (`App{ExceptionType}`, extends a JPA/Spring exception per CLAUDE.md's documented
convention):
```java
// Option A (recommended): reuse the existing 409 mapping by extending OptimisticLockingFailureException-
// adjacent semantics is a stretch (that exception means "version conflict," not "name taken") — so:
// Option B (cleaner): a new AppDuplicateResourceException extending a base that maps to 409,
// with its own @ExceptionHandler arm in GlobalExceptionHandler, following the file's existing
// one-arm-per-exception-type pattern exactly.
@ExceptionHandler(AppDuplicateResourceException.class)
public ResponseEntity<String> handleAppDuplicateResource(AppDuplicateResourceException ex) {
    return new ResponseEntity<>(ex.getMessage(), HttpStatus.CONFLICT);
}
```
CONTEXT.md explicitly leaves this as Claude's Discretion ("align with `GlobalExceptionHandler`'s
existing conflict conventions... or use a `400` field-validation error — planner's call"); this
research's recommendation is **409** (Option B, a new exception type), since `400` in this
codebase is reserved for `IllegalArgumentException`-style request-shape problems (e.g.
`TaskService.moveToColumn`'s cross-board guard), and a duplicate name is a state conflict, not a
malformed request — matching the semantic distinction `GlobalExceptionHandler` already draws
between its `400` (`IllegalArgumentException`) and `409` (`OptimisticLockingFailureException`)
handlers.

### `UserController` — no existing home, new controller recommended

`AuthenticationController` (read verbatim this session) carries **no** class-level
`@PreAuthorize("isAuthenticated()")` and **no** `@CurrentUserId` usage anywhere — its two routes
(`signin`, `signup`) are deliberately the *only* unauthenticated-entry routes in the application
(per its own comment: "only these authentication routes yield session cookie"). This is decisive
evidence against adding the theme endpoint there. `BoardController` is the closest structural
template (`@RestController @RequestMapping(ApiPaths.BOARDS) @Validated
@PreAuthorize("isAuthenticated()")`, `@CurrentUserId String userId` on every method) — a new
`UserController` at a new `ApiPaths.USERS = "/users"` (no such constant exists yet, verified
against the full `ApiPaths.java` read this session) mirroring that exact shape is the
convention-consistent recommendation:
```java
@RestController
@RequestMapping(ApiPaths.USERS)
@PreAuthorize("isAuthenticated()")
public class UserController {
    @Autowired private UserService userService;

    @GetMapping(ApiPaths.ME + ApiPaths.THEME)
    public ResponseEntity<UserResponseDTO> getTheme(@CurrentUserId String userId) { ... }

    @PutMapping(ApiPaths.ME + ApiPaths.THEME)
    public ResponseEntity<UserResponseDTO> updateTheme(
            @CurrentUserId String userId, @Valid @RequestBody UpdateThemeRequestDTO dto) { ... }
}
```
Exact route shape (`/users/me/theme` vs. `/users/{userId}/theme` vs. folding onto the response of
an existing endpoint) is not pre-decided by CONTEXT.md — planner's call, but `/users/me/theme` is
recommended since `userId` already comes from `@CurrentUserId` (the session), never from a path
variable, everywhere else in this codebase's authenticated routes.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| `activity_log.event_id uuid` | `activity_log.event_id varchar` (GAP-07, this phase) | This phase | Requires a new Flyway migration dropping and re-adding `uk_activity_log_event_id` on the new column type, plus a corresponding change to `FlywaySchemaProvenanceTest`'s constraint-existence assertions if they reference the column type (they currently only check constraint *names*, not types — verified, so likely unaffected, but worth a planner double-check) |
| No `DELETE` route for a column | `DELETE /boards/{boardId}/columns/{columnId}` (GAP-02) | This phase | First column-level deletion path independent of full board deletion |
| Four round-trips to render one board | `GET /boards/{boardId}/full` (GAP-04) | This phase, un-deferring `.planning/STATE.md`'s 2026-07-31 "deferred to v2" note | First deliberate exception to this codebase's flat-DTO convention — precedent-setting for any future nested-read endpoint |

**Deprecated/outdated:** Nothing in this phase deprecates existing behavior — GAP-04 adds a new
endpoint alongside the existing four-round-trip endpoints (`GET /boards`, `GET .../columns`,
`GET .../tasks`, `GET .../subtasks`), it does not replace them; MOCKUP_FEATURE_GAP.md §1.4
confirms those flat endpoints remain useful independently.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `/users/me/theme` (session-derived `userId`, not a path variable) is the right route shape for GAP-05 | Code Examples — UserController | Low — a route-naming choice with no schema/contract cost if changed later; CONTEXT.md explicitly leaves this to the planner |
| A2 | A new `AppDuplicateResourceException` → `409` is the right shape for GAP-01's uniqueness conflict, rather than `400` | Code Examples — GlobalExceptionHandler | Low-medium — CONTEXT.md explicitly defers this to the planner; changing it later is a response-status change a client would need to handle differently, but no schema impact |
| A3 | The safest sequencing for GAP-07 vs. GAP-02 is "GAP-07 in its own wave, fully before or after GAP-02" | Pitfall 2 | Medium — if the planner instead interleaves these two within one wave/plan, the risk is a compile error or an internally-inconsistent Avro schema set (5 old-type + 1 new-type `eventId`), not a silent bug — likely to be caught at compile time or by `AvroSchemaRegistrar`'s own registration step, but worth flagging explicitly in the plan rather than discovering it mid-task |
| A4 | Extracting a static/shared helper from `RandFlakeGenerator.generateRandflake()` (rather than instantiating `new RandFlakeGenerator()` at 5 call sites, or writing a second generator) is the right call for GAP-07 | Pitfall 4, Don't Hand-Roll | Low — purely a code-organization choice; either approach produces a correct Snowflake-style ID, this is about avoiding duplicated logic, not correctness |
| A5 | A single batched `UPDATE ... SET position = position + 1 WHERE ...` (not `DEFERRABLE` unique constraint, not per-row loop) is the right renumbering mechanism, and the cross-request race in Pitfall 1's point 2 is acceptable to leave unresolved-but-flagged rather than requiring a `SELECT ... FOR UPDATE` | Architecture Patterns — Pattern 4 | Medium — this is a genuine unresolved design question CONTEXT.md leaves to the planner ("exact renumbering mechanics... planner's call"); if wrong, the failure mode is a rare, hard-to-reproduce position collision under concurrent same-column inserts, not a data-loss or security issue, given this project's low realistic concurrency (2-session-per-user ceiling, no multi-user collaboration on one board evidenced anywhere in the domain model) |

## Open Questions

1. **Exact `UNIQUE(column_id, position)` DB constraint: add it, or leave `position` unconstrained?**
   - What we know: the "shift neighbors" pitfall research (CITED above) assumes such a constraint
     exists and describes how to avoid it colliding mid-shift; this codebase's existing `V1`/`V2`
     migrations show a strong precedent for explicit named constraints on invariants worth
     protecting (`fk_tasks_column`, `uk_activity_log_event_id`).
   - What's unclear: whether database-level enforcement of "no two tasks in the same column share
     a position" is worth the extra migration complexity for a purely presentational ordering
     field, versus trusting the batched-UPDATE renumbering logic alone.
   - Recommendation: add it — matches this codebase's general preference for DB-enforced
     invariants over application-only enforcement (every FK, every existing UNIQUE constraint is
     DB-level) — but confirm at planning time rather than assuming.

2. **Does `ActivityAction` (the enum backing `ActivityLogEntity.action`) need a `COLUMN_DELETED`
   value, and is that enum's full member list something this research should have read directly?**
   - What we know: `ActivityLogConsumer.deriveActionAndDetailIds` (read verbatim this session)
     references `ActivityAction.TASK_CREATED`, `.TASK_MOVED`, `.TASK_DELETED`, `.BOARD_CREATED`,
     `.COLUMN_CREATED` — a 1:1 mapping with the 5 current `ActivityEvent` records — strongly
     implying a `COLUMN_DELETED` value must be added alongside GAP-02's new event.
   - What's unclear: this research did not open `entity/ActivityAction.java` directly this
     session, so the exact existing member list/declaration is not independently verified here —
     only inferred from its 5 usage sites in `ActivityLogConsumer`.
   - Recommendation: planner/executor must read `entity/ActivityAction.java` directly before
     writing the new enum value — do not assume this research's inference is a substitute for
     that read.

## Environment Availability

Skipped — this phase introduces no new external tool/service/runtime dependency. Every
infrastructure piece it touches (PostgreSQL via Testcontainers, Redpanda/Schema Registry via the
existing Phase 4 pipeline) is already a proven, working dependency of this codebase's test suite.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | No | Unchanged — all 6 new/modified routes sit behind the existing `@PreAuthorize("isAuthenticated()")` + session-cookie mechanism; no new auth surface |
| V3 Session Management | No | Unchanged |
| V4 Access Control | Yes | Every new/modified service method must route through `OwnershipVerifierService.verifyOwnershipOf{Board,Column,Task,Subtask}` per CODE_STYLE.md rule 2 and `LayeringArchTest`'s mechanical enforcement — this is the one access-control requirement every item in this phase must satisfy, with zero exceptions (GAP-01's board creation is the identity-root case, already correctly using `UserService.findById` rather than an ownership chain, matching `UserService`'s documented exemption) |
| V5 Input Validation | Yes | Jakarta Validation (`@NotBlank`, `@NotNull`, `@Size`, custom `@BoardName`/`@TaskTitle`/etc. annotations) — every new request DTO (`ReorderColumnRequestDTO`, `UpdateThemeRequestDTO`, the extended `MoveTaskRequestDTO`) must follow the existing `Save*RequestDTO`/`Update*RequestDTO` validation-annotation convention, not introduce ad hoc unvalidated fields |
| V6 Cryptography | No | Not touched by this phase |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| IDOR (Insecure Direct Object Reference) — e.g. deleting a column that belongs to another user's board by guessing/enumerating its ULID | Elevation of Privilege | `OwnershipVerifierService.verifyOwnershipOfColumn` (already the pattern for every existing mutating route) — GAP-02's new `DELETE` route must call this before any delete logic runs, exactly like every sibling delete route |
| Mass-assignment / over-posting on a new DTO — e.g. a `targetPosition` field on `MoveTaskRequestDTO` accepted without bounds checking, allowing a negative or absurdly large position to corrupt renumbering | Tampering | Bean Validation (`@Min(0)` or equivalent) on the new `targetPosition`/`position`-bearing fields; the renumbering service logic itself should also defensively clamp/validate against the actual sibling count rather than trusting the client-supplied integer blindly |
| Board-name-uniqueness check bypassing ownership scoping (checking global uniqueness instead of per-user) | Information Disclosure (leaking whether another user has a board with the same name) | The uniqueness check must be scoped `WHERE user_id = ? AND name = ?` (per D-09's own "board-name uniqueness validation per user" wording, and the `existsByUserIdAndName` signature recommended above) — never a global-uniqueness check across all users' boards |

## Sources

### Primary (HIGH confidence — read directly from this repository this session)
- `service/{User,Board,Column,Task,Subtask}Service.java` — every existing mutation/locking/cascade
  pattern cited above
- `entity/{Task,Column,Subtask,User,Board,ActivityLog}Entity.java` — exact field declarations,
  quoted verbatim
- `controller/{Board,Column,Subtask,TaskMove}Controller.java`, `security/AuthenticationController.java`
- `dto/**/*.java`, `mapper/*.java` — exact existing DTO/mapper shapes
- `event/*.java`, `event/avro/ActivityEventAvroMapper.java`, `src/main/avro/*.avsc`
- `config/AvroSchemaRegistrar.java`, `config/RandFlakeGenerator.java`, `config/RandFlakeId.java`
- `src/main/resources/{application.properties,application-test.properties}`
- `src/main/resources/db/migration/V1__init.sql`, `V2__add_optimistic_locking_version_columns.sql`,
  `V3__add_activity_log.sql`
- `src/test/java/.../FlywaySchemaProvenanceTest.java`, `AbstractAppTest.java`
- `docs/CODE_STYLE.md` (all 9 rules)
- `docs/MOCKUP_FEATURE_GAP.md` (full read)
- `.planning/phases/04-schema-registry/04-CONTEXT.md`, `.planning/todos/pending/2026-08-02-use-snowflake-id-generator-for-activity-log-events.md`
- `build.gradle` (avro plugin, `registerSchemas`/`rehearseHistoricalSchemas` tasks)

### Secondary (MEDIUM confidence — WebSearch/WebFetch verified against an identifiable, authoritative-style source)
- [MapStruct 1.6.3 Reference Guide](https://mapstruct.org/documentation/stable/reference/html/) —
  `uses` attribute composition mechanism
- [begriffs.com — User-defined Order in SQL](https://begriffs.com/posts/2018-03-20-user-defined-order.html) —
  integer-position shift-approach pitfalls (unique-constraint deferral, race conditions)

### Tertiary (LOW confidence — flagged for planner validation)
- None additional beyond what's captured in the Assumptions Log above.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — zero new dependencies, every tool already proven in this codebase
- Architecture: HIGH for GAP-01/02/05/06/07 (direct pattern reuse, verified against real code);
  MEDIUM for GAP-03/GAP-04 (genuinely new patterns for this codebase, grounded in CITED external
  sources rather than an in-repo precedent)
- Pitfalls: HIGH — every pitfall above is either a verified fact about this codebase (Pitfalls 1,
  3, 4, 5) or a CITED external-source concern applied to a verified absence of mitigation in this
  codebase (Pitfall 2, and Pattern 4's concurrency discussion)

**Research date:** 2026-08-08
**Valid until:** 30 days (stable Spring Boot/JPA/MapStruct/Avro stack, no fast-moving dependency
in scope)
