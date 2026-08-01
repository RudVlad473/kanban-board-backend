# Epic 3 — Flyway migrations + OpenAPI polish

[← back to plan index](README.md) · Effort: 1–2 days · Priority: **High**

**Why bundled:** both are low-effort, both are "hygiene" items graders expect to just be there, so
batch them into one PR.

## Flyway tasks

- Add `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` to
  `build.gradle`.
- Currently prod `application.properties` has no explicit `spring.jpa.hibernate.ddl-auto` (relying
  on Hibernate default) and test uses `ddl-auto=create-drop`. Set
  `spring.jpa.hibernate.ddl-auto=validate` in prod once Flyway owns the schema — this is the
  correct pattern (Hibernate validates, Flyway migrates) and is worth being able to state
  explicitly, since "why not let Hibernate auto-generate the schema in prod" is a common
  follow-up question.
- Generate `V1__init.sql` under `src/main/resources/db/migration` reflecting the current schema
  (users, boards, columns, tasks, subtasks, plus the Spring Session JDBC tables already implied by
  `spring.session.jdbc.initialize-schema=always` — decide whether Flyway or Spring Session owns
  those tables and document the choice).
- Add `V2__add_activity_log.sql` and `V3__add_task_version_column.sql` for the tables/columns
  introduced in [Epic 1](01-kafka-activity-feed.md) and [Epic 2](02-n-plus-one-optimistic-locking.md),
  so the migration history tells the real story of the project's evolution.
- Keep `application-test.properties` on `create-drop` via Hibernate for now, OR switch tests to run
  Flyway against Testcontainers Postgres — do this switch as part of [Epic 5](05-testcontainers.md),
  not here, to keep this epic small.

## OpenAPI tasks

- Add `@Operation(summary = ..., description = ...)` and `@ApiResponse` annotations to every
  endpoint in `BoardController`, `ColumnController`, `TaskController`, `SubtaskController`, and
  `AuthenticationController`.
- Add `@Schema` descriptions to the request/response DTOs, particularly the validation-constrained
  fields (`ValidationConstants` already defines min/max lengths — surface them in the schema
  descriptions so the generated docs are actually useful, not just present).
- Add a top-level `@OpenAPIDefinition` bean (in `BeanConfiguration` or a new `OpenApiConfig`)
  with title/description/version and a security scheme entry describing the session-cookie auth,
  since Swagger UI otherwise can't show how to authenticate.

Note: `springdoc-openapi-starter-webmvc-ui` is already a dependency and already wired into
`SecurityConfiguration` (docs path is permit-all) — this section is quality polish, not adding a
new dependency.
