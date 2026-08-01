# Architecture Research

**Domain:** Spring Boot 3.5.0 / JPA-Hibernate REST backend — Epic 2 completion (nested aggregate endpoint + optimistic locking)
**Researched:** 2026-07-31
**Confidence:** HIGH (grounded directly in this codebase's existing entities, DTOs, mappers, repositories, services, `application.properties`/`application-test.properties`, and the epic spec — no external framework research needed; this is an integration-pattern question, not an ecosystem survey)

## Standard Architecture

### System Overview

The existing layered architecture is unchanged by this work — both new deliverables are **additive slices** through the same five layers, not a new layer:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Controller   BoardController.getFullBoard()  [NEW endpoint method]         │
│               GET /boards/{boardId}/full                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│  Service      BoardService.getFullBoard()  [NEW orchestration method]       │
│               → OwnershipVerifierService (existing, unchanged)              │
│               → ColumnRepository / TaskRepository / SubtaskRepository       │
│                 (NEW batch-fetch query methods)                             │
├─────────────────────────────────────────────────────────────────────────────┤
│  Mapper       NEW: BoardFullMapper / BoardFullResponseDTO tree              │
│               (separate MapStruct interface, does NOT touch existing        │
│               BoardMapper/ColumnMapper/TaskMapper/SubtaskMapper)             │
├─────────────────────────────────────────────────────────────────────────────┤
│  Repository   ColumnRepository.findByIdWithColumns() [NEW]                  │
│               TaskRepository.findAllByColumnIdIn() [NEW]                    │
│               SubtaskRepository.findAllByTaskIdIn() [NEW]                   │
│               (existing findAllByColumnId/findAllByBoardId untouched)       │
├─────────────────────────────────────────────────────────────────────────────┤
│  Entity       TaskEntity.version, ColumnEntity.version  [NEW @Version field]│
│               (BaseEntity itself NOT touched — see rationale below)         │
└─────────────────────────────────────────────────────────────────────────────┘
```

Both deliverables touch different, non-overlapping seams of the same stack (new mapper/DTOs/repo-methods for `/full`; a new entity field + exception mapping for optimistic locking), which is why they can be built as two independent phases (see Build Order below).

### Component Responsibilities

| Component | Responsibility | Typical Implementation |
|-----------|----------------|------------------------|
| `BoardController` (existing, extended) | New `GET /boards/{boardId}/full` route | One additional `@GetMapping` method, same class, same `@PreAuthorize`/`@CurrentUserId` pattern already used for every other endpoint |
| `BoardService` (existing, extended) | Orchestrates ownership check + calls new repository batch-fetch methods + delegates to new full-tree mapper | One additional `@Transactional` method; does not change any existing method signature |
| `BoardFullMapper` (new, separate MapStruct interface) | Entity tree → nested DTO tree, isolated from flat DTO mappers | New file in `mapper/`, `componentModel = SPRING`, references existing `SubtaskMapper` for the leaf conversion via MapStruct's `uses = {...}` composition |
| `BoardFullResponseDTO` / `ColumnFullResponseDTO` / `TaskFullResponseDTO` (new DTOs) | The one deliberately-nested response contract | New files in a `dto/board_dto/full/` subpackage (see Q1 below), built from data that is **guaranteed already loaded** before mapping — same anti-`LazyInitializationException` discipline as the flat DTOs, just enforced by construction (batch-fetch) instead of by omission (flat shape) |
| `ColumnRepository` / `TaskRepository` / `SubtaskRepository` (existing, extended) | New batch-fetch query methods (`findByIdWithColumns`, `findAllByColumnIdIn`, `findAllByTaskIdIn`) | One `JOIN FETCH` for the first level (Board+Columns); plain `findAllBy...In` for the two deeper levels (see Data Flow) |
| `TaskEntity` / `ColumnEntity` (existing, extended) | New `@Version` field for optimistic locking | Field added directly on the two entities, not on `BaseEntity` (see Q2) |
| `GlobalExceptionHandler` (existing, extended) | Map `ObjectOptimisticLockingFailureException` → 409 | New/adjusted `@ExceptionHandler`, same `@ControllerAdvice` class |

## Recommended Project Structure

No new top-level packages are required. Two small additions to the existing structure:

```
src/main/java/com/vrudenko/kanban_board/
├── dto/
│   ├── board_dto/
│   │   ├── BoardResponseDTO.java        # unchanged — flat, still used by GET /boards
│   │   └── full/                        # NEW subpackage, isolates the nested shape
│   │       ├── BoardFullResponseDTO.java
│   │       ├── ColumnFullResponseDTO.java
│   │       └── TaskFullResponseDTO.java
│   │           # subtask leaf reuses the existing dto/subtask_dto/SubtaskResponseDTO.java as-is
├── mapper/
│   ├── BoardMapper.java                 # unchanged
│   ├── ColumnMapper.java                # unchanged
│   ├── TaskMapper.java                  # unchanged
│   ├── SubtaskMapper.java               # unchanged
│   └── BoardFullMapper.java             # NEW — composes SubtaskMapper via `uses = {...}`
├── repository/
│   ├── ColumnRepository.java            # + findByIdWithColumns (NEW method)
│   ├── TaskRepository.java              # + findAllByColumnIdIn (NEW method)
│   └── SubtaskRepository.java           # + findAllByTaskIdIn (NEW method)
├── entity/
│   ├── TaskEntity.java                  # + @Version private Long version;
│   └── ColumnEntity.java                # + @Version private Long version;
```

### Structure Rationale

- **`dto/board_dto/full/` subpackage:** Keeps the nested DTOs physically separate from the flat ones. A developer opening `board_dto/` sees the flat convention is still the default; the `full/` subpackage is an explicit, scoped exception, not a silent parallel convention. This mirrors how the codebase already scopes things by domain (`board_dto`, `column_dto`, `task_dto`, `subtask_dto`).
- **New `BoardFullMapper`, not modifying existing mappers:** MapStruct interfaces are cheap to add and each existing mapper (`BoardMapper`, `ColumnMapper`, `TaskMapper`, `SubtaskMapper`) already has a single, narrow responsibility (one entity's flat DTO conversions). Adding nested-mapping methods to them would blur that responsibility and risk MapStruct generating an unwanted overload that the flat endpoints could accidentally pick up. A dedicated mapper for the aggregate is the safer boundary.
- **`@Version` directly on `TaskEntity`/`ColumnEntity`, not on `BaseEntity`:** see Q2 discussion below — this is the one place structure diverges from "just extend the base class."

## Architectural Patterns

### Pattern 1: Scoped nested DTO tree, reusing existing flat DTOs as leaves where possible

**What:** The new `/full` endpoint needs `Board → Columns → Tasks → Subtasks`. Rather than inventing four brand-new DTOs top-to-bottom, only the levels that need a `List<Child>` field are new (`BoardFullResponseDTO`, `ColumnFullResponseDTO`, `TaskFullResponseDTO`). The leaf level (`Subtask`) has no children to nest — reuse the existing `SubtaskResponseDTO` unchanged.

**When to use:** Whenever a genuinely nested read-model is needed alongside an existing flat convention. The key discipline: the flat convention exists to avoid `LazyInitializationException`, not because nesting is inherently wrong. Nesting is safe as long as every field on every level was fetched inside the same transaction before mapping — which the batch-fetch strategy in Q3 guarantees.

**Trade-offs:** Adds 3 new DTO classes and 1 new mapper, but touches zero existing DTOs/mappers. The alternative (giving `TaskResponseDTO` an optional nested `ColumnResponseDTO`) was rejected because it would change the meaning of the existing flat DTO for every other endpoint that returns it, inviting exactly the lazy-init risk the flat convention was designed to prevent.

**Example:**
```java
// dto/board_dto/full/BoardFullResponseDTO.java
@Getter @Setter @Builder
public class BoardFullResponseDTO {
    private String id;
    private String name;
    private List<ColumnFullResponseDTO> columns;
}

// dto/board_dto/full/ColumnFullResponseDTO.java
@Getter @Setter @Builder
public class ColumnFullResponseDTO {
    private String id;
    private String name;
    private List<TaskFullResponseDTO> tasks;
}

// dto/board_dto/full/TaskFullResponseDTO.java
@Getter @Setter @Builder
public class TaskFullResponseDTO {
    private String id;
    private String title;
    private String description;
    private List<SubtaskResponseDTO> subtasks; // reuse existing flat leaf DTO as-is
}
```

```java
// mapper/BoardFullMapper.java
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE,
    uses = {SubtaskMapper.class})
public interface BoardFullMapper {
    BoardFullResponseDTO toFullResponseDTO(BoardEntity board);
    // MapStruct auto-generates Column->ColumnFull and Task->TaskFull sub-mappings
    // by matching field names/types; add explicit @Mapping only if names diverge
    // (e.g. BoardEntity.column -> BoardFullResponseDTO.columns, since the existing
    // entity field is oddly singular-named for a List — see Pitfalls file).
}
```

Because MapStruct maps by walking the object graph you give it, this only works correctly if the `List<ColumnEntity>`/`List<TaskEntity>`/`List<SubtaskEntity>` on the entities passed in are already Hibernate-initialized collections (not lazy proxies) — which is exactly what the batch-fetch strategy in Pattern 3 must guarantee before the entity ever reaches the mapper.

### Pattern 2: `@Version` added directly to the two entities that need it, not to `BaseEntity`

**What:** `@Version private Long version;` goes on `TaskEntity` and `ColumnEntity` directly, as a sibling field to their existing `title`/`name` fields — not pulled up into `BaseEntity`.

**When to use:** Optimistic locking is being scoped, per the epic spec, to the two entities where concurrent-edit conflicts are a real product scenario (drag-and-drop reorder/move of tasks and columns). `BoardEntity`, `SubtaskEntity`, and `UserEntity` have no such concurrent-edit story in this milestone.

**Trade-offs:**
- **Why not add it to `BaseEntity`:** `BaseEntity` is a `@MappedSuperclass` shared by `UserEntity`, `BoardEntity`, `ColumnEntity`, `TaskEntity`, `SubtaskEntity`. Adding `@Version` there would silently add a `version` column requirement to *every* table, including ones that don't need it (`users`, `boards`, `subtasks`) — a bigger, unscoped schema change than the epic asks for, and it would force Hibernate's automatic version-check-on-every-update behavior onto entities where no test/behavior currently expects it (e.g. it could change `BoardService.updateById()`'s save semantics unexpectedly, or `UserService`'s account-deletion cascade timing, without anyone having decided that on purpose).
- **Why directly on the two entities is safe:** Each entity is a concrete `@Entity` class, and `@Version` is a completely ordinary mapped field. No inheritance change, no migration coordination beyond the two tables actually being modified. It keeps the blast radius of this change exactly matching the epic's stated scope.
- **If a later epic decides ALL entities need versioning:** that's a deliberate, separate decision — promote the field to `BaseEntity` at that point, as its own reviewable change. Don't pre-emptively generalize now.

**Example:**
```java
// entity/TaskEntity.java
@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
@Table(name = "tasks")
public class TaskEntity extends BaseEntity implements BaseTask {
    @ManyToOne @JoinColumn(name = "column_id")
    private ColumnEntity column;

    @OneToMany(mappedBy = "task")
    private List<SubtaskEntity> subtasks;

    @Column(nullable = false, length = ValidationConstants.MAX_TASK_TITLE_LENGTH)
    private String title;

    @Column(length = ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH)
    private String description;

    @Version
    private Long version;   // NEW
}
```

**On "without needing a manual migration script" — verified against the actual config, and the answer is more nuanced than it first looks:**

Checked both properties files directly:
- `src/main/resources/application-test.properties` sets `spring.jpa.hibernate.ddl-auto=create-drop` (H2, in-memory, recreated fresh every test run) — adding `@Version` here is automatically reflected with **zero migration concern**, since the schema is rebuilt from the entity model on every test boot.
- `src/main/resources/application.properties` (the real/dev/prod profile against PostgreSQL) **does not set `spring.jpa.hibernate.ddl-auto` at all**. Spring Boot's own default for a non-embedded database (PostgreSQL is not auto-detected as embedded) is `ddl-auto=none` — meaning Hibernate does **not** auto-alter the schema in this profile today. This is a materially different situation than "typical pre-Flyway project using `update`" — it means either (a) the current `tasks`/`columns` tables were created by some one-time manual/other mechanism already, and adding `@Version` in code alone will NOT create the column against a real Postgres instance and the app will fail at runtime with a "column version does not exist" SQL error the first time Hibernate tries to `SELECT`/`UPDATE` including that column, or (b) local/deployed Postgres instances are being recreated from scratch each time (e.g. via Docker Compose wiping the volume) in which case it's moot.

**Practical, still-no-Flyway-needed path, given `ddl-auto=none` in the real profile:**
1. Confirm which of the two situations above is true (check for a Docker Compose file, init scripts, or ask whether the Postgres instance is long-lived or ephemeral in this project's dev/deploy setup).
2. If the Postgres instance is long-lived (most likely for a project the user drives locally), the safest zero-Flyway option is **not** relying on `ddl-auto` at all (don't flip it to `update` just for this — that's a bigger behavior change than the epic asks for, and would silently start auto-migrating the schema on every future entity change too, which the project has apparently avoided doing deliberately). Instead, run one manual, one-off DDL statement against the dev database as part of implementing this change:
   ```sql
   ALTER TABLE tasks ADD COLUMN version bigint NOT NULL DEFAULT 0;
   ALTER TABLE columns ADD COLUMN version bigint NOT NULL DEFAULT 0;
   ```
   This is a manual SQL statement, but it is *not* a migration **script/tool** (no Flyway, no versioned migration files, no new dependency) — it's a single ad hoc statement run once against the local/dev DB, consistent with how the schema evidently already got created without Flyway. Document it in the PR description as the "how to apply" step, same spirit as a commit message noting a manual step.
   `DEFAULT 0` (not `NULL`) matters: Hibernate's optimistic-lock check treats a `null` version specially (as "not yet versioned"/first insert), so backfilling existing rows to `0` avoids ambiguous behavior on the first `UPDATE` of a pre-existing row after the column is added.
3. If the team later decides recurring schema drift like this is common enough to formalize, that's exactly the trigger for introducing Flyway — already flagged as future work (Epic 3+) in `PROJECT.md`'s Out of Scope, so don't pull it into this milestone.

This nuance (verified `ddl-auto` is unset/`none` in the real profile, not `update`) is the single most important correction to carry into phase planning — treat "how does the version column get into the real Postgres schema" as an explicit task in whichever phase implements optimistic locking, not an assumed side-effect of the code change.

### Pattern 3: Batch-fetch-and-stitch for the nested aggregate — one query per tree level (not a naive triple `JOIN FETCH`, not `@BatchSize` alone)

**What:** Fetch the aggregate in a small, fixed number of queries regardless of how many columns/tasks/subtasks exist, then assemble the Java object graph before mapping to DTOs.

**When to use:** Any time a single response needs to nest **two or more** `List`-valued to-many relationships (`Board.columns` × `Column.tasks` × `Task.subtasks` here). This is exactly the Cartesian-product trap the epic spec calls out.

**Trade-offs — why one-query-per-level-and-stitch over the alternatives:**

| Approach | Query count | Cartesian risk | Verdict |
|---|---|---|---|
| Naive triple `JOIN FETCH` (`board.columns.tasks.subtasks` in one JPQL query) | 1 | **High** — Hibernate cannot `JOIN FETCH` more than one `List`/bag association in a single query without either a `MultipleBagFetchException` or duplicate-row bloat from the Cartesian product; nesting three collection levels compounds this | Rejected — explicitly what the epic spec says to avoid |
| `@BatchSize` on all three collections | Variable (batched `IN` queries triggered lazily per collection as they're accessed, one batch per `parentIds.size() / batchSize`) | None | Viable, but relies on lazy-loading firing correctly at mapping time within the transaction, and the exact query count is less predictable/explicit than counting it upfront |
| **One-query-per-level-and-stitch (recommended)** | Exactly 3 queries total (Board+Columns, then Tasks, then Subtasks), independent of board size | None | Recommended — explicit, deterministic query count, doesn't depend on Hibernate's lazy-loading/batching machinery working invisibly; easiest to reason about, count in a test, and explain to a reviewer |

**Recommended shape — 3 queries, one per collection level, each a flat (non-transitive) fetch:**

The important constraint: never `JOIN FETCH` across **two** collection associations in the same query, even implicitly. `Board → Columns` is one collection; `Column → Tasks` is another; `Task → Subtasks` is a third. Fetching any two of them together in one JPQL statement re-triggers the same multiple-bag-fetch problem the naive-triple-join is rejected for — so each query below fetches exactly one collection level, using the parent IDs already collected from the previous query.

**Query 1** — Board + its Columns (one collection, safe to `JOIN FETCH`):
```java
// ColumnRepository (or BoardRepository) — new method
@Query("SELECT b FROM BoardEntity b JOIN FETCH b.column c WHERE b.id = :boardId")
Optional<BoardEntity> findByIdWithColumns(@Param("boardId") String boardId);
```
(Note: `BoardEntity.column` is the existing, oddly-singular field name for the `List<ColumnEntity>` — see Pitfalls file.)

**Query 2** — all Tasks for the column IDs from query 1 (flat `IN`, no nested fetch):
```java
// TaskRepository — new method
List<TaskEntity> findAllByColumnIdIn(Collection<String> columnIds);
```

**Query 3** — all Subtasks for the task IDs from query 2 (flat `IN`, no nested fetch):
```java
// SubtaskRepository — new method
List<SubtaskEntity> findAllByTaskIdIn(Collection<String> taskIds);
```

Then stitch in Java: group tasks by `columnId`, group subtasks by `taskId`, and assign them back onto the in-memory (already Hibernate-managed, same-transaction) entity graph before mapping.

This is a 3-query strategy, not 2 — describe it accurately as "one query per tree level, stitched in Java" when documenting/defending the choice. It's still small, fixed, and independent of data volume (O(1) queries regardless of how many columns/tasks/subtasks exist), and it fully avoids the Cartesian-product blowup because no single query ever joins across more than one collection association. `@BatchSize` was considered as the practical alternative and is worth mentioning as "considered, rejected for weaker query-count predictability" — exactly the kind of trade-off the epic spec asks to be ready to explain.

**Example (service-layer stitching):**
```java
// BoardService.java — new method
@Transactional
public BoardFullResponseDTO getFullBoard(String userId, String boardId) {
    var pair = ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId);
    var board = columnRepository.findByIdWithColumns(pair.getSecond().getId())
            .orElseThrow(() -> new AppEntityNotFoundException("Board"));

    var columnIds = board.getColumn().stream().map(ColumnEntity::getId).toList();
    var tasks = taskRepository.findAllByColumnIdIn(columnIds);
    var taskIds = tasks.stream().map(TaskEntity::getId).toList();
    var subtasks = subtaskRepository.findAllByTaskIdIn(taskIds);

    var tasksByColumnId = tasks.stream().collect(Collectors.groupingBy(t -> t.getColumn().getId()));
    var subtasksByTaskId = subtasks.stream().collect(Collectors.groupingBy(s -> s.getTask().getId()));

    board.getColumn().forEach(col -> {
        var colTasks = tasksByColumnId.getOrDefault(col.getId(), List.of());
        colTasks.forEach(t -> t.setSubtasks(subtasksByTaskId.getOrDefault(t.getId(), List.of())));
        col.setTask(colTasks);
    });

    return boardFullMapper.toFullResponseDTO(board);
}
```
This mutates the in-memory (already-fetched) entity graph's transient collection fields to attach the stitched children before handing off to the mapper — since these are the same Hibernate-managed entities within the same transaction, setting `col.setTask(...)` to the manually-fetched list is safe (it overwrites the lazy proxy reference before anything ever tries to lazy-load it), and MapStruct then walks the now-fully-populated graph.

## Data Flow

### Request Flow — `GET /boards/{boardId}/full`

```
HTTP GET /boards/{boardId}/full
    |
BoardController.getFullBoard(userId, boardId)          [NEW method, same class]
    |
BoardService.getFullBoard(userId, boardId)              [NEW method]
    |
OwnershipVerifierService.verifyOwnershipOfBoard()        [existing, unchanged -- 1 query]
    |
ColumnRepository.findByIdWithColumns(boardId)             [NEW -- query 1: Board + Columns]
    |
TaskRepository.findAllByColumnIdIn(columnIds)              [NEW -- query 2: all Tasks for those columns]
    |
SubtaskRepository.findAllByTaskIdIn(taskIds)                [NEW -- query 3: all Subtasks for those tasks]
    |
[in-memory stitch: group tasks by columnId, subtasks by taskId, attach to entity graph]
    |
BoardFullMapper.toFullResponseDTO(board)                  [NEW MapStruct mapper, walks fully-populated graph]
    |
BoardFullResponseDTO (nested: Board -> Columns -> Tasks -> Subtasks)
    |
200 OK, JSON body
```

Total: **1 (ownership) + 3 (fetch levels) = 4 queries**, constant regardless of board size — versus the client currently needing `1 + columns + tasks` round trips if it renders the same view by calling today's flat per-level endpoints itself.

### Request Flow — Optimistic locking conflict (drag-and-drop reorder)

```
Client A: GET task (version=3) --.
Client B: GET task (version=3) --+ both read same version
                                  |
Client A: PUT task (moves column) -> UPDATE ... WHERE id=? AND version=3 -> succeeds, version becomes 4
                                  |
Client B: PUT task (reorders)     -> UPDATE ... WHERE id=? AND version=3 -> 0 rows affected
                                  |
                    Hibernate detects 0-row update on a versioned entity
                                  |
                    throws ObjectOptimisticLockingFailureException
                                  |
                    propagates up through TaskService.updateById() (unchanged method body,
                    exception surfaces naturally from taskRepository.save())
                                  |
                    GlobalExceptionHandler.handleObjectOptimisticLockingFailure()  [NEW/adjusted handler]
                                  |
                    409 Conflict response to Client B
```

Note: the existing `GlobalExceptionHandler` already has a handler for `OptimisticLockingFailureException` mapped to **423 Locked**. `ObjectOptimisticLockingFailureException` (thrown specifically by JPA/Hibernate `@Version` conflicts) is a subtype of `OptimisticLockingFailureException` — so the existing generic handler will already catch it, unless a more specific handler is added. The epic spec explicitly asks for **409**, not the existing **423** — this requires either changing the existing handler's status code from 423->409, or adding a new, more-specific `@ExceptionHandler(ObjectOptimisticLockingFailureException.class)` that Spring will dispatch to in preference to the generic one. Decide explicitly which; don't leave both silently disagreeing about status code — flagged as a Pitfall.

### Key Data Flows

1. **`/full` endpoint:** One ownership check + three flat `IN`-clause fetch queries + in-memory stitch + MapStruct mapping. No new N+1 risk introduced because every list is fetched with a single batched query keyed by parent IDs collected from the previous step, not fetched per-parent-in-a-loop.
2. **Optimistic locking:** No change to data flow shape at all — `@Version` piggybacks on the existing `UPDATE` path (`taskRepository.save()` / `columnRepository.save()`), Hibernate appends `AND version = ?` to the `UPDATE` and increments it automatically. The only new flows are (a) the exception path, which needs one decision (409 vs 423) reconciled in `GlobalExceptionHandler`, and (b) the one-time schema change against the real Postgres instance (see Pattern 2 — `ddl-auto=none` in the real profile means this doesn't happen automatically).

## Scaling Considerations

| Scale | Architecture Adjustments |
|-------|--------------------------|
| Personal/portfolio project (current) | 4-query stitch is more than sufficient; boards realistically have single-digit columns, tens of tasks |
| If boards grow to hundreds of tasks | Same query shape still holds — it's O(1) queries regardless of row count, only row-count-proportional data transfer grows. Add `@BatchSize` on `Column.task`/`Task.subtasks` as a *defense-in-depth* fallback for any other endpoint that isn't the `/full` aggregate but still risks incidental lazy access, without changing the `/full` endpoint's own explicit-fetch strategy |
| If concurrent editors become common (multi-user boards, not just multi-tab) | Optimistic locking as implemented is the correct default; only escalate to pessimistic locking (`@Lock(PESSIMISTIC_WRITE)`) if conflict *retry* UX becomes a real product requirement — out of scope for this milestone |

### Scaling Priorities

1. **First bottleneck:** None expected at this project's scale — the 4-query stitch is already the scalable shape (constant query count). No premature optimization needed.
2. **Second bottleneck:** If a "board list with full nesting" endpoint is ever requested (multiple boards, each fully nested), the same stitch pattern applies per-board but batching across boards would need care — explicitly out of scope; the current epic only asks for single-board `/full`.

## Anti-Patterns

### Anti-Pattern 1: Naive triple `JOIN FETCH` across `board.columns.tasks.subtasks` in one query

**What people do:** Reach for `@Query("SELECT b FROM BoardEntity b JOIN FETCH b.column c JOIN FETCH c.task t JOIN FETCH t.subtasks WHERE b.id = :id")` because it "looks like" the natural one-query solution.
**Why it's wrong:** Hibernate cannot cleanly fetch multiple bag (`List`) associations in a single query without either throwing `MultipleBagFetchException` or silently producing a Cartesian-product result set (rows duplicated once per combination of column×task×subtask), which both wastes bandwidth and can produce duplicate entities in the Java-side collection unless deduplicated with `DISTINCT`/`Set`.
**Do this instead:** One query per list-nesting level, each keyed by the parent IDs collected from the previous level (Pattern 3 above).

### Anti-Pattern 2: Letting the new nested DTOs replace/merge into the existing flat DTOs

**What people do:** Add an optional `List<ColumnResponseDTO> columns` field directly onto the existing `BoardResponseDTO`, reasoning "it's optional, other endpoints just won't populate it."
**Why it's wrong:** Every consumer of `BoardResponseDTO` (today: `GET /boards`) would now carry a nullable field with unclear contract ("is this null because it's the flat endpoint, or because the board has no columns?"), and any future engineer touching `BoardMapper` risks accidentally trying to populate/traverse it outside a transaction, reintroducing the exact `LazyInitializationException` risk the flat convention exists to prevent.
**Do this instead:** New, separate `BoardFullResponseDTO` (Pattern 1) reached only via the new `/full` endpoint and its own dedicated mapper. Zero ambiguity about which endpoint returns which shape.

### Anti-Pattern 3: Adding `@Version` to `BaseEntity`

**What people do:** Since `BaseEntity` is the shared base class, reach for adding `@Version` there "once, for everyone," reasoning it's more DRY.
**Why it's wrong:** Silently obligates every entity (`UserEntity`, `BoardEntity`, `SubtaskEntity` included) to carry version-check semantics on every update, expanding the schema/behavior change beyond what the epic scoped (only `TaskEntity`/`ColumnEntity` have a concurrent-edit scenario worth guarding). It also risks surprising currently-passing tests that don't expect optimistic-lock exceptions on unrelated entities, and — since `ddl-auto=none` in the real profile — would silently expand the manual-DDL surface to five tables instead of two.
**Do this instead:** Add the field directly to the two entities that need it (Pattern 2). Promote to `BaseEntity` later only as an explicit, separately-reviewed decision if broader coverage becomes a real requirement.

### Anti-Pattern 4: Re-verifying ownership per list level during the `/full` fetch

**What people do:** Call `ownershipVerifierService.verifyOwnershipOfColumn()` per column, or `verifyOwnershipOfTask()` per task, while building the nested tree — mirroring the (already-being-fixed-elsewhere) N+1 pattern from Finding 2 in the epic spec.
**Why it's wrong:** Ownership of the board is already established once at the top (`verifyOwnershipOfBoard`); every column/task/subtask under an owned board is transitively owned by the same user via the FK chain. Re-verifying at each level is the exact same wasted-chatty-query anti-pattern the epic spec is fixing elsewhere in `OwnershipVerifierService`.
**Do this instead:** Verify board ownership once at the top of `BoardService.getFullBoard()`, then fetch children by `boardId`/`columnIds`/`taskIds` directly via repository queries — no further ownership re-checks needed, since the WHERE-clause scoping to already-verified parent IDs is itself the security boundary.

### Anti-Pattern 5: Assuming `ddl-auto` will silently create the `version` column in the real database

**What people do:** Add `@Version` to the entities, run the app locally against Postgres, see it "just work" in dev, and assume production will behave the same without checking `application.properties`.
**Why it's wrong:** The real profile has no `spring.jpa.hibernate.ddl-auto` set, which defaults to `none` for PostgreSQL (non-embedded) — Hibernate will not auto-add the `version` column against a persistent Postgres instance the way it does in the test profile's `create-drop` mode. If the dev Postgres instance happens to be recreated from scratch frequently (e.g. Docker volume wiped often), this could go unnoticed until a longer-lived instance (or a teammate's persistent local DB) hits a "column does not exist" SQL error.
**Do this instead:** Explicitly verify how the real schema currently gets created (check for init scripts/Docker Compose/manual setup), and add the `version` column via one manual, one-off `ALTER TABLE ... ADD COLUMN version bigint NOT NULL DEFAULT 0` statement per table — not a code-only change. See Pattern 2 for the full reasoning.

## Integration Points

### External Services

Not applicable — no new external service integration in this scope (no Kafka/Redis/Flyway per PROJECT.md's explicit Out of Scope).

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| `BoardController` ↔ `BoardService` | Direct method call, existing `@Autowired` field injection pattern, unchanged | New `getFullBoard()` method added to both; no new cross-service dependency introduced (still only calls `OwnershipVerifierService` + repositories, same as existing `BoardService` methods) |
| `BoardService` ↔ `ColumnRepository`/`TaskRepository`/`SubtaskRepository` | Direct repository calls for the 3-level fetch-and-stitch | New query methods added to existing repository interfaces; **no new service-to-service dependency** — `BoardService` calling `TaskRepository`/`SubtaskRepository` directly (rather than through `TaskService`/`SubtaskService`) is a deliberate, scoped exception to "services call services," justified because this is a read-only aggregate query, not a business operation, and going through the intermediate services would force fetching through their existing per-entity ownership-verification methods again (reintroducing Anti-Pattern 4) |
| `BoardFullMapper` ↔ `SubtaskMapper` | MapStruct `uses = {...}` composition | Read-only composition; does not create a runtime circular dependency because MapStruct wires this at compile time as plain bean references, same as any other Spring `@Autowired` — no different from existing mapper usage patterns |
| `TaskService`/`ColumnService` ↔ `GlobalExceptionHandler` | Exception propagation (not a direct call) | `ObjectOptimisticLockingFailureException` thrown by `taskRepository.save()`/`columnRepository.save()` propagates up through unchanged service method bodies to the existing `@ControllerAdvice`; requires a decision on 409 vs. existing 423 mapping (see Data Flow section) |
| New code ↔ real Postgres schema | One-off manual DDL, not framework-managed | Because `ddl-auto` is unset (defaults to `none`) in `application.properties`, the `version` column must be added to the real database out-of-band from the code change — this is the one boundary in this work that is NOT purely a Spring-managed integration (see Pattern 2, Anti-Pattern 5) |

## Answers to the Three Specific Questions (summary)

**Q1 — Where does the nested DTO structure live, and how is it reconciled with the flat convention?**
New `dto/board_dto/full/` subpackage holding `BoardFullResponseDTO` → `ColumnFullResponseDTO` → `TaskFullResponseDTO` → (reuse existing `SubtaskResponseDTO` as the leaf). A dedicated `BoardFullMapper` (new MapStruct interface, composing the existing `SubtaskMapper` via `uses`) does the conversion. The flat convention is preserved everywhere else untouched; the new nested shape is safe specifically because the batch-fetch strategy (Q3) guarantees every nested field is already Hibernate-initialized before mapping — the flat convention's *purpose* (avoid `LazyInitializationException`) is upheld by fetch discipline, not by DTO shape alone.

**Q2 — Where does `@Version` belong relative to `BaseEntity`, and how to add it without a migration script?**
Directly on `TaskEntity` and `ColumnEntity`, not on `BaseEntity` — scoped to exactly the two entities the epic's product scenario (drag-and-drop conflict) concerns. Verified in the actual config: the test profile (`application-test.properties`) uses `ddl-auto=create-drop`, so the field just works there with zero extra effort. The real profile (`application.properties`) has **no `ddl-auto` set at all**, which defaults to `none` for PostgreSQL — meaning Hibernate will NOT auto-add the column against a real, persistent Postgres instance. The correct "no Flyway needed" path is one manual, one-off `ALTER TABLE tasks/columns ADD COLUMN version bigint NOT NULL DEFAULT 0` statement per table (not a migration tool/versioned script — just an ad hoc DDL statement, consistent with however the rest of the schema evidently already got created without Flyway). Use `DEFAULT 0`, not leaving it nullable, so pre-existing rows don't hit Hibernate's null-version-means-unversioned edge case on their first post-change `UPDATE`.

**Q3 — What repository/service changes support batch-fetching in a small, fixed number of queries?**
Three new flat (non-transitive) fetch queries, one per list-nesting level: `ColumnRepository.findByIdWithColumns(boardId)` (Board + Columns via one `JOIN FETCH`), `TaskRepository.findAllByColumnIdIn(columnIds)` (flat `IN` query, no nested fetch), `SubtaskRepository.findAllByTaskIdIn(taskIds)` (flat `IN` query). Stitch results in Java inside `BoardService.getFullBoard()` by grouping children by parent ID and assigning into the entity graph's transient list fields before mapping. This is a 3-query strategy (one query per tree level), not the naive single triple-join and not `@BatchSize`'s variable batch count — accurately describe it as "one query per level of the tree, stitched in Java," and note `@BatchSize` was considered and rejected specifically for weaker query-count predictability, per the epic's own ask to justify the choice.

## Build Order / Sequencing Note

**These two deliverables are independent and can be built in either order** — they touch disjoint parts of the stack (new DTOs/mapper/repo-methods for `/full`; new entity field + exception-handler adjustment + one-off DDL for locking) with no shared code path. Recommended order, if sequencing anyway for review-size reasons: **optimistic locking first**, because it's the smaller, more self-contained change (one field × 2 entities + one exception-handler decision + one manual DDL step + one test), giving a quick clean PR; then the `/full` endpoint, which is the larger surface area (3 new DTOs, 1 new mapper, 3 new repository methods, stitching logic, and its own dedicated test). Building locking first also surfaces the `ddl-auto=none` schema-application question early, before it can be mixed up with the also-nontrivial `/full` endpoint work. No hard dependency either way — flag this as a build-order *preference*, not a requirement, for roadmap phase ordering.

---
*Architecture research for: Spring Boot / JPA-Hibernate kanban backend — Epic 2 completion (nested aggregate + optimistic locking)*
*Researched: 2026-07-31*
