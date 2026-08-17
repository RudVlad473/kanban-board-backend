# Milestones

## v1.2 Infra Migration & Schema Registry (Shipped: 2026-08-17)

**Phases completed:** 7 phases, 39 plans, 107 tasks

**Key accomplishments:**

- 5 Avro schemas (one per ActivityEvent type), gradle-avro-plugin codegen wired into the build, and a hand-authored ActivityEventAvroMapper proving bidirectional translation with a no-mock round-trip test — nothing wired into the live Kafka path yet.
- The activity-log pipeline now speaks genuine Avro end-to-end against a real Redpanda-hosted Confluent-compatible registry — BACKWARD compatibility is enforced and demonstrated (a 409 on an incompatible evolution, a clean accept on a compatible one), and the local docker-compose stack has a registry the running app actually publishes and consumes against.
- Re-verified SCHEMA-05's dead-letter byte-fidelity guarantee against two Avro-era poison shapes (framing failure, registry-resolution failure) and proved D-01's mutation-survives-Kafka-failure resilience policy specifically for a schema-registry outage — surfacing along the way a genuine, previously undocumented gap in how that resilience policy is described in code.
- Built and structurally verified the last pre-cutover gate for SCHEMA-06 -- an inverse mapper proven against the real Avro pipeline, and a dedicated `rehearseHistoricalSchemas` Gradle task that round-trips real `activity_log` rows through the new schemas -- but could not complete the live human-check against genuine historical data in this sandboxed environment because a pre-existing native Windows PostgreSQL service already owns port 5432, silently intercepting every connection the Docker-based Postgres this task needs was supposed to receive.
- Wired Flyway through build, test-profile, and migration-resource layers, then proved the whole path end-to-end: a genuinely empty docker-compose Postgres, migrated by the app's own startup process, records exactly one successful row (`V1__init.sql`) in `flyway_schema_history` — with the existing H2-backed test suite unaffected.
- Ported the three manual DDL bridge scripts (optimistic-locking version columns, activity_log, password_hash NOT NULL) into checksummed Flyway migrations V2-V4, proven end-to-end against a genuinely empty docker-compose Postgres volume alongside Plan 01's V1 baseline.
- Flipped Hibernate to `ddl-auto=validate` in both `application.properties` and the docker-compose `app` service (closing RESEARCH.md's Open Question 1 by making the local stack actually exercise deploy-equivalent behavior), marked the three manual DDL bridge scripts this phase replaces as superseded, and closed out the blocking checkpoint with an operator-run clean-volume migration proof and a green full test suite — completing Phase 04.1.
- Proved Flyway V1-V4 + Hibernate `ddl-auto=validate` + Spring Session JDBC coexist against one real, imperatively-started `postgres:16` Testcontainers container, through a single new test class, with H2 and all ~25 existing test classes left completely untouched.
- Cut the entire test suite off H2 onto the shared PostgreSQL 16 container proven in Plan 01 -- both test hierarchies plus the two orphan `@SpringBootTest` classes now boot against Flyway-built schema, `com.h2database:h2` is gone from the build entirely, the `activity_log` cross-test isolation gap is closed, and the full suite finished ~19s FASTER on the container than the H2 baseline it replaces.
- Testcontainers reuse evaluated and deliberately not enabled (measured ~1% of wall-clock, smaller than run-to-run variance, and structurally capped by D-01 besides); every H2 claim in git-tracked docs, agent context, and the GSD codebase map corrected to name the real Testcontainers PostgreSQL substrate; Epic 5 closed in the modernization tracker; phase closes on a green `spotlessCheck` + `clean test` (210 tests, 0 failures, 4m53s).
- Allowlisted Actuator health endpoint wired into Spring Security, a Neon-pooled-endpoint-safe HikariCP datasource config, and empirical proof (not a new artifact) that the already-shipped Flyway migrations satisfy INFRA-06's empty-database schema requirement.
- Standalone docker-compose.prod.yml (caddy + app + redpanda, no postgres) with capped logs and an honest UP-status healthcheck, a Caddyfile relying on automatic Let's Encrypt HTTPS, two Kruchten-scoped Mermaid architecture diagrams closing the folded diagram todo, and an arm64 buildx cross-compile added to the CI build job.
- Oracle Cloud's A1.Flex capacity proved structurally unavailable, so the production deploy target pivoted to a Netcup VPS — provisioned, hardened with a two-layer firewall, and verified reachable; a Neon Postgres project and a DuckDNS hostname now complete the infrastructure the tracer deploy (plan 05-04) needs.
- Full production stack live on real Netcup infrastructure (HTTPS, Neon persistence, Redpanda registry), Schema Registry cutover independently re-verified against production, and Redpanda's resource caps corrected from real measured usage rather than left as a provisional guess.
- Flyway migration verification and a fingerprint-pinned SSH/SCP deploy job now run on every push to master, proven against the real Netcup VM three times in a row -- plus a genuine tag-pruning bug found live and a false "verified" claim in a prior commit corrected rather than repeated.
- Proved 22/80/443-only external reachability via three independent full-range scans, proved Docker's log-rotation mechanism deterministically after discovering the app itself logs almost nothing per request, confirmed AWS-era secrets are already gone, corrected eight files' worth of stale AWS-EC2 deploy-target documentation, and (in a same-day follow-on continuation) fixed and live-verified `cleanup-old-images`' two-bug Docker Hub pruning failure — Phase 5 is now fully complete.
- One V5 Flyway migration lands tasks.position, columns.position, subtasks.version, users.theme (default LIGHT), and a boards per-user name uniqueness constraint; SubtaskService.updateById gains the same explicit version-compare-then-409-then-flush guard TaskService/ColumnService already use, proven over real HTTP by a new SubtaskLockingE2ETest.
- POST /boards wired end-to-end onto the pre-existing UserService.addBoardByUserId (201 + Location header, inheriting BoardCreatedEvent for free), plus a new AppDuplicateResourceException giving both the board-create and board-rename paths a real per-user name-uniqueness guard backstopped by the uk_boards_user_id_name constraint.
- `DELETE /boards/{boardId}/columns/{columnId}` cascades to tasks/subtasks via the existing batched delete and publishes a new `ColumnDeletedEvent` — the sixth Avro-registered, sealed-interface `ActivityEvent` — closing column deletion as the one previously-unlogged mutation in the domain.
- `Integer position` on both `TaskEntity` and `ColumnEntity`, maintained by renumber-on-insert via single bulk column/board-scoped shift statements, with task move and task reorder collapsed into the existing move endpoint's new `targetPosition` field (D-04) and a new `PATCH /boards/{boardId}/columns/{columnId}/reorder` route for columns.
- GET /boards/{boardId}/full returns board, columns, tasks and subtasks four levels deep in a single database round trip, via a chained LEFT JOIN FETCH query and a MapStruct BoardFullMapper -> ColumnFullMapper -> TaskFullMapper -> SubtaskMapper composition chain -- built on top of a corrected, non-obvious finding that Hibernate's MultipleBagFetchException and multi-level collection duplication apply more broadly than this plan's own design rationale assumed.
- GET/PUT /users/me/theme (GAP-05) on a new session-scoped UserController, proving the theme survives logout/fresh-signin and is stored as the enum's STRING form per user -- recovered after a mid-task stall left task 2's tests written but uncommitted.
- Switched `ActivityLogEntity.eventId` from `UUID.randomUUID()` to a Base36, time-ordered string produced by the project's existing `RandFlakeGenerator` (reused via a new `EventIdGenerator` wrapper), retyping the column, all six Avro schemas, and every event-publish call site as one atomic compile unit, with the registry's real acceptance of the change under BACKWARD compatibility verified directly rather than assumed.
- Relocated all five shared test-infrastructure files into a 3-way-split `support/` package, added `AbstractAppMockMvcTest` as the in-process auth fixture, deleted two empty test classes, and empirically proved Assumption A2 (real signin+cookie-relay authentication works under MockMvc) by converting `ColumnLockingE2ETest`.
- Merged AuthenticationControllerTest, SessionPersistenceE2ETest and UserPersistenceE2ETest into one `@Nested`-grouped, in-process MockMvc test (`security/AuthenticationE2ETest`), and proved by falsification that the concurrent-session-ceiling and session-fixation controls still exercise the real `AuthenticationController.authenticate` call site after the downgrade.
- Converted ColumnDeletionE2ETest (4 tests) and ColumnOrderingE2ETest (8 tests) from the real-socket RestAssured/RANDOM_PORT tier to the in-process @SpringBootTest + MockMvc tier, preserving every assertion and the real signin+cookie-relay authentication path.
- TaskLockingE2ETest, TaskOrderingE2ETest, and TaskMoveE2ETest downgraded from real-socket RestAssured/RANDOM_PORT to in-process MockMvc, preserving every optimistic-locking and event-observation assertion one-for-one, with TaskMoveE2ETest's misleadingly-named "ConcurrentConflict" group confirmed genuinely sequential before conversion.
- Converted BoardFullReadE2ETest and SubtaskLockingE2ETest from the real-socket RestAssured/RANDOM_PORT tier to the in-process @SpringBootTest + MockMvc tier, with zero assertion changes and zero production-code touches.
- Downgraded ThemePersistenceE2ETest and ActivityReadE2ETest from the real-socket RestAssured/RANDOM_PORT tier to the in-process AbstractAppMockMvcTest/MockMvc tier, preserving every assertion one-for-one — including the theme class's logout-then-re-signin round trip and its cookie-inequality check, and the activity class's direct repository seeding with zero Kafka dependency.
- Closed Phase 7 with a green full-repository gate (278 tests, 0 failures, no shrinkage from the pre-phase count), reconciled every git-tracked doc that described the pre-Phase-7 test tree, extended `docs/CODE_STYLE.md` rule 4 with both a which-package and a which-base-class decision rule, and filed the one genuine follow-up decision (E2ETest suffix vs. `fastTest` filter coupling) this phase surfaced.
- Converged all 13 GlobalExceptionHandler branches onto Spring's RFC 7807 `ProblemDetail` envelope with a stable `ErrorCode` extension property, and split the previously-overloaded 401 by remapping ownership denials to 403 while leaving bad-credentials 401 untouched.
- Wired a custom `AuthenticationEntryPoint` that produces a real, RFC 7807 `ProblemDetail`-shaped 401 for every genuinely unauthenticated request, replacing Spring Security's default bodiless `Http403ForbiddenEntryPoint` and completing the 401/403 split plan 07.1-01 started.
- Turned on `@Valid` for the two `permitAll()` auth routes (previously dead-code field constraints), added a real 409 for duplicate signup emails without regressing the anti-enumeration 401 collapse, and delivered the `getForeignUser()` cross-owner fixture plan 07.1-08 depends on.
- Closed the last Board/Column/Task/Subtask concurrency-model asymmetry: Flyway V7 adds `boards.version`, `BoardEntity` carries `@Version`, `BoardService.updateById` explicitly rejects a stale write with 409, and `version` now flows through every board DTO (request and both response shapes) with a new `BoardLockingTest` proving all of it over real HTTP.
- Column and subtask creation now return 201 Created with a Location header, closing the last create-status-code inconsistency the phase title named -- all five resource-creating POST routes (board, column, task, subtask, signup) now agree.
- Pre-commit gate membership is now an explicit per-class `@Tag` declaration instead of an emergent property of class naming — closes the exact coupling that made Phase 7's test-tier downgrade unable to rename its own classes safely.
- Two new security test classes — `InjectionAttemptTest` (SQL/XSS/boundary/malformed-path payloads against real Postgres) and `AuthorizationGatingTest` (a 22-route, reflectively-guarded sweep of every 401/403 gate) — both running in the pre-commit fastTest gate with zero production code changed.
- Fixed a real POST /logout 500 the scan surfaced (dead `@Value`-on-a-static-field bug), deferred five lower-priority findings with written rationale and todos, and added four Mermaid sequence diagrams (auth/session, CRUD-mutation-with-Kafka-side-effect, activity-log read, and the four-way 401/403/400/409 error split) to `docs/ARCHITECTURE.md`, closing out phase 07.1.

---

## v1.1 Kafka Activity Feed (Shipped: 2026-08-03)

**Phases completed:** 2 phases, 6 plans, 6 tasks

**Key accomplishments:**

- The full vertical slice — Gradle dependencies, Kafka producer configuration
- `TaskCreatedEvent`/`TaskDeletedEvent` records, widened `ActivityEvent` permits clause,
- `docker-compose.yml` at the repo root with `postgres:16`, `apache/kafka-native:4.3.1`
- 1. [Rule 1 - Bug, production-relevant] `KafkaConsumerConfig.deadLetterKafkaTemplate` silently suppressed Spring Boot's default `KafkaTemplate` bean

---

## v1.0 Optimistic Locking (Shipped: 2026-08-01)

**Phases completed:** 1 phases, 3 plans, 7 tasks

**Key accomplishments:**

- End-to-end optimistic locking on Task updates (entity @Version, required client version, explicit service check, 423->409 fix) proven by a real HTTP E2E test, plus a fix to a pre-existing cookie-authentication bug that silently broke every real (non-MockMvc) authenticated request.
- Added the previously-missing Column update endpoint (PUT /boards/{boardId}/columns/{columnId}) with the same explicit version-check optimistic-locking pattern proven for Task in Plan 01, plus documentation of the two research-carried bulk-delete/@Version tradeoffs.
- Delivered a standalone, idempotent `ALTER TABLE ... ADD COLUMN version bigint NOT NULL DEFAULT 0` SQL script for the real Postgres `tasks`/`columns` tables, plus a dated STATUS.md decision-log entry recording the one-way manual-run obligation before merge/deploy.

---
