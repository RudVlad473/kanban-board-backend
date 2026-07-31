# Status

Tracker for [the backend modernization plan](README.md). Update as epics are started/finished.

- [ ] Epic 1 — Kafka + event-driven activity feed
- [ ] Epic 2 — N+1 fix + optimistic locking
- [ ] Epic 3 — Flyway migrations + OpenAPI polish
- [ ] Epic 4 — Redis (cache + rate limit)
- [ ] Epic 5 — Testcontainers, drop H2
- [ ] Epic 6 — Observability (Actuator + Micrometer + Prometheus)
- [ ] Epic 7 — Kubernetes, local only (stretch)

## Notes / decisions log

- **2026-07-31 — Epic 2, Findings 1 & 2 (query-count work) done.** Added a `countQueries()` helper
  to `AbstractAppTest` (Hibernate `Statistics.getPrepareStatementCount()` — not
  `getQueryExecutionCount()`, which only counts HQL/JPQL and misses `findById()` calls).
  - **Finding 1 (chatty ownership chain) turned out to be a non-issue on measurement.** Because
    all the parent-side `@ManyToOne`s are default-EAGER and every level in
    `OwnershipVerifierService` is called via plain internal method calls (no proxy boundary), the
    whole chain runs inside one Hibernate session — Hibernate joins the EAGER chain into one SQL
    statement on the first `findById()`, and the redundant per-level `findById()` calls after that
    hit the L1 cache. Measured: **1 query** for `verifyOwnershipOfSubtask`. No code change made;
    added `OwnershipVerifierServiceTest.QueryCountTest` as a regression guard, and removed the two
    stale `// TODO: optimize verification logic...` comments since they no longer describe a real
    problem.
  - **Finding 2 (bulk delete N+1) was real and is fixed.** `TaskService.deleteAllByColumnId`
    measured at **33 queries for 8 tasks** before the fix (scales with task count) vs **4 queries
    regardless of task count** after. Fix: verify column ownership once, batch-delete subtasks via
    an explicit `@Modifying` bulk JPQL query (`SubtaskRepository.deleteAllByTaskIdIn`), then
    `taskRepository.deleteAllByIdInBatch(taskIds)`. Also applied the same pattern to
    `ColumnService.deleteAllByBoardId`, which had the identical bug one level up.
  - **Gotcha hit along the way:** Spring Data's *derived* `deleteAllByXIn` methods do
    fetch-then-`remove()`-per-entity, not a real bulk SQL statement — mixing that with a
    subsequent true bulk JPQL delete on a dependent table causes an FK violation (the entity
    removes haven't flushed yet). Fixed by making the subtask delete an explicit `@Modifying
    @Query` bulk delete. Bulk JPQL deletes also bypass the persistence context, which left stale
    managed entities behind and broke a later auto-flush in the same transaction (only reproduces
    when many aggregates are deleted in one transaction, e.g. the test suite's `deleteAll()`
    cleanup helper) — fixed with `entityManager.flush(); entityManager.clear();` after the batch
    deletes in `TaskService.deleteAllByColumn`.
  - **Pre-existing flaky test found, unrelated:** `SignupRequestDTOTest.whenDisplayNameIsMissing_thenNoViolation`
    fails intermittently on the *unmodified* base code too (confirmed via `git stash`) — likely
    `DataFactory`-generated random email/password occasionally violating `@Email`/`@Password`
    constraints. Not touched; flagged separately, not part of this epic.
  - Remaining in Epic 2: the `GET /boards/{boardId}/full` endpoint and optimistic locking — not
    started yet.
