# Roadmap: Kanban Board Backend — Epic 2 Completion

## Milestones

- ✅ **v1.0 Optimistic Locking** — Phase 1 (shipped 2026-08-01)
- ✅ **v1.1 Kafka Activity Feed** — Phases 2-3 (shipped 2026-08-03)
- 🚧 **v1.2 Infra Migration & Schema Registry** — Phases 4-5 (in progress)

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

<details>
<summary>✅ v1.0 Optimistic Locking (Phase 1) — SHIPPED 2026-08-01</summary>

- [x] Phase 1: Optimistic Locking (3/3 plans) — completed 2026-08-01

Full details: [milestones/v1.0-ROADMAP.md](milestones/v1.0-ROADMAP.md)

</details>

<details>
<summary>✅ v1.1 Kafka Activity Feed (Phases 2-3) — SHIPPED 2026-08-03</summary>

- [x] Phase 2: Kafka Foundation, Domain Events & Move Endpoint (3/3 plans) — completed 2026-08-01
- [x] Phase 3: Activity Log Consumer, Reliability & Read API (3/3 plans) — completed 2026-08-02

Full details: [milestones/v1.1-ROADMAP.md](milestones/v1.1-ROADMAP.md)

</details>

### 🚧 v1.2 Infra Migration & Schema Registry (In Progress)

**Milestone Goal:** Redeploy the app on a cost-guarded, always-free/near-free stack (Oracle Cloud + Neon + self-hosted Redpanda) after the AWS EC2/RDS deletion, and close the schema-evolution risk flagged during v1.1 (SEED-001) with a Kafka Schema Registry (Avro) in front of the activity-log pipeline.

- [x] **Phase 4: Schema Registry** - Avro schemas, mapping layer, enforced compatibility mode, and DLT/historical-data re-verification, built and proven entirely against the local docker-compose stack (completed 2026-08-04)
- [ ] **Phase 5: Infra Migration** - Oracle Cloud VM + Neon + Redpanda + Caddy + GitHub Actions CI/CD, with Phase 4's Schema Registry cutover to the production target

## Phase Details

### Phase 4: Schema Registry

**Goal**: The activity-log pipeline's 5 event types are governed by explicit, versioned Avro schemas with an enforced (non-default) compatibility mode, verified end-to-end — including DLT poison-message handling and a real-data rehearsal — entirely against the local docker-compose stack. Closes the schema-evolution risk (SEED-001, planted during v1.1 Phase 3) with zero dependency on the new deploy target.
**Depends on**: Phase 3 (existing Kafka producer/consumer pipeline — `KafkaEventPublisher`, `ActivityLogConsumer`, `KafkaConsumerConfig`)
**Requirements**: SCHEMA-01, SCHEMA-02, SCHEMA-03, SCHEMA-04, SCHEMA-05, SCHEMA-06
**Success Criteria** (what must be TRUE):

  1. Each of the 5 `ActivityEvent` types (`TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`) has an explicit, versioned Avro schema registered in the local Schema Registry via a build/CI step, not producer auto-registration
  2. The producer (`KafkaEventPublisher`) and consumer (`ActivityLogConsumer`/`KafkaConsumerConfig`) serialize/deserialize activity events as Avro `SpecificRecord`s via Confluent's Avro serde against the registry, through a mapping layer that leaves the existing sealed-interface/exhaustive-switch application code unchanged
  3. The activity-log topic's schema subject(s) enforce an explicitly configured, documented compatibility mode (BACKWARD or FULL) rather than the registry's out-of-the-box default
  4. A poison message is dead-lettered with byte-fidelity intact under Avro, via a dedicated raw byte-array serializer kept separate from the Avro-aware main path, proven by a new automated test
  5. A sample of real historical activity-log events round-trips through the new Avro schemas without field-default/strictness errors, rehearsed before any production cutover is attempted

**Plans**: 4/4 plans executed

Plans:

- [x] 04-01-PLAN.md — Avro schema source of truth: 5 `.avsc` files, Gradle codegen, and the sealed-interface ↔ SpecificRecord mapper (wave 1)
- [x] 04-02-PLAN.md — TRACER: registry-backed Avro cutover of producer and consumer, build/CI registration step, BACKWARD compatibility with a proven rejection, local compose stack with a registry (wave 2)
- [x] 04-03-PLAN.md — Failure paths under Avro: dead-letter byte fidelity for framing-level and registry-level poison, and a mutation surviving a registry outage (wave 3)
- [x] 04-04-PLAN.md — Historical-data rehearsal: reconstruct real `activity_log` rows into events and round-trip them through the new schemas (wave 3)

### Phase 04.1: Flyway database migration implementation (INSERTED)

**Goal:** The app's own domain schema (`users`, `boards`, `columns`, `tasks`, `subtasks`, `activity_log`) is managed by versioned, checksummed Flyway migrations that reconstruct its real evolution rather than a flattened snapshot, and Hibernate can no longer create or alter schema outside the test profile — proven by V1–V4 applying cleanly to an empty local docker-compose Postgres followed by a passing `ddl-auto=validate` startup and a green full test suite.
**Requirements**: None — inserted urgent phase with no REQ-IDs; scope is locked by `04.1-CONTEXT.md` decisions D-01 (incremental history, not a collapsed baseline), D-02 (Spring Session tables stay out of Flyway's scope), D-03 (`ddl-auto=validate` now, verified locally), D-04 (test profile stays on `create-drop`).
**Depends on:** Phase 4
**Plans:** 3/3 plans complete

Plans:

**Wave 1**

- [x] 04.1-01-PLAN.md — Flyway tracer: BOM-managed `flyway-core`/`flyway-database-postgresql`, `V1__init.sql` pre-Epic-2 baseline, H2 test-profile isolation, applied end-to-end against real Postgres (wave 1, blocking decision checkpoint)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 04.1-02-PLAN.md — Expand migration history: V2 optimistic-locking version columns, V3 `activity_log` + index, V4 guarded `password_hash NOT NULL` (wave 2, autonomous)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 04.1-03-PLAN.md — `ddl-auto=validate` cutover in `application.properties` and `docker-compose.yml`, clean-volume proof, superseded-by headers on the three manual DDL scripts (wave 3, human-verify checkpoint) — complete; operator ran the clean-volume docker-compose proof and full `./gradlew spotlessCheck test` gate directly and approved (Flyway V1-V4 applied sequentially against a wiped volume, zero Hibernate DDL activity, flyway_schema_history 4/4 success, live POST /api/signup returned 201, forced-fresh test suite green in 2m52s)

### Phase 04.2: Testcontainers Postgres, drop H2 (INSERTED)

**Goal:** The whole test suite runs against a real PostgreSQL container whose schema is built by the same Flyway V1–V4 migrations that run in production — with `com.h2database:h2`, `spring.flyway.enabled=false`, and `spring.jpa.hibernate.ddl-auto=create-drop` all gone from the build — proven by a green full suite showing zero Hibernate-generated DDL and a recorded suite-duration delta against the same-machine, same-session H2 baseline (4m48s/199 tests, superseding the earlier ~232s figure measured in a different session).
**Requirements**: None — inserted phase with no REQ-IDs. Source is modernization Epic 5, `docs/plans/backend-modernization/05-testcontainers.md`, previously deferred 2026-07-31 and pulled forward here.
**Depends on:** Phase 04.1 — this phase's entire point is making the suite execute the V1–V4 migrations that 04.1 authored.
**Blocks:** Phase 5. Those migrations are currently verified only by 04.1-03's manual clean-volume run against local docker-compose, never by CI. Phase 5 points production at a brand-new Neon database and adds a pre-merge DDL verification gate, so closing that gap first is what keeps the cutover from being the first real test of the migration history.
**Scope** — locked in `04.2-CONTEXT.md` (D-01..D-05), which is authoritative over this summary:

  - D-05: full drop, not a hybrid — no H2 fallback profile retained, since two schema paths is exactly the drift `docs/CODE_STYLE.md` rule 8 exists to prevent
  - D-01: one static Postgres container in a shared base that **both** `AbstractAppTest` and `AbstractKafkaContainerTest` inherit — not behind `AbstractAppTest` alone as Epic 5's doc says, because the Kafka base does not extend it while its 9 subclasses do persist entities. Imperative `start()`, not `@Testcontainers`/`@Container`, matching the precedent already set here
  - D-02: test isolation stays `@AfterEach deleteAll()`; test-managed `@Transactional` rollback was considered and rejected — it breaks `AFTER_COMMIT` event delivery, the `getPrepareStatementCount()` metric, and gives REST Assured E2E no isolation anyway
  - D-03/D-04: `fastTest` keeps its `*E2ETest` exclusion and now requires Docker; Spring Session tables stay out of Flyway per 04.1 D-02
  - Carried risk: ~25 fixture-heavy test classes move off in-memory H2 against a same-machine 4m48s/199-test full-suite baseline, and the pre-commit gate's container-free premise disappears

**Plans:** 3/3 plans complete — delivered ~19s/6.1% FASTER than the like-for-like H2 baseline, not slower; Testcontainers reuse evaluated and deliberately not enabled (see 04.2-03-SUMMARY.md)

Plans:

- [x] 04.2-01-PLAN.md — TRACER: `AbstractPostgresContainerTest` + `FlywaySchemaProvenanceTest` prove Flyway V1-V4 + Hibernate `ddl-auto=validate` + Spring Session JDBC coexist against one real Postgres container, H2 and the existing suite left untouched
- [x] 04.2-02-PLAN.md — Cutover: both test hierarchies + two orphan `@SpringBootTest` classes moved onto the shared container, `com.h2database:h2` removed outright (D-05), `activity_log` cross-test isolation gap closed (D-02a), two H2-dialect-coupled assertions repaired — full suite 4m51s/210 tests, faster than the H2 baseline it replaced (blocking-human checkpoint, approved)
- [x] 04.2-03-PLAN.md — Testcontainers reuse measured and NOT enabled (docs/LOCAL_DEV.md); every H2 claim in git-tracked docs, agent context, and the codebase map corrected; Epic 5 ticked in the modernization tracker; phase closed on a green `spotlessCheck` + `clean test` (210/0/0, 4m53s)

### Phase 5: Infra Migration

**Goal**: The app is redeployed on a cost-guarded, always-free/near-free stack — reachable over real HTTPS, backed by Neon and a resource-capped Redpanda broker, deployed automatically on merge to `master` — with Phase 4's Schema Registry repointed from local/standalone to the production Redpanda registry and re-verified against it.
**Depends on**: Phase 4 (final cutover step: repoint `schema.registry.url` from wherever Phase 4 verified against — local Redpanda or a standalone registry container — to the production Redpanda instance's built-in registry on the Oracle VM, then re-run Phase 4's verification suite against the real target)
**Requirements**: INFRA-01, INFRA-02, INFRA-03, INFRA-04, INFRA-05, INFRA-06, INFRA-07, INFRA-08
**Success Criteria** (what must be TRUE):

  1. The app is publicly reachable over real HTTPS (not bare HTTP/IP) on the Oracle Cloud Always Free A1 Flex VM, running in Docker with `restart: unless-stopped` and healthchecks on the app container
  2. The production database is Neon serverless Postgres via a pooled connection string (`sslmode=require`, HikariCP sized for Neon's cold-start/pooling behavior), with zero JPA/Hibernate code changes
  3. The deploy target's Kafka broker is a resource-capped (`--overprovisioned`/`--memory`/`--smp`), single-node Redpanda instance that cannot starve the co-resident app JVM, and Phase 4's Schema Registry verification suite is re-run and green against Redpanda's built-in registry on the VM
  4. A push to `master` triggers an automated GitHub Actions build-and-deploy to the Oracle VM using freshly generated SSH credentials (not reused AWS-era secrets), gated by a pre-merge DDL verification step against Neon's direct connection string
  5. Only ports 80/443 are externally reachable — verified by an outside port scan/curl across all three OCI network layers (Security List, NSG, OS firewall); Redpanda's 9092 is never internet-facing; Docker log drivers are capped (`max-size`/`max-file`) so unbounded app/Redpanda logs cannot fill the free-tier disk

**Plans**: 6 plans (4 waves; D-03 sequencing — manual tracer deploy proven before CI/CD automation)

Plans:
**Wave 1**

- [ ] 05-01-PLAN.md — Production app configuration: Actuator health endpoint, Neon datasource + HikariCP sizing, baseline schema DDL (wave 1, autonomous)
- [ ] 05-02-PLAN.md — Production deploy manifests: docker-compose.prod.yml, Caddyfile, infra architecture diagram (wave 1, autonomous)
- [ ] 05-03-PLAN.md — Guided cloud provisioning: Oracle VM + Reserved IP, three-layer firewall, Neon project, free subdomain (wave 1, human checkpoints)

**Wave 2** *(blocked on Wave 1 completion)*

- [ ] 05-04-PLAN.md — TRACER: manual end-to-end deploy on real Oracle infra + Schema Registry cutover + measured resource caps (wave 2, human checkpoints)

**Wave 3** *(blocked on Wave 2 completion)*

- [ ] 05-05-PLAN.md — CI/CD pipeline rewrite + guided deploy secrets + DDL verification job (wave 3, human checkpoints)

**Wave 4** *(blocked on Wave 3 completion)*

- [ ] 05-06-PLAN.md — Cutover verification & decommission: external network audit, log-rotation measurement, AWS-era secret revocation (wave 4, human checkpoints)

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|-----------------|--------|-----------|
| 1. Optimistic Locking | v1.0 | 3/3 | Complete | 2026-08-01 |
| 2. Kafka Foundation, Domain Events & Move Endpoint | v1.1 | 3/3 | Complete | 2026-08-01 |
| 3. Activity Log Consumer, Reliability & Read API | v1.1 | 3/3 | Complete | 2026-08-02 |
| 4. Schema Registry | v1.2 | 4/4 | Complete    | 2026-08-04 |
| 04.1. Flyway database migration implementation (INSERTED) | v1.2 | 3/3 | Complete | 2026-08-05 |
| 04.2. Testcontainers Postgres, drop H2 (INSERTED) | v1.2 | 3/3 | Complete | 2026-08-06 |
| 5. Infra Migration | v1.2 | 0/TBD | Not started | - |
</content>

### Phase 6: Mock-up Feature Gap Closure

**Goal:** The backend's REST surface closes the six concrete gaps `docs/MOCKUP_FEATURE_GAP.md`
§1 identifies between the Kanban mock-ups and the current API, bringing feature parity
with the design without disturbing the existing Board/Column/Task/Subtask/Move
contracts those gaps sit alongside.

**Candidate features** (source: `docs/MOCKUP_FEATURE_GAP.md` §1, one row per gap):

  1. **Board creation route** *(§1.1)* — expose `POST /boards`, wiring the already-written
     but unreachable `UserService.addBoardByUserId` onto `BoardController`; decide how the
     Add New Board modal's initial-columns list is submitted (create-then-batch-add vs. a
     DTO that grows a columns list)

  2. **Column deletion route** *(§1.2)* — add `DELETE /boards/{boardId}/columns/{columnId}`
     to `ColumnController`, with the same cascade-to-tasks/subtasks behavior board deletion
     already has

  3. **Task/column ordering** *(§1.3)* — add a position/index field to the task (and
     optionally column) entity and DTOs so reordering within or between columns has a
     backend representation; `MoveTaskRequestDTO` today carries no notion of position

  4. **Single nested "full board" read** *(§1.4)* — `GET /boards/{boardId}/full`, replacing
     the four-round-trip fan-out (board → columns → tasks → subtasks) a client needs today;
     previously deferred to v2 per `STATE.md` line 199, now back in scope

  5. **Per-user theme persistence** *(§1.5)* — a field on `UserEntity`/`UserResponseDTO`
     plus a read/write endpoint for the light/dark preference the mock-ups render
     throughout, if it needs to persist across devices/sessions rather than live in
     client-local storage

  6. **Subtask optimistic-locking `version` field** *(§1.6, lower confidence)* — bring
     `UpdateSubtaskRequestDTO`/`SubtaskResponseDTO` in line with Column/Task/Move, which all
     already require and check a `version` on update

**Requirements**: GAP-01, GAP-02, GAP-03, GAP-04, GAP-05, GAP-06, GAP-07
**Depends on:** Phase 5
**Plans:** 7/7 plans executed (4 waves — wave 1: plan 01; wave 2: plans 02, 03 in parallel; wave 3: plans 04, 05, 06 in parallel; wave 4: plan 07)

Plans:
**Wave 1**

- [x] 06-01-PLAN.md — [BLOCKING] Flyway V5 + all new entity fields + ApiPaths constants, proven by subtask optimistic locking (GAP-06, schema half of GAP-03/GAP-05)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 06-02-PLAN.md — POST /boards + per-user board-name uniqueness on create and rename (GAP-01)
- [x] 06-03-PLAN.md — DELETE column with cascade + new ColumnDeletedEvent and Avro schema (GAP-02)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 06-04-PLAN.md — Task/column position, reorder endpoints, ordered reads (GAP-03)
- [x] 06-05-PLAN.md — GET /boards/{boardId}/full nested single-round-trip read (GAP-04)
- [x] 06-06-PLAN.md — Per-user theme persistence on a new UserController (GAP-05)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 06-07-PLAN.md — Snowflake-style activity-log eventId; opens with a blocking decision checkpoint (GAP-07)

### Phase 7: Restructure test folder

**Goal:** Execute (not merely evaluate — D-04) three test-suite organization improvements from the
Phase 1-6 growth period: relocate all five shared setup/fixture infrastructure files (`AbstractAppTest`,
`AbstractAppE2ETest`, `AbstractKafkaContainerTest`, `AbstractPostgresContainerTest`,
`RecordingActivityEventListener`) into a three-way-split `support/` package; merge the three
closely-related authentication/session test classes into one `@Nested`-grouped file; and drop the 13
`E2ETest`-suffixed classes that need neither a real socket nor Kafka to the cheaper in-process
`@SpringBootTest` + MockMvc tier, keeping the 9 that genuinely earn the expensive tier. Two empty test
classes are deleted as a byproduct. Zero production-code changes — every diff is under `src/test/java`.
Source todo:
`.planning/todos/pending/2026-08-08-restructure-test-folder-separate-setup-from-tests-evaluate-n.md`
**Requirements**: TEST-01, TEST-02, TEST-03, TEST-04
**Depends on:** Phase 6
**Plans:** 7/7 plans complete

Plans:

- [x] 07-01-PLAN.md — Relocate all 5 fixture files into `support/{containers,fixtures,listeners}`, add the shared MockMvc fixture base, delete the 2 empty classes (wave 1; TEST-01, TEST-03, TEST-04) — Assumption A2 proven true
- [x] 07-02-PLAN.md — Merge the 3 auth/session classes into one in-process `AuthenticationE2ETest` and falsify the session-strategy coverage (wave 2; TEST-02, TEST-03) — falsification probe confirmed the merged tests genuinely exercise `SessionAuthenticationStrategy`
- [x] 07-03-PLAN.md — Downgrade the Column family: `ColumnDeletionE2ETest`, `ColumnOrderingE2ETest` (wave 2; TEST-03)
- [x] 07-04-PLAN.md — Downgrade the Task family: `TaskLockingE2ETest`, `TaskOrderingE2ETest`, `TaskMoveE2ETest` (wave 2; TEST-03)
- [x] 07-05-PLAN.md — Downgrade `BoardFullReadE2ETest` and `SubtaskLockingE2ETest`; keep `BoardCreationE2ETest` on the real-socket tier (wave 2; TEST-03)
- [x] 07-06-PLAN.md — Downgrade `ThemePersistenceE2ETest` and `ActivityReadE2ETest` (wave 2; TEST-03)
- [x] 07-07-PLAN.md — Full `spotlessCheck` + `clean test` gate (278/278 tests, no shrinkage), documentation reconciliation, service/controller/e2e test-tier decision guide added to `docs/CODE_STYLE.md`, filed the E2ETest-suffix/fastTest-filter follow-up todo (wave 3; TEST-01..04)

### Phase 07.1: Address hard blockers and inconsistencies from the frontend-integration-readiness audit: add CORS configuration for local frontend dev origins with credentials support; add @Valid to signin/signup request bodies; converge the error response envelope in GlobalExceptionHandler to one consistent shape; split the overloaded 401 into 401 (unauthenticated) vs 403 (forbidden-ownership); make create-endpoint success status codes consistently 201; close the optimistic-locking gap on Board/User entities to match Column/Task/Subtask; add security/injection-attempt test coverage and thorough auth-gating test coverage proving every protected endpoint rejects both unauthenticated requests and cross-user access. (INSERTED)

**Goal:** Every error the API returns is one consistent RFC 7807 envelope with a stable machine-readable code; 401 means unauthenticated and 403 means forbidden, with no overlap; a local frontend dev server can make credentialed cross-origin calls; signin/signup actually validate their request bodies; every resource-creating POST returns 201; Board carries the same optimistic locking Column/Task/Subtask already have; and adversarial injection resistance plus complete auth gating are proven by tests rather than assumed.
**Requirements**: D-01..D-26 (CONTEXT.md decisions — this inserted phase has no REQUIREMENTS.md IDs), plus ROADMAP-201 (the create-endpoint 201 item named in this phase's title, which has no D-number)
**Depends on:** Phase 7
**Plans:** 7/9 plans executed (7 waves)

Plans:
**Wave 1**

- [x] 07.1-01-PLAN.md — Converge GlobalExceptionHandler on RFC 7807 ProblemDetail with a stable code taxonomy; remap ownership denials 401→403 (wave 1; D-01, D-02, D-03, D-05)
- [x] 07.1-02-PLAN.md — CorsConfigurationSource bean with credentialed, property-driven explicit origins (wave 1; D-10, D-11, D-12)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 07.1-03-PLAN.md — ProblemDetail-producing AuthenticationEntryPoint so unauthenticated requests return a real 401 (wave 2; D-04, D-05)
- [x] 07.1-04-PLAN.md — @Valid on signin/signup, duplicate-email 409, and the fixture hardening validation forces (wave 2; D-06, D-07, D-08, D-09, D-20)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 07.1-05-PLAN.md — Board optimistic locking: V7 migration, @Version, version on the board DTOs (wave 3; D-13, D-14, D-15)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 07.1-06-PLAN.md — Create-endpoint status consistency: column and subtask creation return 201 (wave 4; ROADMAP-201)

**Wave 5** *(blocked on Wave 4 completion)*

- [x] 07.1-07-PLAN.md — Retarget the pre-commit fastTest gate from name-suffix to JUnit @Tag; rename the 11 mistiered classes (wave 5; D-21, D-22, D-23)

**Wave 6** *(blocked on Wave 5 completion)*

- [ ] 07.1-08-PLAN.md — InjectionAttemptTest and AuthorizationGatingTest, sweeping every protected route (wave 6; D-16, D-17, D-18, D-19, D-20)

**Wave 7** *(blocked on Wave 6 completion)*

- [ ] 07.1-09-PLAN.md — /claude-security scan and triage, then four Mermaid sequence diagrams in docs/ARCHITECTURE.md (wave 7; D-24, D-25, D-26)
