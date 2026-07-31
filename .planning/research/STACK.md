# Stack Research

**Domain:** JPA/Hibernate depth work on existing Spring Boot 3.5.0 REST API (nested-aggregate fetching + optimistic locking)
**Researched:** 2026-07-31
**Confidence:** HIGH

This is a narrow, well-trodden corner of the JPA/Hibernate ecosystem — both problems (bag-fetch Cartesian products, optimistic locking + HTTP mapping) are canonical, extensively documented patterns with a clear consensus answer as of Hibernate 6.x / Spring Boot 3.5. No exotic tech is needed; this is entirely about correct use of what's already on the classpath (`spring-boot-starter-data-jpa`, which pulls in Hibernate ORM 6.x transitively).

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Hibernate ORM | 6.x (bundled transitively via `spring-boot-starter-data-jpa` in Spring Boot 3.5.0 — no explicit version pin needed) | JPA provider already in use | Already the project's ORM; both features (batch fetch, `@Version`) are core Hibernate/JPA capabilities, no additional dependency required |
| Spring Data JPA | matches Spring Boot 3.5.0 BOM (Spring Data 2025.0.x line) | Repository abstraction, exception translation | Already in use; its `PersistenceExceptionTranslationPostProcessor` is what turns JPA's `jakarta.persistence.OptimisticLockException` into Spring's `ObjectOptimisticLockingFailureException` — this translation is automatic and requires zero extra config, only correct exception handling downstream |

**Note on Hibernate version pinning:** do not hand-roll a `hibernate.version` override in `build.gradle`. Spring Boot 3.5.0's dependency management BOM already selects a compatible Hibernate 6.x patch release tested against that Spring Boot/Spring Data combination; overriding it risks subtle behavioral regressions in fetch/batch semantics documented below. Confidence: MEDIUM (exact patch version not independently verified against Maven Central in this pass — verify with `./gradlew dependencies --configuration compileClasspath | grep hibernate-core` before writing the phase plan, since it costs one command and removes all doubt).

### Supporting Libraries / Config (no new dependencies)

| Config/Annotation | Where | Purpose | When to Use |
|---|---|---|---|
| `@BatchSize(size = N)` | On `@OneToMany` collection fields (`BoardEntity.column`, `ColumnEntity.task`, `TaskEntity.subtasks`) | Batches lazy-collection initialization into `IN (...)` queries instead of one query per parent | Use for the `/full` endpoint's second and third collection levels, after JOIN FETCH-ing the first |
| `@Version` | New `Long version` field, added to `TaskEntity` and `ColumnEntity` (per the epic spec) | Optimistic concurrency control | Any entity subject to concurrent update races — here specifically drag/reorder targets |
| `hibernate.default_batch_fetch_size` (application.properties) | Global Hibernate setting | Alternative to per-entity `@BatchSize` | Prefer per-entity `@BatchSize` here since only 2–3 collections need it and explicitness aids the interview-narrative goal stated in the epic spec; use global config only if this pattern spreads to many more entities later |

## Installation

No new dependencies. Everything needed ships with the existing `org.springframework.boot:spring-boot-starter-data-jpa` dependency already in `build.gradle`. This is purely an annotation/configuration/query-shape change.

```bash
# Nothing to install — verify existing Hibernate version only:
./gradlew dependencies --configuration compileClasspath | grep hibernate-core
```

---

## Question 1 — `GET /boards/{boardId}/full` without Cartesian-product blowup or N+1

### The core problem (verified, HIGH confidence — Hibernate ORM is explicit about this)

`BoardEntity.column`, `ColumnEntity.task`, and `TaskEntity.subtasks` are all mapped as `List<...>` (`@OneToMany` "bags" — unordered, duplicate-permitting collections, since none carry `@OrderColumn` or a `Set` type). Hibernate **cannot** `JOIN FETCH` two bags in the same query — this throws `org.hibernate.loader.MultipleBagFetchException` at query-parse time, not runtime, so a naive triple `JOIN FETCH board.columns.tasks.subtasks` (fetching both `columns→tasks` and `tasks→subtasks` in one query) will fail outright before it even produces bad data.

Even if you defeated that exception (e.g., by converting all three collections to `Set`), you would trade a compile/parse-time error for a *silent correctness and performance regression*: JOIN-fetching two one-to-many collections in a single SQL query produces a Cartesian product at the database level. For a board with, say, 5 columns × 8 tasks/column × 3 subtasks/task, a naive triple join returns `5 × 8 × 3 = 120` duplicated-and-inflated row combinations for what should conceptually be ~53 entities (5 + 40 + 120 if you count subtasks — but the point is the *row count returned by the DB*, before Hibernate's result-set deduplication, balloons multiplicatively across every fetched level, not additively). At small board sizes this is invisible; it becomes a real performance and memory problem exactly as boards grow, which is precisely the failure mode interviewers probe for.

### Recommended approach for this codebase: JOIN FETCH one level + `@BatchSize` for the rest

Given the shape here — 3 levels deep (Board → Column → Task → Subtask), each a `List` — the standard, Hibernate-idiomatic answer as of 6.x is:

1. **JOIN FETCH exactly one collection** in the primary query — the first level (`board.columns`), since it's the only one guaranteed to be reasonably small (a handful of columns per board) and directly needed to shape the response root.
2. **Apply `@BatchSize`** to the deeper collections (`ColumnEntity.task`, `TaskEntity.subtasks`) so that when the mapper/service subsequently touches `column.getTask()` and `task.getSubtasks()`, Hibernate issues **one batched `SELECT ... WHERE column_id IN (?, ?, ?, ...)`** per level — not one query per row. With `@BatchSize(size = 25)` and a board that has, say, 8 columns, that's 1 query for columns' tasks (all 8 column IDs batched into one `IN` clause) and 1 query for subtasks (all task IDs from those columns batched into one `IN` clause) — **3 total queries for the whole aggregate, flat and bounded**, regardless of how many columns/tasks exist (it becomes `ceil(N/batchSize)` queries only if N exceeds the batch size, which is a graceful degradation, not N+1).

```java
// Repository — first level via JOIN FETCH
public interface BoardRepository extends JpaRepository<BoardEntity, String> {
    @Query("SELECT b FROM BoardEntity b LEFT JOIN FETCH b.column WHERE b.id = :boardId")
    Optional<BoardEntity> findByIdWithColumns(@Param("boardId") String boardId);
}
```

```java
// Entity — batch the deeper collections instead of joining them
@Entity
public class ColumnEntity extends BaseEntity implements BaseBoard {
    // ...
    @OneToMany(mappedBy = "column")
    @BatchSize(size = 25)
    private List<TaskEntity> task;
}

@Entity
public class TaskEntity extends BaseEntity implements BaseTask {
    // ...
    @OneToMany(mappedBy = "task")
    @BatchSize(size = 25)
    private List<SubtaskEntity> subtasks;
}
```

Then in the service, accessing `board.getColumn().forEach(c -> c.getTask().forEach(t -> t.getSubtasks()...))` inside the same `@Transactional` method triggers exactly the batched queries described above — Hibernate detects the access pattern across the batch and coalesces it, it does not refetch per-row.

**Why this over the alternatives:**

| Alternative | Why not chosen here |
|---|---|
| Naive triple `JOIN FETCH` across two `List` bags | Throws `MultipleBagFetchException` at startup/query-parse time — doesn't even run. |
| Convert all collections to `Set` + triple JOIN FETCH | Removes the exception but reintroduces the Cartesian product silently — worse, because it *works* at small scale and only degrades as boards grow, making it a landmine rather than a hard failure. Also a bigger diff (entity type changes ripple into mappers, equals/hashCode, and any ordering assumptions the frontend relies on for column/task order — column/task order is almost certainly meaningful for a kanban board, and `Set` has no reliable order without an explicit `@OrderColumn`, which this codebase doesn't have). |
| Two-queries-and-stitch-in-Java (fetch board+columns in query 1, then a second `JOIN FETCH` query for `columns.tasks`, merging via Hibernate's persistence-context identity) | Valid and Vlad Mihalcea's stated general-purpose answer, but requires an extra explicit merge step and only handles 2 levels before you need a 3rd query anyway for subtasks — at that point it's the same query count as the batch-size approach but with more code to maintain (manual `IN (:ids)` List<Long> extraction) for no benefit, since `@BatchSize` gives the same query-count outcome declaratively. Two-query-stitch becomes preferable over `@BatchSize` mainly when result sets are enormous (thousands of rows) and you want to paginate the parent query independently — not the case for a single board's aggregate. |
| `@EntityGraph(attributePaths = {"column", "column.task", "column.task.subtasks"})` | Under the hood, Hibernate compiles multi-level `@EntityGraph` fetches into the same JOIN FETCH SQL as JPQL — so a graph spanning two `List` levels hits the *identical* `MultipleBagFetchException`. `@EntityGraph` is not a workaround for the bag-fetch restriction; it's syntactic sugar over the same mechanism, so it doesn't change this analysis. |
| MULTISET-based fetching (Hibernate 6.5+ native, or via Blaze Persistence/jOOQ) | Real, modern (2024+) fix for this exact problem in a single query without Cartesian products, using a SQL-level array/collection sub-select rather than a join. Not recommended here: it requires Hibernate 6.5+ (verify against the pinned Boot 3.5.0 version) or an additional third-party library (Blaze Persistence/jOOQ), meaningfully expands scope for a portfolio-scoped epic, and `@BatchSize` already solves the problem with primitives already on the classpath. Worth mentioning in an interview as "the newer alternative" but not worth adopting here. |

**Confidence: HIGH.** `MultipleBagFetchException` and the `@BatchSize` mitigation are extensively and consistently documented across Hibernate's own contributors (Vlad Mihalcea, a Hibernate core team alumnus) and independent sources (Baeldung, Thorben Janssen), with no contradictions found across sources.

### DTO shape implication

The `/full` endpoint necessarily breaks this codebase's stated flat-DTO convention (see `ARCHITECTURE.md` — DTOs are flat specifically to dodge `LazyInitializationException`). That's fine and expected — the epic spec calls this out explicitly as a deliberate, justified departure. The key discipline to carry over: perform **all** collection traversal (`getTask()`, `getSubtasks()`) inside the `@Transactional` service method, and build the nested response DTO tree there, before the Hibernate session closes. Do not return the entity graph itself out of the service layer and expect a MapStruct mapper invoked later (e.g., from the controller) to still have an open session — that reintroduces the exact `LazyInitializationException` risk the flat-DTO convention was built to avoid. A dedicated `BoardFullResponseDTO` (with nested `ColumnDTO { List<TaskDTO> { List<SubtaskDTO> } }`) built via nested MapStruct mappers (`uses = {ColumnMapper.class}` etc.) invoked from within the transactional service method is the correct shape.

---

## Question 2 — `@Version` optimistic locking + 409 mapping

### Mechanism (verified, HIGH confidence)

Adding `@jakarta.persistence.Version private Long version;` to `TaskEntity` and `ColumnEntity` is genuinely all that's needed to activate optimistic locking for those entities — Hibernate automatically:
- Includes `version` in every `UPDATE ... WHERE id = ? AND version = ?` it issues for that entity,
- Increments it on every successful update,
- Throws `jakarta.persistence.OptimisticLockException` (wrapped by Spring Data as `org.springframework.orm.ObjectOptimisticLockingFailureException`, itself a subtype of `org.springframework.dao.OptimisticLockingFailureException`) when the `WHERE` clause matches zero rows because another transaction already bumped the version first.

```java
// BaseEntity — do NOT add here; see rationale below
// TaskEntity.java
@Entity
public class TaskEntity extends BaseEntity implements BaseTask {
    @Version
    private Long version;
    // ... existing fields
}

// ColumnEntity.java
@Entity
public class ColumnEntity extends BaseEntity implements BaseBoard {
    @Version
    private Long version;
    // ... existing fields
}
```

**Why `Long`, not `int`/`Integer`:** `Long` avoids overflow entirely in practice and is the type used in virtually every reference example (Baeldung, Vlad Mihalcea, Spring's own JPA guides). `int` technically works too (JPA spec supports `int`, `Integer`, `long`, `Long`, `short`, `Short`, and `java.sql.Timestamp`/`Instant` for the version column), but `Long` is the pragmatic, future-proof default and matches the epic spec's explicit instruction (`@Version private Long version;`). Confidence: HIGH for correctness of either type; MEDIUM-leaning-convention for "why Long specifically" (it's community convention/spec-compliant, not a hard requirement).

**Why put `@Version` directly on `TaskEntity`/`ColumnEntity` rather than on `BaseEntity`:** the epic spec scopes this to `TaskEntity` and `ColumnEntity` only (the two entities actually subject to concurrent drag/reorder races). `BoardEntity` and `SubtaskEntity` don't have that access pattern in the app today (subtasks are edited one at a time by their owner; boards aren't collaboratively dragged). Adding `@Version` to `BaseEntity` would silently apply optimistic locking to every entity including `UserEntity`, which is broader blast radius than the epic asks for, and risks failing existing tests/flows that update those entities without expecting version-check semantics (e.g., any code path doing partial-field updates via `save()` after a stale read). Add it narrowly to the two entities named in the spec.

### Parent-collection version nuance (relevant to this schema — verified, HIGH confidence)

`ColumnEntity.task` and `BoardEntity.column` are the **non-owning side** of their `@OneToMany` (`mappedBy`), with `TaskEntity.column`/`ColumnEntity.board` owning the FK. Per the JPA spec, Hibernate does **not** bump a parent's `@Version` merely because a child in its `mappedBy` collection changed (e.g., moving a `TaskEntity` to a different `ColumnEntity`, or editing a task's title, does **not** increment `ColumnEntity.version` or `BoardEntity.version`). This is actually the *correct* behavior for this use case: the stated conflict scenario is two clients editing/reordering **the same task** concurrently — that's a direct `TaskEntity.version` conflict, detected correctly without any extra configuration. You do not need `OPTIMISTIC_FORCE_INCREMENT` locking or any parent-version-cascading trick here; that mechanism exists for a different problem (detecting "this list's membership/order changed" as a whole), which is out of scope per the epic spec (only "silent overwrite of a single row" is asked for). Flag this only as a known limitation if reordering *within a column* (position/order field on `TaskEntity` itself, presumably) is the concurrency surface — if position is a field on `TaskEntity`, then `TaskEntity.version` still catches it correctly, since both concurrent reorder operations are `UPDATE tasks SET ... WHERE id=? AND version=?` on the same row.

### Exception-to-409 mapping — critical fix needed in the existing handler

**This is the most important concrete finding: the codebase's current `GlobalExceptionHandler` already has an `@ExceptionHandler(OptimisticLockingFailureException.class)` that maps to `HttpStatus.LOCKED` (423), not 409.** `ObjectOptimisticLockingFailureException` (what Spring Data actually throws on a version mismatch) **is a subclass of** `org.springframework.dao.OptimisticLockingFailureException` — so today's handler already intercepts it, but returns the wrong status code for the epic's requirement (409, not 423).

Two ways to fix, in order of preference:

**Option A (recommended): change the existing handler's status to 409, keep the broader type.**
```java
@ExceptionHandler(OptimisticLockingFailureException.class)
public ResponseEntity<String> handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
    return new ResponseEntity<>(ex.getMessage(), HttpStatus.CONFLICT); // 409, not 423
}
```
This is a one-line change. It correctly returns 409 for `ObjectOptimisticLockingFailureException` (the concrete exception thrown by Spring Data JPA repositories on a stale `@Version` write) and any other `OptimisticLockingFailureException` subtype, since Spring's exception hierarchy is designed for exactly this kind of broad, safe catch-all.

**Option B (more precise, if you want the epic's exact exception type explicit in the signature for documentation/interview clarity):** add a dedicated handler for `ObjectOptimisticLockingFailureException` above the more general one — Spring's `@ExceptionHandler` resolution picks the most specific matching type, so ordering doesn't strictly matter, but it reads more clearly:
```java
@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
public ResponseEntity<String> handleObjectOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex) {
    return new ResponseEntity<>(
        "The record was updated by another request. Please reload and try again.",
        HttpStatus.CONFLICT); // 409
}
```
Recommendation: **do Option A** — it's the minimal, correct diff, and 409 Conflict is the RFC 7231-conventional status for "the request conflicts with the current state of the target resource," which is exactly what a stale-version write is. Reserve Option B only if the team wants a friendlier, non-leaky message distinct from other `OptimisticLockingFailureException` subtypes (there likely aren't other subtypes reachable in this codebase today, so Option A is sufficient and lower-risk).

**Why NOT 423 Locked (the current behavior):** 423 is defined by WebDAV (RFC 4918) to mean the resource is explicitly locked (e.g., a pessimistic lock held by another session) — semantically wrong for optimistic concurrency, where there is no lock at all, just a detected write conflict after the fact. 409 Conflict is both the epic spec's explicit requirement and the semantically correct HTTP status per REST convention (this exact mapping — `ObjectOptimisticLockingFailureException` → 409 — appears consistently across every optimistic-locking + Spring Boot resource surveyed).

### Test approach (matches epic spec, confirms via Spring's real wrapper type)

```java
@Test
void concurrentUpdate_throwsObjectOptimisticLockingFailureException() {
    // Load same task in two separate "sessions" (two independent finds)
    TaskEntity session1 = taskRepository.findById(taskId).orElseThrow();
    TaskEntity session2 = taskRepository.findById(taskId).orElseThrow();

    session1.setTitle("Updated by session 1");
    taskRepository.saveAndFlush(session1); // version bumps 0 -> 1

    session2.setTitle("Updated by session 2"); // session2 still holds version 0
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> taskRepository.saveAndFlush(session2));
}
```
Use `saveAndFlush` (not plain `save`) in the test so the `UPDATE` is actually sent to the DB synchronously and the exception surfaces within the test method rather than being deferred to a later flush/commit boundary.

**Confidence: HIGH** for the whole `@Version` → `ObjectOptimisticLockingFailureException` → 409 chain; this is one of the most consistently documented patterns in the Spring/JPA ecosystem, cross-checked across Baeldung, multiple independent blog sources, and confirmed directly against this codebase's actual `GlobalExceptionHandler.java` source (read directly, not inferred).

---

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| Naive triple `JOIN FETCH board.column.task.subtasks` in one JPQL query | Throws `MultipleBagFetchException` immediately — two `List` associations can't both be join-fetched in one query | JOIN FETCH the first level only; `@BatchSize` the rest |
| Converting all collections to `Set` to dodge `MultipleBagFetchException` | Fixes the exception but produces a silent Cartesian product at the DB level, and loses reliable column/task ordering (no `@OrderColumn` today) | Keep `List`, use JOIN FETCH (level 1) + `@BatchSize` (levels 2–3) |
| `@EntityGraph` spanning two `List` levels as a "fix" for bag-fetch | Compiles to the same JOIN FETCH SQL under the hood — hits the identical exception, it's not a real workaround | Same JOIN FETCH + `@BatchSize` combination |
| Pessimistic locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) for the drag/reorder conflict scenario | Epic spec explicitly asks for optimistic locking; pessimistic locks hold DB row locks for the transaction duration, which is unnecessary friction for a low-contention, human-interaction-paced conflict (drag-and-drop), and doesn't match the "detect and 409, let the client retry" UX the spec wants | `@Version` + catch `ObjectOptimisticLockingFailureException` → 409 |
| Mapping `ObjectOptimisticLockingFailureException` (or its parent) to `HttpStatus.LOCKED` (423) | Current codebase behavior; 423 is WebDAV's "resource is locked," semantically wrong for a stale-write conflict detected after the fact, and doesn't match the epic's explicit 409 requirement | `HttpStatus.CONFLICT` (409) |
| Adding `@Version` to `BaseEntity` (all entities) | Broader blast radius than the epic asks for; risks unexpected version-check failures on `UserEntity`/`BoardEntity`/`SubtaskEntity` update paths not designed around version semantics | Add `@Version` directly to `TaskEntity` and `ColumnEntity` only, per spec |
| MULTISET-based collection fetching (Hibernate 6.5+ native or via Blaze Persistence/jOOQ) for the `/full` endpoint | Real and modern, but adds either a Hibernate-version floor to verify or a new third-party dependency, for a problem `@BatchSize` already solves with what's on the classpath today | JOIN FETCH + `@BatchSize`; mention MULTISET as the "next generation" alternative in the interview narrative only |

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|------------------|-------|
| Spring Boot 3.5.0 | Hibernate ORM 6.x (exact patch pinned by Boot's BOM) | Do not override `hibernate.version` manually; run `./gradlew dependencies` to confirm the resolved version before phase planning if precision matters for docs |
| `@BatchSize` (org.hibernate.annotations) | Hibernate 6.x, unchanged API surface from 5.x | No migration concerns; annotation package is stable across the 5→6 major version |
| `@Version` (jakarta.persistence, not javax.persistence) | Spring Boot 3.x mandates the Jakarta EE 9+ namespace | Codebase already on `jakarta.persistence.*` imports (confirmed in `SubtaskEntity.java`, `TaskEntity.java`) — no namespace migration needed, just add the annotation |
| `ObjectOptimisticLockingFailureException` (org.springframework.orm) | Spring Data JPA (any 3.x line paired with Boot 3.5.0) | Already transitively available; no new dependency |

## Sources

- [vladmihalcea.com/hibernate-multiplebagfetchexception](https://vladmihalcea.com/hibernate-multiplebagfetchexception/) — HIGH confidence (Hibernate core team alumnus, canonical source on this exact exception; fetched directly and cross-checked)
- [baeldung.com/java-hibernate-multiplebagfetchexception](https://www.baeldung.com/java-hibernate-multiplebagfetchexception) — HIGH confidence (well-established, cross-checked against Vlad Mihalcea's post, consistent)
- [thorben-janssen.com/fix-multiplebagfetchexception-hibernate](https://thorben-janssen.com/fix-multiplebagfetchexception-hibernate/) — HIGH confidence (Hibernate-focused technical author, fetched directly, consistent with above)
- [thorben-janssen.com/hibernate-tips-how-to-fetch-associations-in-batches](https://thorben-janssen.com/hibernate-tips-how-to-fetch-associations-in-batches/) — MEDIUM confidence (single-level example only; multi-level batching behavior extrapolated from general `@BatchSize`/`default_batch_fetch_size` semantics, not independently confirmed for 3-level hierarchies in this specific source)
- [vladmihalcea.com/how-to-increment-the-parent-entity-version-whenever-a-child-entity-gets-modified-with-jpa-and-hibernate](https://vladmihalcea.com/how-to-increment-the-parent-entity-version-whenever-a-child-entity-gets-modified-with-jpa-and-hibernate/) — HIGH confidence (confirms non-owning-side version-bump behavior, directly relevant to this schema's `mappedBy` structure)
- [Baeldung — JPA Optimistic Locking](https://www.baeldung.com/jpa-optimistic-locking) — referenced via search snippet (direct fetch blocked by 403); MEDIUM confidence, cross-checked against multiple other sources returning consistent claims (Long version field convention, exception wrapping chain, 409 recommendation)
- Direct source read: `C:/Dev/Repos/kanban-board-backend/src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java` — HIGH confidence (ground truth for current 423 mapping that needs to change)
- Direct source read: `TaskEntity.java`, `ColumnEntity.java`, `BoardEntity.java`, `SubtaskEntity.java`, `BaseEntity.java` — HIGH confidence (ground truth for entity structure, confirms all three parent-child collections are unordered `List` bags with no `@Version` yet)
- Spring Boot 3.5 Release Notes (GitHub wiki) — fetched, did not explicitly surface the pinned Hibernate patch version; flagged as MEDIUM/verify-before-use above

---
*Stack research for: JPA/Hibernate nested-aggregate fetching + optimistic locking (Epic 2 completion)*
*Researched: 2026-07-31*
