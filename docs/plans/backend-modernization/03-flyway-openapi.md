# Epic 3 — Flyway migrations + OpenAPI polish

[← back to plan index](README.md) · Effort: 1–2 days · Priority: **High**

**Why bundled:** both are low-effort, both are "hygiene" items graders expect to just be there, so
batch them into one PR.

> **Status (2026-08-05, GSD Phase 04.1):** The Flyway half of this epic has shipped, pulled forward
> ahead of the paused Phase 5 as its own independently-claimable GSD phase
> (`.planning/phases/04.1-flyway-database-migration-implementation/`). `flyway-core` and
> `flyway-database-postgresql` are on the classpath, `V1__init.sql` through
> `V4__add_password_hash_not_null.sql` reconstruct the schema's real history under
> `src/main/resources/db/migration/`, `spring.jpa.hibernate.ddl-auto=validate` is set in prod, and
> the three manual DDL bridge scripts in this directory now carry superseded-by headers pointing at
> their replacement migrations. Spring Session's tables stay owned by `spring-session-jdbc`'s own
> initializer, not Flyway (this epic doc's own open question above, now resolved). **The OpenAPI
> half below remains open and untouched** — still its own future slice of work if pulled forward.
>
> Two downstream heads-ups for whoever resumes Phase 5:
>
> 1. **INFRA-06** ("pre-merge DDL verification step against Neon") should be reconsidered as
>    running Flyway's own `migrate`/`validate` against Neon's direct connection string, rather than
>    a new bespoke DDL check — Flyway now owns that job. This is a heads-up for Phase 5, not a
>    change to Phase 5's own plan.
> 2. **`rehearseHistoricalSchemas`** (the Gradle task in `build.gradle`) deliberately does not
>    activate the test profile, so it resolves `application.properties`' datasource — meaning it now
>    inherits both Flyway and `ddl-auto=validate` against whatever real Postgres
>    `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` point at when it runs. If that database was built by
>    Hibernate's auto-alter and has no `flyway_schema_history` table, Flyway will refuse to migrate a
>    non-empty unmanaged schema, and the task fails at context startup with a non-empty-schema error
>    rather than anything to do with Avro. The remedy is to point it at a database Flyway owns — for
>    the local stack, `docker compose down -v` followed by `docker compose up -d`, then regenerate
>    the activity corpus by exercising the running app (the same procedure quick task 260804-nd3
>    already used).

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
