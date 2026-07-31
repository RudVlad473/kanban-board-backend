# Pitfalls Research

**Domain:** JPA/Hibernate depth work — nested aggregate read endpoint + retrofitted optimistic locking (Spring Boot 3.5.0 / Hibernate 6 / Spring Data JPA)
**Researched:** 2026-07-31
**Confidence:** HIGH (codebase-verified pitfalls, cross-checked against documented Hibernate behavior) / MEDIUM (general Hibernate ecosystem gotchas not yet reproduced locally)

## Critical Pitfalls

### Pitfall 1: Triple `JOIN FETCH` across two `List` collections multiplies rows (Cartesian product)

**What goes wrong:**
A single JPQL query like `SELECT b FROM BoardEntity b JOIN FETCH b.columns c JOIN FETCH c.tasks t JOIN FETCH t.subtasks s WHERE b.id = :id` produces one flat SQL result set where every row is the join of one column × one task × one subtask. A board with 5 columns, 10 tasks/column, 4 subtasks/task returns 2,000 SQL rows to materialize what is logically 1 board. Hibernate does de-duplicate the returned *object graph* in memory (via `DISTINCT` + result-set processing, or automatically for bag/list fetches since Hibernate 5.2+ with `PASS_DISTINCT_THROUGH`), but the JDBC round trip still transmits the full multiplied row set, and de-dup itself costs CPU. This gets dramatically worse than a "two `List`s" case here because there are actually **three** nested `List` collections (columns, tasks, subtasks) — the multiplication compounds at each level (columns × tasks × subtasks), not just doubles.

**Why it happens:**
Fetching two-or-more `List`-typed (bag) associations in one query is a many-to-many join at the SQL level even though the object model is a tree. Developers reach for `JOIN FETCH` because it "avoids N+1," not realizing it trades N+1 round trips for one enormous, quadratically-multiplied result set — often worse for both memory and total bytes transferred than the N+1 it replaces, once collection sizes are non-trivial.

**How to avoid:**
Given this schema has three levels of `List` under Board, do **not** attempt a single triple `JOIN FETCH`. Use one of:
- **`@BatchSize` on each collection** (`@OneToMany(mappedBy=...) @BatchSize(size=N)` on `BoardEntity.column`, `ColumnEntity.task`, `TaskEntity.subtasks`) — Hibernate issues one query for the board, then batches `WHERE column_id IN (...)` for all columns' tasks in groups of N, then batches subtasks the same way. Bounded, predictable query count (roughly 1 + ceil(columns/N) + ceil(tasks/N)), no row multiplication.
- **Two-queries-and-stitch in Java**: fetch board+columns with one `JOIN FETCH`, fetch all tasks+subtasks for those column IDs with a second `JOIN FETCH` (this one is safe — tasks:subtasks is only *one* bag, not two), then stitch task lists onto columns in the DTO mapper. This trades a bit of manual assembly code for fully predictable, non-multiplied queries.
- Either approach is defensible; the PLAN.md for this epic already flags this decision as deferred to phase planning — pick one and document why (this codebase's boards are small/personal-project scale, so `@BatchSize` is likely simpler to implement and sufficiently performant; two-query-stitch is more explicit/interview-legible if you want to show you understand exactly what SQL runs).
- **Never** write `JOIN FETCH board.columns JOIN FETCH columns.tasks JOIN FETCH tasks.subtasks` as a single query — this is the textbook Cartesian-product mistake this project explicitly calls out.

**Warning signs:**
- Query-count test passes (looks like "1 query!") but row-count/response-size blows up disproportionately to board size — a board with 5 columns/10 tasks each returns thousands of JDBC rows for one logical board.
- Enable `hibernate.generate_statistics` + log actual SQL (`logging.level.org.hibernate.SQL=debug`) and eyeball the row count returned, not just the query count. Query count alone hides this pitfall.
- Response time or memory usage degrades much faster than linearly as boards grow (columns × tasks × subtasks growth is combinatorial, not additive).

**Phase to address:**
Full-board read endpoint phase — this must be settled during implementation of `GET /boards/{boardId}/full`, not discovered later. Add a test with a board sized like "5 columns, 8 tasks/column, 3 subtasks/task" (documented before/after row-count or query-count, same spirit as the existing `QueryCountTest` pattern) so the chosen strategy is provably bounded, not just "looks right in the happy path with 1 column."

---

### Pitfall 2: `MultipleBagFetchException` when eagerly joining more than one `List` in the same JPQL/Criteria query

**What goes wrong:**
Hibernate throws `org.hibernate.loader.MultipleBagFetchException: cannot simultaneously fetch multiple bags` at query-parse time (not runtime data corruption — this one fails loud and immediately) the moment a single query tries `JOIN FETCH` on two sibling-or-nested `List` (bag) associations. Given `BoardEntity.column` is `List<ColumnEntity>` and `ColumnEntity.task` is `List<TaskEntity>`, any attempt to fetch both in one query (`... JOIN FETCH b.column c JOIN FETCH c.task ...`) throws this exception outright.

**Why it happens:**
A `List` without an explicit `@OrderColumn` (this codebase's collections are plain `List`, unordered/unindexed) is treated by Hibernate as a "bag" — an unordered collection with no way to disambiguate which multiplied row belongs to which parent when two bags are fetched simultaneously in the same SQL join. Hibernate refuses to guess.

**How to avoid:**
This is effectively the same root cause as Pitfall 1 and is resolved the same way: never JOIN FETCH two `List` associations in one query. If `@BatchSize` is chosen, this exception never triggers (batch loading uses separate `IN` queries, not joined bags). If two-query-stitch is chosen, each individual query only ever fetches at most one bag level deep (board→columns is one bag; tasks→subtasks in the second query is one bag), so it's also safe. Converting the collections to `Set` sidesteps `MultipleBagFetchException` specifically, but does **not** solve the Cartesian-product row multiplication from Pitfall 1 — don't treat `Set` conversion alone as the fix.

**Warning signs:**
This fails at Hibernate query-plan-parse time, typically the very first time the query executes (including in a quick manual test), not silently — so it's more "annoying/blocking" than "hidden," but worth naming explicitly since it's a very common first attempt on this exact schema shape (Board→Columns→Tasks, both `List`).

**Phase to address:**
Full-board read endpoint phase. If this exception appears during implementation, it's a strong signal the naive single-query approach was attempted — switch to `@BatchSize` or two-query-stitch rather than trying to work around it with `Set` alone.

---

### Pitfall 3: N+1 still happens despite `JOIN FETCH` — because the JOIN FETCH only covers part of the graph

**What goes wrong:**
A developer fixes the Cartesian-product problem by fetching only the *first* level with `JOIN FETCH` (e.g., `board JOIN FETCH board.column`), declares victory because "I used JOIN FETCH," but then the DTO mapper (or a `for` loop rendering the response) touches `column.getTask()` — an association that was never fetched — triggering one lazy-load query per column. This is N+1 with a JOIN FETCH physically present in the code, just not covering the full path that gets accessed.

**Why it happens:**
`JOIN FETCH` only eagerly loads the exact association path named in the query. Any relationship walked afterward that wasn't part of that path reverts to whatever the mapping's default fetch behavior is (LAZY for `@OneToMany` by default in this codebase — note `ColumnEntity.task` and `BoardEntity.column` have no explicit `fetch` override, so they're LAZY unless the endpoint's specific query says otherwise). This is exactly why "N+1 despite JOIN FETCH" is possible and is one of the most common false-fix patterns.

**How to avoid:**
- Verify with an actual query-count assertion (the codebase already has the pattern via `Statistics.getPrepareStatementCount()` in `AbstractAppTest`) covering the *entire* DTO-building path, not just the repository call. The existing `QueryCountTest` convention should be extended to the full-board endpoint specifically because this bug is invisible if you only check "did my JOIN FETCH query run once" — you must check total queries for the whole request.
- Whichever strategy is chosen (batch-size or two-query-stitch), write the query-count test against the *controller* or *service* method that returns the full DTO tree, not just the repository method, so lazy-triggered queries from mapper code are caught.

**Warning signs:**
Repository-level test shows 1 query; controller/integration-level test (or manual SQL log inspection) shows many more. Any discrepancy between "the fetch query I wrote" and "the queries Hibernate actually ran for this request" is the signature of this pitfall.

**Phase to address:**
Full-board read endpoint phase — write the query-count regression test at the same altitude as the actual HTTP response assembly (service method returning the nested DTO, or full E2E), consistent with how `OwnershipVerifierServiceTest.QueryCountTest` was scoped for Finding 1.

---

### Pitfall 4: Memory bloat from loading the entire nested graph in one request, un-paginated

**What goes wrong:**
Even with a correctly-bounded query strategy (Pitfall 1 solved), the full-board endpoint by design loads every column, every task, every subtask into memory and serializes them all in one response. For a personal-project scale app this is fine, but it's worth being explicit that this endpoint has no pagination/limits and is a full-materialization endpoint by design — not an oversight, a deliberate scope decision.

**Why it happens:**
"Full board" implies "everything," and the whole point of the endpoint is to replace N+1 client round trips with one call — so some amount of full materialization is inherent to the feature, not a bug. The pitfall is only in *not stating that as a bounded, deliberate tradeoff* and instead discovering it as a surprise under load later.

**How to avoid:**
Document the assumption explicitly (e.g., in the endpoint's javadoc or the PR description): this endpoint is designed for "initial board render" at personal/small-team scale, not for boards with thousands of tasks. If board size ever needs to scale beyond that, the mitigation is column-level or task-level pagination on top of this endpoint, not a change to the fetch strategy itself. No code change needed now — just don't let silence here be read later as "we didn't think about it."

**Warning signs:**
N/A for current scope — this becomes a real pitfall only if/when board sizes grow far beyond personal-project scale. Flag as a known, accepted limitation rather than something to solve now.

**Phase to address:**
Full-board read endpoint phase — one sentence in the PR/README describing the scale assumption is sufficient; no additional engineering needed at this project's scale.

---

### Pitfall 5: Bulk JPQL `@Modifying` deletes silently skip `@Version` checks entirely — direct interaction with existing code

**What goes wrong:**
This is the most important pitfall for this specific milestone, because it's not hypothetical — this codebase **already has** exactly this code pattern from the just-completed part of Epic 2. `TaskService.deleteAllByColumn` calls `taskRepository.deleteAllByIdInBatch(taskIds)` (a Spring Data JPA bulk-delete-by-ID-list method) and `subtaskService.deleteAllByTaskIds` calls the explicit `@Modifying @Query("delete from SubtaskEntity s where s.task.id in :taskIds")` bulk JPQL delete in `SubtaskRepository.deleteAllByTaskIdIn`. **Both of these issue a raw SQL `DELETE ... WHERE id IN (...)` (or equivalent) that does not read, does not check, and does not care about the row's `version` column at all.** Once `@Version` is added to `TaskEntity` (per the plan), these two existing bulk-delete call sites will delete tasks even if another transaction concurrently modified that exact task (changing its version) a moment earlier — the optimistic lock is completely bypassed for anything going through a bulk statement, by design of how bulk JPQL/`deleteAllByIdInBatch` works in Hibernate: `@Version` checking is a feature of the entity-level `UPDATE`/`DELETE` Hibernate generates when flushing a *managed, loaded* entity, not a database constraint — bulk statements never load entities, so there's no version to compare against.

**Why it happens:**
Developers (correctly) fixed N+1 by converting per-entity loop-and-delete into bulk JPQL/`deleteAllByIdInBatch` — the right call for that problem. But adding `@Version` afterward, to *different code paths* (the interactive create/update/reorder endpoints), creates a false sense that "the entity is now versioned everywhere" when in fact the bulk-delete paths were never touched and structurally cannot be, short of rewriting them back to per-entity loads (which would reintroduce the N+1 problem this milestone's predecessor phase just fixed).

**How to avoid:**
- **Explicitly document this gap rather than silently accepting it.** In `TaskService.deleteAllByColumn`'s existing javadoc (which already documents the flush/clear gotcha) add a note that bulk deletes bypass `@Version` checking by design, and that this is an accepted tradeoff because deletes are idempotent-in-intent (the task is gone either way) — unlike concurrent *updates*, a concurrent delete "racing" a version-mismatched update doesn't corrupt data, it just means the update's effects on a deleted row are silently discarded, which is arguably the correct behavior for a delete-wins race.
- Do **not** attempt to retrofit version-checking onto the bulk delete paths (e.g., adding `AND version = :expectedVersion` per task) — that would require row-by-row version tracking that defeats the purpose of the batch operation and doesn't fit a bulk multi-row delete's semantics anyway (which version would you check against, if deleting 8 tasks at once?).
- Scope `@Version`/optimistic-lock guarantees explicitly to the single-entity update paths this milestone is actually adding it for: `TaskService.updateById` (title/description edits) and whatever new reorder/move endpoint updates `TaskEntity.column`/position. The bulk-delete paths remain out of scope for the optimistic-lock guarantee, and that should be a stated decision, not an oversight discovered by a reviewer.
- Verify with a targeted test: create a task, bulk-delete its column (triggering `deleteAllByColumn`), confirm it does *not* throw `ObjectOptimisticLockingFailureException` even if the task's version was bumped by a separate transaction first — proving the bypass is real and understood, not just assumed.

**Warning signs:**
- Any reviewer question along the lines of "does optimistic locking also protect deletes?" — if the honest answer requires explaining the bulk-JPQL bypass, that's this pitfall surfacing. Better to preempt it in the PR description.
- If a future feature ever needs "don't delete if it was just modified" semantics (unlikely for a kanban board, but worth naming), the current bulk-delete implementation cannot provide that guarantee at all.

**Phase to address:**
Optimistic locking phase — this must be called out explicitly in that phase's plan/PR, given the question quality gate for this research explicitly asks whether bulk deletes on a versioned entity's parent skip version checks. Answer: **yes, completely, by design of how bulk JPQL/`deleteAllByIdInBatch` work** — there is no Hibernate configuration flag that makes bulk statements respect `@Version`; it would need to be hand-rolled per statement (e.g., adding `AND version = ?` to the JPQL and checking affected-row-count), which is disproportionate for a delete flow like this and is not recommended here.

---

### Pitfall 6: `ColumnRepository.deleteAllByBoardId` is still a *derived* delete method — same class of pre-existing gotcha, now at risk of a subtler version-related re-occurrence

**What goes wrong:**
`ColumnService.deleteAllByBoardId` calls `taskService.deleteAllByColumn(column)` per column (already fixed, bulk), but then calls `columnRepository.deleteAllByBoardId(pair.getSecond().getId())` — and `ColumnRepository.deleteAllByBoardId` is a Spring Data JPA **derived** method (`void deleteAllByBoardId(String boardId)`), not an explicit `@Modifying @Query`. Per the STATUS.md gotcha already hit once in this exact codebase, derived `deleteAllByXIn`/`deleteAllByX` methods do fetch-then-`remove()`-per-entity, not a single bulk SQL statement. Once `@Version` is added to `ColumnEntity`, this derived-delete path *will* load each column as a managed entity and *will* go through Hibernate's normal versioned-delete check (unlike the bulk JPQL paths in Pitfall 5) — meaning this one delete path behaves inconsistently with the sibling task-delete path: one honors `@Version`, the other doesn't, and that asymmetry is easy to lose track of if not written down now.

**Why it happens:**
The prior fix (Finding 2) only touched the task-deletion loop; the column-deletion call at the end of `deleteAllByBoardId` was left as-is because it wasn't the N+1 bottleneck (it's a single call, not a loop). But "not an N+1 problem" and "behaves consistently with the rest of the delete chain once @Version exists" are different questions, and this milestone changes the second one without anyone necessarily re-examining this line.

**How to avoid:**
- When adding `@Version` to `ColumnEntity`, explicitly re-test `ColumnService.deleteAllByBoardId` end-to-end (board with several columns, each with tasks) to confirm behavior is still correct: since it's fetch-then-remove per column, a concurrent modification to a column between the `findAllByBoardId` fetch and the derived delete's internal per-entity delete *could* now throw `ObjectOptimisticLockingFailureException` mid-batch-delete (an interaction that didn't exist before `@Version` existed). Decide whether that's desired (probably yes — surfacing a conflict during a cascading board delete is arguably correct) or whether it needs to be caught/handled specially so a whole-board delete doesn't 500/409 because of an unrelated single-column race.
- Document the asymmetry explicitly in code comments: task-bulk-delete bypasses `@Version` (Pitfall 5); column-derived-delete does not. This prevents a future reader from assuming both behave the same way.

**Warning signs:**
A board-delete integration test that never exercises concurrent modification won't reveal this — it only surfaces under genuine concurrent load or a deliberately-crafted race test. Treat its absence from the test suite as an open gap, not a sign it's fine.

**Phase to address:**
Optimistic locking phase — flag as a specific test case ("delete board while one of its columns is concurrently updated elsewhere") even if the codebase decides not to build extra handling for it; at minimum, understand and document what currently happens.

---

### Pitfall 7: `@Data`/`@EqualsAndHashCode` on the versioned entities includes the `version` field in `equals`/`hashCode`, breaking Lombok-Builder identity and Set-membership semantics

**What goes wrong:**
`ColumnEntity` uses Lombok `@Data` (which generates `equals`/`hashCode` over **all** fields unless excluded) plus `@EqualsAndHashCode(callSuper = false)`. Once `@Version private Long version;` is added, it becomes part of that generated `equals`/`hashCode` by default. Two `ColumnEntity` objects representing the "same" row before and after a save (version 0 vs version 1) will now compare as **not equal**, and their hash codes will differ — silently breaking anything relying on entity equality/hash stability across a save (e.g., if a `ColumnEntity` is ever put in a `Set`, used as a `Map` key, or compared with `.equals()` in a test or in `OwnershipVerifierService`'s `.equals()` ownership check pattern, e.g. `board.get().getUser().getId().equals(...)` — that one's on `id`, so it's safe, but any *entity*-level `.equals()` elsewhere is now at risk). `TaskEntity` currently has `@EqualsAndHashCode` commented out entirely (`// @EqualsAndHashCode(callSuper = false)`), so it falls back to Lombok's absence-of-annotation behavior — actually **no**, without any `@EqualsAndHashCode`, `@Getter`/`@Setter`-only classes fall back to default `Object.equals()` (identity), which is a *different* but also worth-verifying situation once `@Version` is added.

**Why it happens:**
`@Version` is "just another field" from Lombok's perspective — it has no special-casing to auto-exclude version/audit columns from generated `equals`/`hashCode`. This is a very common miss when retrofitting `@Version` (or `@CreatedDate`/`@LastModifiedDate`) onto entities that already use blanket `@Data`/`@EqualsAndHashCode`.

**How to avoid:**
- On `ColumnEntity`, add `@EqualsAndHashCode.Exclude` on the new `version` field (and ideally on all non-ID fields — best practice for JPA entities is `equals`/`hashCode` based on `id` alone, but at minimum the version field must be excluded to avoid this specific new bug).
- On `TaskEntity`, since `@EqualsAndHashCode` is already commented out, verify explicitly whether `.equals()` is called anywhere expecting value semantics (grep for `.equals(` on `TaskEntity` instances, and check any test using `assertEquals(taskA, taskB)` on full entities rather than DTOs) — if so, either add a proper `id`-only `@EqualsAndHashCode` or confirm identity-based equality is fine for those call sites.
- General best practice being retrofitted here: JPA entity `equals`/`hashCode` should be based on the (immutable) ID field only, never on mutable fields like `version`, and never using Lombok's `@Data` default of "all fields" on an `@Entity`. This is a good moment to fix this project-wide while `@Version` forces the question anyway.

**Warning signs:**
Existing tests that compare full entity objects (not DTOs) start failing intermittently after adding `@Version`, or `Set<ColumnEntity>`/`Set<TaskEntity>` membership behaves inconsistently across a save/reload cycle.

**Phase to address:**
Optimistic locking phase — audit `@EqualsAndHashCode` on `ColumnEntity` and `TaskEntity` as part of adding `@Version`, not as an afterthought.

---

### Pitfall 8: The existing `GlobalExceptionHandler` already has an `OptimisticLockingFailureException` handler — but it maps to `423 LOCKED`, not the `409 Conflict` this epic's spec requires

**What goes wrong:**
`GlobalExceptionHandler.handleOptimisticLockingFailure` already exists in this codebase and catches `OptimisticLockingFailureException` (the Spring Data superclass of `ObjectOptimisticLockingFailureException`), returning `HttpStatus.LOCKED` (423). The epic spec (`02-n-plus-one-optimistic-locking.md`) and `PROJECT.md` both explicitly call for a **409** mapping. If this milestone's implementation assumes the mapping doesn't exist yet and adds a second handler for `ObjectOptimisticLockingFailureException`, Spring's `@ExceptionHandler` resolution will pick the **more specific** exception type handler correctly — but only if both aren't ambiguously matched; if instead the existing 423 handler is left untouched and a new handler is added for the subtype, there will be two handlers for overlapping exception hierarchies in the same `@ControllerAdvice`, which is at best confusing and at worst throws `IllegalStateException: Ambiguous @ExceptionHandler` at startup if Spring can't disambiguate (it generally can, by most-specific-type, but it's a code smell to have both).

**Why it happens:**
This handler was presumably added speculatively/defensively during earlier work (possibly anticipating future locking work) without being wired to an actual locking mechanism yet (there is no `@Version` field anywhere in the entities today, so this handler has never actually fired in production or tests) and without the status code being reconciled against what the modernization plan actually specifies.

**How to avoid:**
- Change the existing handler's status from `HttpStatus.LOCKED` to `HttpStatus.CONFLICT` (409) to match the spec, rather than adding a duplicate/overlapping handler.
- Since `ObjectOptimisticLockingFailureException extends ObjectOptimisticLockingFailureException` (which itself extends `OptimisticLockingFailureException`), a single handler on the **superclass** `OptimisticLockingFailureException` is sufficient and already in place — no need for a second, more-specific handler unless the plan wants to differentiate exception subtypes in the response body (unlikely necessary here).
- Add the test the epic spec calls for (two "sessions" load the same task, both update, second save throws) as an integration/E2E test asserting the **actual HTTP status code returned is 409**, not just that the exception type is right in a unit test — this closes the loop on whether the existing handler is correctly wired end-to-end.

**Warning signs:**
A locking test written only at the service/repository level (asserting `ObjectOptimisticLockingFailureException` is thrown) will pass even if the HTTP-level mapping is still wrong (423 instead of 409) — the gap only shows up in a controller/E2E-level test that checks status code.

**Phase to address:**
Optimistic locking phase — fix the status code on the existing handler as part of this phase's work; add an E2E-level assertion on the 409 status code specifically, not just the exception type.

---

### Pitfall 9: Detached-entity version mismatches — `updateById`'s load-then-mutate-then-save pattern is *safe* by construction here, but easy to break by "optimizing" it later

**What goes wrong (general Hibernate gotcha, MEDIUM confidence — not yet a bug in this codebase, but a common regression path):**
The classic "optimistic locking doesn't fire" bug happens when code builds a *new*, detached entity instance carrying a stale or default version value (e.g., constructing a `TaskEntity` from an incoming DTO with `id` set but `version` left at Java default `0`/`null`) and calls `repository.save()` on it directly, instead of loading the managed entity first and mutating it. Hibernate then either overwrites with a stale version comparison (silently succeeding when it should conflict) or throws a confusing `StaleObjectStateException`-derived error at the wrong point, because a "detached" entity's version isn't validated against the current DB row the way a managed entity's is.

**Why it happens:**
`TaskService.updateById` in this codebase already does the *correct* pattern: `findById` (loads a managed entity within `@Transactional`), mutates fields on that managed instance, then calls `taskRepository.save(task)` — since `task` is still the same managed instance from the current persistence context, `@Version` will work correctly and increment/check automatically at flush time. This pattern is safe. The pitfall is a **regression risk**: if a future refactor (e.g., introducing MapStruct entity-mapping helpers, or accepting a client-supplied `version` field in `UpdateTaskRequestDTO` for optimistic-concurrency-aware clients) switches to constructing a fresh entity from the DTO and calling `save()` on that detached object instead of mutating the loaded one, the version check silently stops working as expected.

**How to avoid:**
- Keep the existing load-mutate-save pattern in `updateById` — do not "simplify" it into a mapper-constructs-entity-then-saves pattern without deliberately deciding how the client-supplied version (if any) should be validated against the loaded entity's current version.
- If the reorder/move endpoint accepts a `version` from the client (to let the client explicitly assert "I'm updating the version I last saw"), the correct pattern is: load the managed entity, compare `dto.getVersion()` to `task.getVersion()` explicitly (or just let Hibernate's own version check at flush time handle it if the client-supplied version is set onto the managed entity before save — riskier and easON to get wrong) — document which of these two approaches is used, since both are legitimate but behave differently on mismatch (explicit check → your own exception/message; relying on Hibernate → generic `ObjectOptimisticLockingFailureException`).
- Do not accept a bare `version` field on the DTO and forward it into a brand-new detached entity that then gets merged/saved — that reintroduces the classic bug.

**Warning signs:**
Optimistic-lock test suite passes for "two sequential loads, two saves" scenarios but a client-driven concurrency test (client submits its last-known version, expecting a conflict if stale) doesn't behave as expected — that's the signature of a detached-entity version mismatch.

**Phase to address:**
Optimistic locking phase — confirm `updateById`'s existing load-mutate-save shape is preserved for `TaskEntity`/`ColumnEntity`, and if the reorder endpoint's DTO carries a version field, explicitly design and test the detached-vs-managed handling rather than assuming Hibernate "just handles it."

---

### Pitfall 10: `ObjectOptimisticLockingFailureException` is not thrown for every conflict path — bulk operations and `@Modifying` updates are silent gaps

**What goes wrong:**
Beyond the bulk-*delete* gap already covered in Pitfall 5, the same gap applies symmetrically to any bulk **update** written as `@Modifying @Query("update TaskEntity t set t.column = :column where t.id in :ids")`-style JPQL (none exist yet in this codebase, but a future "bulk move tasks to another column" feature is a plausible next step given the domain is drag-and-drop reordering). Any such bulk JPQL update bypasses the version check exactly like bulk deletes do — Hibernate only enforces `@Version` semantics when it generates the `UPDATE`/`DELETE` SQL itself from a managed entity's dirty-checking at flush time. A hand-written `@Modifying @Query` update statement is executed as-is, with no automatic `AND version = ?` clause added and no version increment performed, even if the entity being updated has `@Version`.

**Why it happens:**
Developers reasonably assume "the entity has `@Version`, so all writes to it are protected" — but `@Version` is enforced by the ORM's own generated statements, not by a database-level trigger or constraint. Any write path that doesn't go through "load managed entity → mutate → let Hibernate flush" (i.e., anything using `@Modifying @Query`, `saveAll()` in some batch-optimized configurations, or native SQL) sidesteps it entirely.

**How to avoid:**
- For this milestone's actual scope (single-task/column update, single-task/column reorder), the standard `repository.save()` on a loaded managed entity is correct and does get full version protection — no gap here for the features actually being built now.
- Flag this explicitly as a constraint for **future** work: if a "bulk reorder" or "move multiple tasks between columns" endpoint is ever added using `@Modifying @Query` (the pattern this codebase already favors for bulk operations, per the just-completed Finding 2 fix), it will need either (a) an explicit `AND version = :expectedVersion` per-row clause plus an affected-rows check, which doesn't scale to multi-row bulk updates with different expected versions, or (b) accept that bulk reorder is a "last write wins" operation by design and document that tradeoff, or (c) fall back to per-entity loop-and-save for that specific operation if per-row conflict detection is actually required (re-accepting the N+1 cost for that one endpoint, as a deliberate tradeoff).
- Do not assume `ObjectOptimisticLockingFailureException` will be thrown "somewhere" just because `@Version` exists on the entity — it is only thrown when Hibernate itself detects a stale version during a flush of a managed entity it loaded and is updating/deleting via its own generated SQL.

**Warning signs:**
Any future PR that adds a `@Modifying @Query` write path (update or delete) to `TaskEntity`/`ColumnEntity` should trigger an explicit question: "does this need version protection, and if so, how, given bulk statements don't get it for free?"

**Phase to address:**
Optimistic locking phase, as a documented boundary/limitation (not something to build now) — and a flag for whoever later builds a bulk-reorder feature that this gap needs re-litigating at that point.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|--------------------|-----------------|------------------|
| `@BatchSize` instead of two-query-stitch for full-board fetch | Less code, no manual Java-side stitching | Slightly less explicit/controllable query shape; batch size tuning is a magic number that needs revisiting if board sizes grow | Acceptable now — personal-project scale; revisit if boards ever have hundreds of tasks |
| Scoping `@Version` to `TaskEntity`/`ColumnEntity` only, not `BoardEntity`/`SubtaskEntity` | Matches the actual concurrent-edit scenario (drag-and-drop reorder/move) without over-engineering | Board renames or subtask completion toggles remain unprotected against concurrent overwrite | Acceptable — those aren't the stated concurrency scenario (drag-and-drop), and expanding scope isn't part of this epic |
| Leaving bulk-delete paths (`deleteAllByColumn`, `deleteAllByTaskIdIn`) without version enforcement | Avoids reintroducing N+1 by forcing per-entity loads just to check version | Deletes can silently discard a concurrent update's effects (delete-wins race) | Acceptable — document as an explicit, accepted tradeoff rather than silently leaving it undiscussed |
| Not paginating the full-board endpoint | Simpler implementation, matches "single call, one render" purpose | Would need revisiting if board sizes grow to hundreds/thousands of tasks | Acceptable at current scale; note as a known future limitation |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|------------------|--------------------|
| Spring Data derived `deleteAllByXIn`/`deleteAllByX` methods | Assuming they generate a single bulk `DELETE` statement | They generate fetch-then-`remove()`-per-entity; use explicit `@Modifying @Query` for true bulk semantics, as already done for `SubtaskRepository.deleteAllByTaskIdIn` — but note `ColumnRepository.deleteAllByBoardId` is *still* derived (Pitfall 6) |
| Hibernate `@Version` + Lombok `@Data`/`@EqualsAndHashCode` | Letting Lombok include `version` in generated `equals`/`hashCode` | Explicitly exclude `version` (and ideally all non-ID fields) from `equals`/`hashCode` on `@Entity` classes |
| Hibernate `@Version` + bulk JPQL/`deleteAllByIdInBatch` | Assuming `@Version` protects bulk write paths automatically | It doesn't — bulk statements bypass version checks entirely; document the gap, don't try to patch it with per-row version clauses unless truly required |
| `JOIN FETCH` across nested `List` collections | Writing one query that fetches 2+ `List` associations, hitting `MultipleBagFetchException` or silent row multiplication | Use `@BatchSize` per collection, or split into multiple queries (each touching at most one bag) and stitch in Java |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Triple `JOIN FETCH` for full-board endpoint | Response time/size scales combinatorially (columns × tasks × subtasks) rather than linearly with board size | `@BatchSize` or two-query-stitch, verified with a query-count *and* row-count test at realistic board size | Breaks almost immediately — even a modest board (5 columns × 8 tasks × 3 subtasks = 120 leaf rows) already produces thousands of joined SQL rows in the naive approach |
| Query-count test that only checks the repository call, not the full DTO-building path | "1 query" reported, but N+1 still happens in the mapper layer when it touches an un-fetched association | Scope query-count assertions to the full service/controller method, matching the existing `OwnershipVerifierServiceTest.QueryCountTest` convention | Breaks as soon as the DTO mapper accesses any association outside the fetch path chosen |
| Per-row `@Version` clause bolted onto a bulk `@Modifying` update for a future bulk-reorder feature | Doesn't scale — every row potentially needs a different expected version, which a single bulk statement can't express | Either accept bulk-write-is-last-write-wins, or fall back to per-entity loop-and-save for that one operation (accepting its N+1 cost as a deliberate tradeoff) | Breaks the moment a bulk write path needs true per-row optimistic-conflict detection |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Exposing `version` in `UpdateTaskRequestDTO` without validating it belongs to a task the caller actually owns | A malicious client could probe version numbers to infer update frequency/activity on boards it doesn't own | Version comparison must happen *after* `OwnershipVerifierService` ownership check, on the already-verified entity — never let a client-supplied version bypass ownership verification |
| Returning full entity objects (not DTOs) from the full-board endpoint for "convenience" | Leaks internal fields (e.g., future `version`, or `UserEntity` details nested via `BoardEntity.user`) not meant for the client | Keep the existing flat-DTO convention for the response even though the *fetch* strategy is now nested — build a nested *DTO* tree, not a serialized entity graph (`PROJECT.md` already flags this as a deliberate departure worth justifying) |

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-------------------|
| Returning a generic 409/423 with no indication of *what* changed underneath the conflicting request | User/client has to blindly retry or re-fetch with no context on what was different | Response body for the optimistic-lock conflict should at minimum state which resource conflicted (task/column id) so the client can decide whether to re-fetch just that resource or the whole board |
| Full-board endpoint used as the *only* way to view a board, forcing a full reload after every small edit | Wasteful and slow for single-field edits (e.g., toggling one subtask) | Keep existing granular endpoints (single task/column/subtask CRUD) alongside the new full-board endpoint — the full endpoint is for initial render, not for every mutation round-trip |

## "Looks Done But Isn't" Checklist

- [ ] **Full-board endpoint:** Query-count test exists, but does a *row-count* or response-size test also exist for a realistically-sized board (not just 1 column/1 task)? Query count alone hides Cartesian-product blowup.
- [ ] **Full-board endpoint:** Does the query-count/row-count test cover the *whole* service/controller path (DTO mapping included), or just the repository method? A repository-only test hides N+1-despite-JOIN-FETCH (Pitfall 3).
- [ ] **Optimistic locking:** Is there a test that proves `ObjectOptimisticLockingFailureException` on a genuine two-session conflicting update — not just a unit test asserting the annotation exists?
- [ ] **Optimistic locking:** Does the E2E/integration-level test assert the actual HTTP status code (409) returned by `GlobalExceptionHandler`, not just the exception type at the service layer? The existing handler currently returns 423, not 409 — verify this was actually changed (Pitfall 8).
- [ ] **Optimistic locking:** Has `@EqualsAndHashCode` on `ColumnEntity` (currently `@Data`) and `TaskEntity` (currently commented out) been explicitly reviewed/fixed to exclude the new `version` field?
- [ ] **Optimistic locking:** Is it explicitly documented (not just tacitly true) that `TaskService.deleteAllByColumn`'s bulk JPQL delete and `SubtaskRepository.deleteAllByTaskIdIn` do NOT check or enforce `@Version`?
- [ ] **Optimistic locking:** Has `ColumnRepository.deleteAllByBoardId` (still a derived, non-bulk method) been re-tested after `@Version` is added to `ColumnEntity`, given it behaves differently from the sibling bulk task-delete path (Pitfall 6)?

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|-----------------|-------------------|
| Naive triple `JOIN FETCH` shipped, discovered via slow full-board responses | LOW | Swap to `@BatchSize` or two-query-stitch; no schema change needed, isolated to repository/service query code |
| `MultipleBagFetchException` hit during development | LOW | Immediate, loud failure at query-parse time — just restructure the query per Pitfall 1/2's guidance; no data risk |
| `@Version` added but `equals`/`hashCode` not excluded, causing subtle test/Set-membership bugs later | LOW-MEDIUM | Add `@EqualsAndHashCode.Exclude` on `version`; audit any code relying on entity equality across saves; re-run full test suite |
| Existing 423 status code left unfixed, discovered by a reviewer or a failing E2E assertion | LOW | One-line change in `GlobalExceptionHandler`; add the missing E2E status-code assertion |
| Bulk-delete bypass of `@Version` discovered late (e.g., in a security/code review) without prior documentation | LOW (technical) / MEDIUM (credibility) | Add the documentation and the explicit delete-during-concurrent-update test retroactively; explain it as an accepted, understood tradeoff rather than an oversight |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|--------------------|-----------------|
| Cartesian-product blowup from triple JOIN FETCH | Full-board read endpoint phase | Row-count/response-size test at realistic board size, not just query count |
| `MultipleBagFetchException` | Full-board read endpoint phase | Compiles/runs without this exception; code review confirms no query fetches 2+ `List`s at once |
| N+1 despite JOIN FETCH (partial fetch path) | Full-board read endpoint phase | Query-count test scoped to full service/controller path, not just repository |
| Memory bloat / no pagination | Full-board read endpoint phase | One-line documented assumption in PR description; no code required |
| Bulk delete bypasses `@Version` | Optimistic locking phase | Explicit test: bulk-delete a column/task while version was concurrently bumped elsewhere; confirm no exception thrown, document why |
| `ColumnRepository.deleteAllByBoardId` derived-delete asymmetry | Optimistic locking phase | Test board delete under concurrent column modification; document actual behavior |
| Lombok `@EqualsAndHashCode` including `version` | Optimistic locking phase | Code review of `ColumnEntity`/`TaskEntity` annotations; add `@EqualsAndHashCode.Exclude` on `version` |
| Existing handler maps to 423 not 409 | Optimistic locking phase | E2E test asserting HTTP 409 specifically |
| Detached-entity version mismatch regression risk | Optimistic locking phase | Preserve load-mutate-save pattern in `updateById`; document if/how client-supplied version is handled |
| `ObjectOptimisticLockingFailureException` gaps in future bulk updates | Optimistic locking phase (documented boundary only) | Written note in code/PR scoping version protection to single-entity save paths only |

## Sources

- Codebase-verified (HIGH confidence): `src/main/java/com/vrudenko/kanban_board/entity/{BoardEntity,ColumnEntity,TaskEntity,SubtaskEntity,BaseEntity}.java`, `src/main/java/com/vrudenko/kanban_board/service/{TaskService,ColumnService,OwnershipVerifierService}.java`, `src/main/java/com/vrudenko/kanban_board/repository/{SubtaskRepository,TaskRepository,ColumnRepository}.java`, `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java`
- Project planning docs (HIGH confidence, primary source for this exact milestone's prior gotchas): `.planning/PROJECT.md`, `.planning/codebase/CONCERNS.md`, `docs/plans/backend-modernization/02-n-plus-one-optimistic-locking.md`, `docs/plans/backend-modernization/STATUS.md`
- General Hibernate/Spring Data JPA ecosystem knowledge (MEDIUM confidence — well-established, widely-documented behavior, not independently re-verified against Hibernate 6.x source for this project): `MultipleBagFetchException` semantics, bag vs. `Set`/`@OrderColumn` fetch behavior, `@BatchSize` batch-fetch mechanics, `@Version`/optimistic-locking enforcement being scoped to ORM-generated flush statements (not bulk/native SQL), Lombok `@Data`/`@EqualsAndHashCode` field-inclusion defaults.

---
*Pitfalls research for: JPA/Hibernate nested-aggregate reads + retrofitted optimistic locking (Spring Boot 3.5.0 kanban board backend)*
*Researched: 2026-07-31*
