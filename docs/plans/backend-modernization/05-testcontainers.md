# Epic 5 — Formalize Testcontainers, drop H2

[← back to plan index](README.md) · Effort: 2–3 days · Priority: **Medium**

> **Status (2026-08-06, GSD Phase 04.2):** Delivered — pulled forward ahead of the paused Phase 5
> as its own independently-claimable GSD phase
> (`.planning/phases/04.2-testcontainers-postgres-drop-h2/`), for the same reason Epic 3's Flyway
> half was pulled forward in Phase 04.1: the migration history needed to be CI-exercised before the
> Neon cutover. The delivered implementation departs from the task list below it in three places a
> future reader should not mistake for what actually shipped:
>
> 1. **Shared third ancestor, not `AbstractAppTest` alone.** The container lives in a new
>    `AbstractPostgresContainerTest` that both `AbstractAppTest` and `AbstractKafkaContainerTest`
>    extend, not "in `AbstractAppTest` (or a new shared base config class)" as the task list below
>    says — `AbstractKafkaContainerTest` does not extend `AbstractAppTest`, yet its 9 subclasses
>    persist entities and needed the same datasource (D-01).
> 2. **Imperative `static { start(); }`, not the `@Testcontainers`/`@Container` extension.** That
>    JUnit 5 extension's singleton semantics were already found unreliable across sibling classes
>    in this environment for the Kafka container (Phase 3 Plan 02); the container lifecycle here
>    follows that same imperative precedent instead.
> 3. **`@ServiceConnection`, not `@DynamicPropertySource`.** Spring Boot 3.5.0 ships a
>    connection-details factory for `PostgreSQLContainer`, so the task list's suggested
>    `@DynamicPropertySource` wiring wasn't needed.
>
> Full write-up, measurements, and the Testcontainers-reuse evaluation:
> `docs/plans/backend-modernization/STATUS.md`'s Epic 5 entry and `docs/LOCAL_DEV.md`. The task
> list below is kept as the original historical record and is not itself updated.

**Why now, not earlier:** by this point you already need Testcontainers for Kafka
([Epic 1](01-kafka-activity-feed.md)) and ideally Postgres (so tests run against real Postgres, not
H2's different SQL dialect/behavior). Bundle the full switch here rather than doing it piecemeal.

## Tasks

- Add `org.testcontainers:postgresql` and `org.testcontainers:junit-jupiter`.
- Replace `application-test.properties`'s H2 datasource with a `@Testcontainers`-managed
  Postgres container in `AbstractAppTest` (or a new shared base config class), started once per
  test run via a static container + `@DynamicPropertySource`.
- Remove `com.h2database:h2` from `build.gradle` once nothing references it.
- Run Flyway migrations (from [Epic 3](03-flyway-openapi.md)) against the test container too (drop
  `ddl-auto=create-drop` entirely — the schema now comes from the same migrations that run in
  prod, which is the actual point of doing this).
- Confirm existing controller/service tests still pass — this is a good moment to note in the
  README that integration tests now hit real Postgres/Kafka via Testcontainers, which is a
  stronger claim than "H2 in-memory".
