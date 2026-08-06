# Status

Tracker for [the backend modernization plan](README.md). Update as epics are started/finished.

- [ ] Epic 1 — Kafka + event-driven activity feed
- [ ] Epic 2 — N+1 fix + optimistic locking
- [ ] Epic 3 — Flyway migrations + OpenAPI polish
- [ ] Epic 4 — Redis (cache + rate limit)
- [x] Epic 5 — Testcontainers, drop H2
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
- **2026-08-01 — Epic 2, optimistic locking manual DDL bridge delivered.** Added
  [`02-optimistic-locking-ddl.sql`](02-optimistic-locking-ddl.sql): `ALTER TABLE tasks ADD COLUMN
  IF NOT EXISTS version bigint NOT NULL DEFAULT 0;` and the equivalent for `columns`. This is a
  **one-off manual bridge step for this phase only** — the real Postgres profile has `ddl-auto`
  unset, so Hibernate will not create the new `@Version` column added to `TaskEntity`/
  `ColumnEntity` automatically. **Must be run manually via psql against the real database
  immediately before merging/deploying this phase's PR.** This is one-way: master auto-deploys to
  EC2 on every push (`.github/workflows/deploy.yml`), so if the column is missing when the new
  code ships, every request touching a Task or Column hits a missing-column SQL error in
  production. **Flag raised and resolved during discussion:** the initial instinct was to defer
  this DDL to Epic 3's Flyway migration work, but that was explicitly rejected — it would leave
  production broken between merge and whenever Epic 3 actually lands. Running it manually now,
  right before merge, was chosen instead. **Epic 3 must not silently re-apply or lose this step**
  — when Flyway migration tooling is introduced, this manual change needs to be reflected in
  migration history (e.g. as an already-applied/baseline migration), not re-run or forgotten.
- **2026-08-03 — Quick task `260803-m3i`: `UserEntity.passwordHash` tightened to non-nullable,
  production DDL bridge delivered.** Added
  [`04-password-hash-not-null-ddl.sql`](04-password-hash-not-null-ddl.sql): a guarded
  `DO $$ ... $$;` block that counts existing `users` rows with a null `password_hash`, aborts with
  a `RAISE EXCEPTION` naming the row count if any exist, and otherwise runs
  `ALTER TABLE users ALTER COLUMN password_hash SET NOT NULL;`. Must be run manually via psql
  against the real database before merging/deploying this PR, same mechanism as the two precedent
  scripts — `ddl-auto` is unset in the real Postgres profile, so the entity annotation alone does
  not touch the production schema. The pre-flight null check exists because `SET NOT NULL`, unlike
  Epic 2's `ADD COLUMN ... DEFAULT 0`, can fail outright against existing data — there is no
  default to backfill missing values with. **Backfilling nulls to a sentinel hash (approach C) was
  considered and explicitly rejected**: it would manufacture permanently-unauthenticatable accounts
  — the exact defect this change closes — while destroying the evidence that anything was wrong
  upstream. Unlike the Epic 2 bridge, this one is safe in either merge order (the constraint is
  additive and the app already only ever writes non-null hashes), so the pressure to run it before
  merge is "don't lose this," not "production breaks at merge." **Epic 3 must not silently re-apply
  or lose this step either** — same requirement as the two precedent scripts, reflected in Flyway
  migration history as an already-applied baseline when Epic 3 lands.
- **2026-08-06 — Epic 5 delivered.** Pulled forward ahead of Phase 5 as its own GSD phase
  (`.planning/phases/04.2-testcontainers-postgres-drop-h2/`) so the Flyway V1–V4 migration history
  would be CI-exercised before the Neon cutover. The suite now boots against a single, shared
  `postgres:16` Testcontainers instance (`AbstractPostgresContainerTest`), its schema built by
  Flyway's V1–V4 with `spring.jpa.hibernate.ddl-auto=validate` — the identical posture to
  production. `com.h2database:h2` is removed outright from `build.gradle`, with no fallback
  profile of any kind (D-05).
  - **Duration, measured same-machine/same-session:** pre-cutover H2 baseline 4m48s/199 tests →
    post-tracer H2 (like-for-like basis) 5m10s/208 tests → post-cutover PostgreSQL 4m51s/210
    tests, a **19s / 6.1% improvement**, not a regression. A final confirmation run on this same
    machine reproduced it: 4m51s/210 tests, 0 failures. The likely mechanism (not independently
    proven beyond these numbers): H2's `create-drop` rebuilt the schema from scratch on every one
    of the ~30 per-test-class Spring context creations across the run, while Flyway now migrates
    the schema exactly once against a container shared for the whole JVM lifetime — a single
    migration plus per-test-method row cleanup came out cheaper than ~30 repeated in-memory DDL
    rebuilds.
  - **Query-count outcome: unchanged, nothing adjudicated** — not "a change was reviewed and
    accepted." Both dialect-sensitive assertions
    (`TaskServiceTest.DeleteAllByColumnIdQueryCountTest`,
    `OwnershipVerifierServiceTest.QueryCountTest.verifyOwnershipOfSubtask_issuesOneQuery`) passed
    against PostgreSQL with zero edits to either test file. No H2→PostgreSQL dialect difference
    materialized in either guarded query shape.
  - **`activity_log` needed an explicit cleanup call (D-02a).** `V3__add_activity_log.sql` gives
    `activity_log` no foreign key back to `users`, so the existing cascade-from-`UserEntity`
    cleanup path in `AbstractAppTest.cleanup()` could not reach it — masked under H2's per-context
    `create-drop`, real under a schema that persists for the whole JVM run. Closed by adding a
    second, explicit `activityLogRepository.deleteAll()` call to the same cleanup hook, guarded by
    a dedicated `ActivityLogCleanupIsolationTest` whose falsification was verified by hand.
  - **Testcontainers reuse evaluated, not enabled.** Measured: three `fastTest` runs at
    232s/224s/242s against a ~2.29s container-start duration — roughly 1% of wall-clock, smaller
    than the run-to-run variance itself, and structurally capped besides (D-01's one
    static-container-per-JVM-run design means reuse could only ever help a second, separate
    `./gradlew` invocation, never a single run). The standard opt-in
    (`~/.testcontainers.properties`) also fails `docs/CODE_STYLE.md` rule 8 as a manual host-level
    step. Full writeup and the condition for revisiting it: `docs/LOCAL_DEV.md`.
  - **Two deliberate departures from this epic doc's task list above:**
    1. The container lives in a new shared third ancestor, `AbstractPostgresContainerTest`, that
       both `AbstractAppTest` and `AbstractKafkaContainerTest` extend — not "in `AbstractAppTest`
       (or a new shared base config class)" alone, as the task list above suggests. Scouting found
       `AbstractKafkaContainerTest` does not extend `AbstractAppTest`, yet its 9 subclasses persist
       `ActivityLogEntity`, so a container reachable only from `AbstractAppTest` would have left
       them without a datasource or forced a second container (D-01).
    2. The container's lifecycle is an imperative `static { start(); }`, not the
       `@Testcontainers`/`@Container` JUnit 5 extension the task list above names — that
       extension-driven singleton was already found unreliable across sibling classes in this
       environment for the Kafka container (Phase 3 Plan 02), and the same imperative pattern was
       reused here rather than risk the same failure mode with Postgres.
    Also: `@ServiceConnection` wired the datasource instead of the task list's suggested
    `@DynamicPropertySource`, since Spring Boot 3.5.0 ships a connection-details factory for
    `PostgreSQLContainer` that `@DynamicPropertySource` predates the need for.
