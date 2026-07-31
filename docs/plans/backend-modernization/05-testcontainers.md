# Epic 5 — Formalize Testcontainers, drop H2

[← back to plan index](README.md) · Effort: 2–3 days · Priority: **Medium**

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
  stronger claim than "H2 in-memory" if asked in an interview.
