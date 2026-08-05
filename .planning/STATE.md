---
gsd_state_version: 1.0
milestone: v1.2
milestone_name: Infra Migration & Schema Registry
current_phase: 04.1
current_phase_name: flyway-database-migration-implementation
status: executing
stopped_at: Phase 04.1 Plan 01 complete (V1 tracer applied and proven)
last_updated: "2026-08-05T11:52:00.000Z"
last_activity: 2026-08-05
last_activity_desc: Completed Phase 04.1 Plan 01 (Flyway tracer slice — V1__init.sql applied to real Postgres, flyway_schema_history proven, H2 test suite unaffected)
progress:
  total_phases: 3
  completed_phases: 1
  total_plans: 13
  completed_plans: 5
  percent: 38
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-03)

**Core value:** Redeploy the app on a cost-guarded, always-free/near-free stack (Oracle Cloud + Neon + self-hosted Redpanda) after the AWS EC2/RDS deletion, and add a Schema Registry (Avro) in front of the Kafka activity-log pipeline to close the schema-evolution risk flagged during v1.1.
**Current focus:** Phase 04.1 — flyway-database-migration-implementation

## Current Position

Phase: 04.1 (flyway-database-migration-implementation) — EXECUTING
Plan: 1 of 3 complete
Status: Plan 01 (Flyway tracer slice) complete — V2/V3/V4 migrations and the ddl-auto=validate cutover remain
Last activity: 2026-08-05 — Completed Phase 04.1 Plan 01

Progress: [██████████] 100%

## Performance Metrics

**Velocity:**

- Total plans completed: 7
- Average duration: 32 min
- Total execution time: 1.6 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1 | 3 | 95min | 32min |
| 2 | TBD | - | - |
| 3 | TBD | - | - |
| 04 | 4 | - | - |
| 5 | TBD | - | - |

**Recent Trend:**

- Last 5 plans: 45min, 35min, 15min
- Trend: Improving

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 01 P01 | 45min | 3 tasks | 11 files |
| Phase 01 P02 | 35min | 3 tasks | 7 files |
| Phase 01 P03 | 15min | 1 tasks | 2 files |
| Phase 02 P01 | 14min | 2 tasks | 13 files |
| Phase 2 P3 | 70min | 2 tasks | 6 files |
| Phase 03 P01 | 25min | 2 tasks | 16 files |
| Phase 03 P02 | 65min | 2 tasks | 4 files |
| Phase 03 P03 | 20min | 2 tasks | 6 files |
| Phase quick-260803-v23 P01 | 45min | 3 tasks | 10 files |
| Phase 04 P01 | 40min | 3 tasks | 8 files |
| Phase 04 P02 | 70min | 3 tasks | 12 files |
| Phase 04 P03 | 40min | 2 tasks | 3 files |
| Phase 04 P04 | 130min | 2 tasks | 4 files |
| Phase quick-260804-p7a P01 | 12min | 2 tasks | 2 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Phase 04.1 Plan 01]: Task 1 checkpoint resolved option-a — approved V1-V4 migration-history split exactly as researched (V1 pre-Epic-2 baseline, V2 optimistic-locking version columns, V3 activity_log, V4 password_hash NOT NULL), one migration per existing manual DDL script in real chronological order. V1__init.sql applied and proven against a genuinely empty docker-compose Postgres (flyway_schema_history: 1|V1__init.sql|t); entity cross-check confirmed exactly six @Entity classes with no uncovered schema delta.
- [Roadmap/v1.2]: Continued phase numbering from v1.1 — this milestone starts at Phase 4, not Phase 1. Split 14 requirements into 2 phases (coarse granularity, matching research's recommendation): Phase 4 = Schema Registry (SCHEMA-01..06 — buildable/verifiable entirely against the local docker-compose stack, no dependency on the new deploy target; the higher-risk, more code-heavy phase since the Avro/sealed-interface mapping layer has no ready-made pattern to copy), Phase 5 = Infra Migration (INFRA-01..08 — pure ops/config, benefits from Schema Registry already being proven locally).
- [Roadmap/v1.2]: Phase 5 explicitly depends on Phase 4 for one narrow cross-phase task — the final cutover step repoints `schema.registry.url` from wherever Phase 4 verified against (local Redpanda or a standalone registry container) to the production Redpanda instance's built-in registry on the Oracle VM, then re-runs Phase 4's verification suite against the real target.
- [Roadmap/v1.1]: Continued phase numbering from v1.0 — this milestone starts at Phase 2, not Phase 1.
- [Roadmap/v1.1]: Split 16 requirements into 2 phases (coarse granularity): Phase 2 = producer side (Kafka infra, domain events, move endpoint), Phase 3 = consumer side (activity log, idempotency, DLT, read API, e2e verification). Research's 5-phase suggestion was compressed to match coarse granularity — TEST-01/02 folded into Phase 3 as additional success criteria rather than a standalone verification-only phase.
- [Roadmap/v1.1]: Phase 2 explicitly depends on Phase 1 — `PATCH /tasks/{taskId}/move` (MOVE-02) reuses the exact explicit `@Version` compare-before-mutate convention delivered in Phase 1, not a new locking path.
- [v1.0/Scope]: Narrow v1.0 to optimistic locking only; defer `/full` endpoint to v2.
- [v1.0/Finding 1]: Ownership chain treated as closed — already 1 query via EAGER joins, no code change.
- [Phase ?]: [Phase 2 Plan 01]: KafkaEventPublisher is the sole Kafka client API touchpoint in src/main — TaskService never imports org.springframework.kafka, confirmed by grep
- [Phase ?]: [Phase 2 Plan 01]: MOVE-03 cross-board guard (400) runs before the MOVE-02 version guard (409) since a wrong-board target is a request-shape problem, not a concurrency one
- [Phase ?]: [Phase 2 Plan 01]: Added AbstractAppTest.createColumnForUser fixture helper since no REST endpoint exists to create a board directly — boards are only created via UserService.addBoardByUserId
- [Phase ?]: [Phase 2 Plan 03]: Fixed Dockerfile's retired openjdk:21-jdk-slim runtime base image to eclipse-temurin:21-jre-jammy - was silently breaking production CI/CD too, not just this plan's local stack
- [Phase ?]: [Phase 2 Plan 03]: kafka service runs as user: root in docker-compose.yml to fix a named-volume permission failure (apache/kafka-native does not pre-create /var/lib/kafka/data, so the Docker volume driver creates it root-owned) - local-dev-only scope, avoids a 4th compose service
- [Phase ?]: [Phase 3 Plan 01]: Fixed KafkaConsumerConfig.deadLetterKafkaTemplate silently suppressing Spring Boot's default KafkaTemplate bean (@ConditionalOnMissingBean(KafkaTemplate.class) is bare-type) — added an explicit @Primary kafkaTemplate bean sourced from the autoconfigured ProducerFactory; a real production bug, not just a test artifact
- [Phase ?]: [Phase 3 Plan 01]: Pinned docker-java's negotiated Docker Engine API version to 1.44 in AbstractKafkaContainerTest (testcontainers-java#11212) to fix Docker Engine 29.x rejecting every Testcontainers transport on Windows — zero host-level Docker Desktop configuration required
- [Phase ?]: [Phase 3 Plan 01]: Replaced an exact-nanosecond Instant equality assertion with AssertJ isCloseTo(within(1, MICROS)) — Kafka JSON serialization + JPA persistence round-trip loses sub-microsecond precision without a consistent truncate-vs-round direction
- [Phase ?]: [Phase 3 Plan 02]: Fixed KafkaConsumerConfig.activityErrorHandler's ambiguous deadLetterKafkaTemplate parameter with @Qualifier - Spring's @Primary disambiguation runs before parameter-name matching, so the dead-letter path was silently using the wrong (JSON/base64-encoding) producer template
- [Phase ?]: [Phase 3 Plan 02]: Replaced AbstractKafkaContainerTest's @Testcontainers/@Container lifecycle with an imperative kafka.start() static initializer - the JUnit5-extension-driven singleton container pattern did not reliably share one broker across three sibling E2E test classes in this environment
- [Phase ?]: [Phase 3 Plan 03]: Service always discards any caller-supplied Pageable sort and substitutes a service-owned two-key Sort (createdAt desc, id desc) - the ULID id tiebreak makes offset pagination a genuine total order instead of merely newest-first
- [Quick/260802-q6n]: Adding ArchUnit's rule-2 layering test surfaced a genuine, previously-unenforced CODE_STYLE.md violation — `SubtaskService.findById(String)` loaded a `SubtaskEntity` via a direct, unverified `subtaskRepository.findById(id)` call with zero production callers. Removed rather than exempted, so the new rule ships at full strength.
- [Quick/260802-qr8]: ErrorProne landed at rung 4 (hard-gate main, warn-only test) of the plan's decision ladder, chosen from measured counts (5 main findings, all genuine and fixed; 27 test findings, dominated by low-value `FutureReturnValueIgnored` on Testcontainers Kafka test sends) rather than guessed — operator confirmed at the plan's blocking decision checkpoint. `compileTestJava` is not currently enforcing; only `compileJava` fails the build on an ERROR-severity finding.
- [Quick/260802-rq5]: Researched the "bump Java 21→25" todo and found it's gated behind a Spring Boot 3.5.x→4.x major upgrade (Java 25 needs Gradle ≥9.1, which Spring Boot 3.5's Gradle plugin doesn't support) plus hard Lombok/soft MapStruct version blockers — not a quick task as originally scoped. Deferred (research only, no code changed); split into Unit A (Lombok/MapStruct/Boot patch bumps, stays on Java 21), Unit B (CI/Docker hygiene — dead `adopt` JDK distribution, Docker base image risk), Unit C (the actual Java 25 + Boot 4.x jump, milestone-sized). Full findings: `.planning/quick/260802-rq5-bump-java-version-from-21-to-25-current-/260802-rq5-RESEARCH.md`; updated todo carries the split.
- [Quick/260802-ryf]: Researched "enable virtual threads" — DO NOT ENABLE YET. HikariCP 6.3.0 (Boot 3.5.0-pinned) has an open, unfixed upstream carrier-saturation issue under virtual threads on JDK 21 (fixed only in 7.1.0); only one harmless `synchronized` exists in `src/main` (not the risk); PostgreSQL driver and the Kafka publish path (moved off-thread in Phase 3) are both clean. No load-test harness exists in this repo to measure any benefit anyway. Blocked on the same Java 25/Boot 4.x upgrade as 260802-rq5, which resolves both remaining blockers (JEP 491 + HikariCP ≥7.1.0) at once. Deferred, no code changed. **Real side-finding surfaced by this research:** `spring.session.store-type=jdbc` is configured and CLAUDE.md/code comments describe sessions as Postgres-persisted for horizontal scaling, but `org.springframework.session` is absent from the runtime classpath — sessions are actually in-memory Tomcat `HttpSession`. Session loss on every restart; would not survive scaling past one instance. Fixed in Quick/260802-shl below.
- [Quick/260802-shl]: Wired `spring-session-jdbc` for real (operator chose "fix it" over "document the bug") rather than adding a third manual DDL script — Spring Session's own `initialize-schema=always` initializer is `continueOnError`-safe against its non-idempotent `CREATE TABLE` schema script, so no new manual pre-merge production step was introduced. Proven by `SessionPersistenceE2ETest` (schema creation, real JDBC row on signin, SecurityContext persisted, no bcrypt hash leaked into the store). Effective session idle timeout silently changes from 1 minute (Tomcat) to 180 minutes (`spring.session.timeout`) now that Spring Session is present — intentional per the pre-existing property, now actually in force. **New finding while fixing this:** the documented "max 2 concurrent sessions" ceiling (`maximumSessions(2)`) is *also* unenforced — the custom `AuthenticationController.signin` path never invokes a `SessionAuthenticationStrategy`, so no session-fixation protection or session limit actually runs on login. Proven by a tripwire test designed to go red when someone fixes it. Filed as a security-marked todo (`2026-08-02-wire-session-authentication-strategy-into-custom-signin.md`), not fixed here — out of a quick task's blast radius, touches every request's auth path.
- [Quick/260802-tbj]: Removed the `synchronized` modifier from `RandFlakeGenerator.generateRandflake()` — re-verified independently (not just trusting 260802-ryf's research) that it guards zero shared mutable state; was a pointless serialization point on every entity insert (Hibernate `IdentifierGenerator`).
- [Quick/260803-m3i]: Deleted the two dead, hash-less `UserMapper` overloads (`fromSigninRequestDTO`, and `fromSignupRequestDTO(SigninRequestDTO)`) — re-verified zero callers by fresh grep before deleting. Tightened `UserEntity.passwordHash` to `@Column(nullable = false)` and delivered a guarded production DDL bridge (`docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql`). Falsified by hand: temporarily reverted the column to nullable, confirmed the full test suite stayed green (proving the suite is silent on this property), then restored non-nullable and reran `spotlessCheck`/`test` green. **The production database migration is NOT applied** — only the codebase half shipped; running the DDL script via psql against the real Postgres instance, immediately before merge/deploy, remains an outstanding human pre-merge action (RO-4). Discovered while re-verifying callers: `UserMapper`'s two entity-to-request-DTO methods (`toSigninRequestDTO`, `toSignupRequestDTO`) are also uncalled and copy the bcrypt hash into a `password`-named DTO field via MapStruct name matching — filed as a new security-marked todo rather than fixed, since this task's scope was locked to the two named overloads.
- [Quick/260803-ns9]: Deleted both `UserMapper` entity-to-request-DTO methods (`toSigninRequestDTO`, `toSignupRequestDTO`) rather than exempting them with `@Mapping(ignore = true)` — operator's decision, and consistent with `260803-m3i`'s disposition of the sibling hash-less overloads in the same file. Zero callers were re-verified by fresh grep immediately before deleting, not inherited from the prior task's claim. The class Javadoc now carries a forward-looking invariant note (naming neither deleted method) so an equivalent mapping cannot regrow unnoticed. Critically: nothing leaked — there were no callers, so this closes a latent vector, not a live breach.
- [Fast/2026-08-03]: `.githooks/pre-commit` now runs `./gradlew test --exclude-tests '*E2ETest'` after `spotlessApply`, blocking the commit on failure — resolves the open trade-off in the "gate on tests" todo (full suite too slow vs. compile-only missing ArchUnit) by running everything except the Testcontainers-backed E2E classes, hook-scoped only. `build.gradle`'s default `test` task is untouched, so CI and direct `./gradlew test` still run the full suite including E2E.
- [Phase ?]: [Quick/260803-v23]: D-01 resolved as promote-five (Approach B) — removing compileTestJava's Error Prone severity demotion alone was a measured no-op against the 27-finding test-source backlog (all WARNING severity by default), so the five triaged checks (FutureReturnValueIgnored, StringCaseLocaleUsage, MissingOverride, NotJavadoc, DefaultCharset) were additionally promoted to ERROR, scoped by name so a future error_prone_core version bump cannot red the build on its own. Added a shared AbstractKafkaContainerTest.sendAndAwaitAck helper to fix 16 FutureReturnValueIgnored Kafka-send findings; deliberately left 2 executor.submit futures in a concurrency race test fire-and-forget (blocking would destroy the race window). Teeth-checked the promotion by reintroducing and reverting one finding. Full ./gradlew test runtime unaffected by ack-checked sends (208s vs 210s baseline, within noise). Closed the source todo with its incorrect premise corrected.
- [Phase ?]: [Phase 04 Plan 01]: Both uuid and timestamp-millis Avro logical types produce native UUID/Instant accessors under gradle-avro-plugin 1.9.1 + Avro 1.12.1 -- no manual conversion code needed in the mapper
- [Phase ?]: [Phase 04 Plan 01]: Bumped commons-lang3 test pin from 3.0 to 3.18.0 -- Spring Boot's dependency-management plugin was forcing the ancient pin project-wide, and org.apache.avro's transitive commons-compress needed ArrayFill (3.11+), breaking 3 Testcontainers Kafka E2E tests
- [Phase ?]: [Phase 4 Plan 02]: Spring Boot 3.5.0 ships a RedpandaContainerConnectionDetailsFactory (wires Kafka bootstrap-servers via @ServiceConnection) but no equivalent ConnectionDetails type for a schema registry -- schema.registry.url wired via @DynamicPropertySource instead
- [Phase ?]: [Phase 4 Plan 02]: Measured, not assumed: bounding CachedSchemaRegistryClient's own retry/timeout defaults (independent of Kafka producer bounds) did not reduce full-suite wall-clock -- those registry-lookup retries run on the async kafkaPublishExecutor thread, off the critical path. Full suite measured 231-237s post-cutover vs ~208s pre-phase baseline, a real ~11% regression explained by the new tracer test class + one-time Redpanda startup, not the catastrophic unbounded-block failure mode the threat model worried about
- [Phase ?]: [Phase 4 Plan 02]: ActivityLogConsumerE2ETest's timestamp tolerance widened from 1 microsecond to 1 millisecond -- Avro's timestamp-millis logical type truncates to millisecond precision by design, a real property of the schema already confirmed in Plan 01, not a regression
- [Phase ?]: [Phase 4 Plan 03]: @DynamicPropertySource methods across a class hierarchy are discovered/invoked subclass-first, superclass-last (opposite of @BeforeAll) -- a subclass overriding the same property key as its superclass always loses; fixed via a documented, test-scoped mutable static override field with an @AfterAll reset instead
- [Phase ?]: [Phase 4 Plan 03]: A schema-registry lookup failure during Avro serialization throws synchronously from KafkaTemplate.send() (before any delivery future exists), never reaching KafkaEventPublisher's whenComplete callback -- caught instead by Spring's default @Async uncaught-exception handler. D-01's user-facing guarantee still holds; documented as a finding, no production code changed
- [Phase ?]: [Phase 4 Plan 04]: Rehearsal's JPA datasource is the app's default (non-test) config via DB_HOST/DB_NAME/DB_USER/DB_PASS rather than new wiring -- achieved by never setting spring.profiles.active=test on the rehearseHistoricalSchemas Gradle task
- [Phase ?]: [Phase 4 Plan 04]: Per-row Avro round trip in step 2 calls KafkaAvroSerializer/Deserializer directly in-memory against the real registry rather than publishing every row through the topic, keeping the strictness gate at Avro's build() without per-row Kafka latency; only a small final sample goes through the real topic end-to-end
- [Phase ?]: [Quick/260804-p7a]: Disabled deploy-to-ec2 CI job (if: false) — AWS EC2 host was deleted; picked comment+if:false over commenting out the block (breaks needs: chain), a repo-variable gate (unauditable), or deletion (loses reference material). Filed resolves_phase:5, severity:major todo tracking the rewrite plus two side-findings: Docker Hub tag accumulation (cleanup jobs also skip) and a pre-existing truncated curl -X DELETE in cleanup-unused-image.

### Pending Todos

- [minor] Create a sequence diagram documenting the full system flow — deferred until all functional epics of the backend modernization plan are complete and the project is ready for frontend hand-off. See `.planning/todos/pending/2026-08-01-create-sequence-diagram-documenting-full-system-flow-for-fro.md`.
- [minor] Bump Java version from 21 to 25 (current LTS) — build.gradle toolchain, Dockerfile (both stages), and CI `java-version` all pinned to 21; not urgent (21 LTS supported until ~2028), but worth doing proactively. See `.planning/todos/pending/2026-08-01-bump-java-version-from-21-to-25-current-lts.md`.
- [minor] Account for schema evolution risk when changing ActivityEvent shapes — a rolling deploy that renames/retypes an event field while old-shape messages are still unconsumed can dead-letter valid (non-poison) messages; Kafka itself enforces no schema. Directly addressed by v1.2 Phase 4 (Schema Registry) — expect this todo to close at that phase's transition. See `.planning/todos/pending/2026-08-01-account-for-schema-evolution-risk-when-changing-activityeven.md`.
- [minor] Enable virtual threads in Spring Boot config (`spring.threads.virtual.enabled=true`) — evaluate JDBC/Hibernate and Spring Session JDBC blocking-call pinning risk first. See `.planning/todos/pending/2026-08-02-enable-virtual-threads-in-spring-boot-config.md`.
- [minor] Use a Snowflake ID generator for activity log events (`eventId`) instead of UUID — for index locality and time-ordering; see also the general note about adopting this as the project's default ID-generation strategy. See `.planning/todos/pending/2026-08-02-use-snowflake-id-generator-for-activity-log-events.md`.
- [minor] Add a dependency vulnerability scan (OWASP dependency-check or similar) — no scan exists today despite several manually-pinned third-party libs. See `.planning/todos/pending/2026-08-03-add-dependency-vulnerability-scan.md`.
- [minor] Evaluate PMD/Checkstyle/SpotBugs — likely redundant given Error Prone + ArchUnit + `docs/CODE_STYLE.md`; only revisit if a concrete gap surfaces. See `.planning/todos/pending/2026-08-03-evaluate-pmd-checkstyle-spotbugs.md`.
- [minor] Create high-level infra architecture diagram before live infra onboarding — Mermaid C4-style diagram(s) of the Oracle VM boundary (app + Redpanda + Caddy) + Neon + GitHub Actions, optionally plus a Kafka/Schema-Registry data-flow diagram. Trigger: before Phase 5's actual live infra onboarding, not just planning it. See `.planning/todos/pending/2026-08-04-create-high-level-infra-architecture-diagram-before-live-inf.md`.
- [minor] Explore an alert-service integration as a separate microservice — speculative/theoretical, not scoped; a genuine second consumer of `kanban.activity` if pursued, useful primarily as a technology-exploration vehicle (multi-consumer schema compatibility, service-to-service auth, alerting tech). Revisit only if there's a concrete reason to test one of those technologies. See `.planning/todos/pending/2026-08-04-explore-alert-service-as-a-separate-microservice-for-tech-ex.md`.

### Blockers/Concerns

Carried from research (address during Phase 4/5 planning):

- Oracle A1 Flex tenancy shape (OCPU/RAM) must be re-verified in-console before finalizing Redpanda `--memory`/`--smp` resource caps — the publicly reported 2 OCPU/12 GB post-halving figure is MEDIUM confidence, not vendor-confirmed for this specific tenancy.
- Avro/sealed-interface mapping-layer design (hand-authored mapper vs. `@org.apache.avro.reflect.Union` reflection) has no ready-made tooling shortcut — needs its own design pass at Phase 4 planning time, not a mechanical conversion.
- Compatibility mode choice (BACKWARD vs. FULL) is a genuine unresolved tension between research's two source files (FEATURES.md recommends BACKWARD as the topology-matching default; PITFALLS.md argues FULL may be safer given producer/consumer are the same redeployed app) — Phase 4 planning must make and document an explicit choice, not default to either unreviewed.
- Confluent `kafka-avro-serializer` exact patch version and Confluent-client-vs-Redpanda-registry edge cases (map-field Protobuf, Avro namespace-tag handling per GH issues #5771/#11912) should be smoke-tested against the real Redpanda registry before committing, even though this project's Avro usage (no map fields) is not directly implicated.
- Production (deploy-target) Kafka/Redpanda config is new for this milestone — the existing dev `docker-compose.yml` Kafka block is being replaced wholesale for Phase 5, not edited in place; do not reuse it unmodified as a deploy artifact.
- [Phase 4 Plan 04]: SCHEMA-06's live rehearsal against real historical data was not run this session -- a pre-existing native Windows PostgreSQL 17 service on this sandbox machine already owns port 5432, intercepting connections meant for the docker-compose Postgres container, and admin privileges to stop it were unavailable. A human must run ./gradlew rehearseHistoricalSchemas once against a real local Postgres (in an environment without this port conflict) before Phase 5's cutover.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260801-gby | Create docs/CODE_STYLE.md with agent code-style preferences (enums over magic constants), referenced from CLAUDE.md | 2026-08-01 | 685b471 | [260801-gby-create-docs-code-style-md-with-agent-cod](./quick/260801-gby-create-docs-code-style-md-with-agent-cod/) |
| 260801-gib | Append rules 2-7 to docs/CODE_STYLE.md (ownership-verified loading, AssertJ/catchException, no-mocks, @Nested/AAA, Update*RequestDTO shape, Optional isEmpty()-guard) and fix bare-int HTTP status literals in TaskLockingE2ETest/ColumnLockingE2ETest to use HttpStatus enum constants | 2026-08-01 | 85ed93f | [260801-gib-survey-the-repo-for-existing-code-conven](./quick/260801-gib-survey-the-repo-for-existing-code-conven/) |
| 260801-k93 | Reword hiring-context language ("interview-defensible", "interview prep" headings, etc.) out of 17 git-tracked docs to neutral technical phrasing, preserving meaning | 2026-08-01 | 1bdfb79 | [260801-k93-remove-interview-related-language-from-d](./quick/260801-k93-remove-interview-related-language-from-d/) |
| 260802-pw0 | Auto-configure git core.hooksPath so the pre-commit hook needs no manual per-clone step | 2026-08-02 | ea64adc | [260802-pw0-auto-configure-git-core-hookspath-so-the](./quick/260802-pw0-auto-configure-git-core-hookspath-so-the/) |
| 260802-q6n | Add ArchUnit to enforce documented layering and ownership-verification rules (also fixed a genuine pre-existing CODE_STYLE rule-2 violation in SubtaskService) | 2026-08-02 | c3780d7 | [260802-q6n-add-archunit-to-enforce-documented-layer](./quick/260802-q6n-add-archunit-to-enforce-documented-layer/) |
| 260802-qr8 | Add ErrorProne for compile-time bug detection — measured 5 main / 27 test findings, hard-gated compileJava (main sources), warn-only on compileTestJava | 2026-08-02 | 46f4d80 | [260802-qr8-add-errorprone-for-compile-time-bug-dete](./quick/260802-qr8-add-errorprone-for-compile-time-bug-dete/) |
| 260802-shl | Fix the dead Spring Session JDBC configuration — wired spring-session-jdbc for real (sessions now persist to Postgres, surviving redeploys); discovered and documented that the concurrent-session ceiling is also unenforced | 2026-08-02 | 500fde5 | [260802-shl-fix-the-dead-spring-session-jdbc-configu](./quick/260802-shl-fix-the-dead-spring-session-jdbc-configu/) |
| 260802-tbj | Remove the pointless synchronized modifier from RandFlakeGenerator.generateRandflake (guarded no shared state) | 2026-08-02 | 501b53f | [260802-tbj-remove-the-pointless-synchronized-modifi](./quick/260802-tbj-remove-the-pointless-synchronized-modifi/) |
| 260803-l6f | Add UserPersistenceE2ETest proving HTTP signup persists a real bcrypt hash to USERS.PASSWORD_HASH and signup-then-signin round-trips; filed a security-marked deferred todo for a dead hash-less UserMapper overload and the nullable passwordHash column | 2026-08-03 | fb186df,19dfb14 | [260803-l6f-add-a-test-proving-password-hash-is-pers](./quick/260803-l6f-add-a-test-proving-password-hash-is-pers/) |
| 260803-m2z | Wire a SessionAuthenticationStrategy into AuthenticationController.authenticate — the concurrent-session ceiling (maximumSessions(2)) and session-fixation protection now actually run on both signin and signup, proven by SessionPersistenceE2ETest.ConcurrentSessionCeiling (rewritten from tripwire to spec) and a new SessionFixation test; corrected both CLAUDE.md claims and closed the source todo | 2026-08-03 | 0260df7,a09c7e3,6747117 | [260803-m2z-wire-a-sessionauthenticationstrategy-int](./quick/260803-m2z-wire-a-sessionauthenticationstrategy-int/) |
| 260803-m3i | Deleted the two dead hash-less UserMapper overloads (re-verified zero callers by fresh grep); tightened UserEntity.passwordHash to non-nullable with a guarded production DDL bridge script (04-password-hash-not-null-ddl.sql, NOT executed against any database); falsified the new constraint by hand (reverted, confirmed suite silent, restored); closed the source todo; filed a new security-marked todo for the entity-to-request-DTO hash-leak found while re-verifying | 2026-08-03 | c9615a3,baab313 | [260803-m3i-delete-the-dead-hash-less-usermapper-ove](./quick/260803-m3i-delete-the-dead-hash-less-usermapper-ove/) |
| 260803-ns9 | Deleted UserMapper.toSigninRequestDTO(UserEntity) and toSignupRequestDTO(UserEntity), both uncalled and both compiled by MapStruct into dto.setPassword(entity.getPassword()); removed the now-dead SigninRequestDTO import; added a class Javadoc invariant naming neither deleted method; closed the source todo | 2026-08-03 | e500858 | [260803-ns9-delete-dead-usermapper-entity-to-request](./quick/260803-ns9-delete-dead-usermapper-entity-to-request/) |
| 260803-v23 | Hard-gated compileTestJava on Error Prone: drove all 27 test-source findings to zero (25 fixed in source, 2 deliberately dropped with written reasons), added AbstractKafkaContainerTest.sendAndAwaitAck to fix 16 Kafka-send findings, promoted 5 triaged checks to ERROR severity (teeth-checked), closed the source todo with corrected premise | 2026-08-03 | c5bc467,d7bf17b | [260803-v23-hard-gate-compiletestjava-error-prone-fi](./quick/260803-v23-hard-gate-compiletestjava-error-prone-fi/) |
| 260804-nd3 | Remapped docker-compose Postgres to host port 5433 and parameterized the JDBC port (native Windows PostgreSQL 17 owned 5432, silently intercepting the container) — unblocked Phase 4 Plan 04-04's stalled human-check. Found the historical activity_log corpus was empty (destroyed by 04-04's own `docker compose down -v`); generated a real 6-row/5-action-type corpus by exercising the running local app, then reran `rehearseHistoricalSchemas` — BUILD SUCCESSFUL, zero errors, zero dead-lettered. Documented caveat: this corpus proves the reconstructor/round-trip, not compatibility with genuinely pre-cutover row shapes (none exist anymore in this environment) | 2026-08-04 | ffa5587 | [260804-nd3-remap-docker-compose-yml-postgres-host-p](./quick/260804-nd3-remap-docker-compose-yml-postgres-host-p/) |
| 260804-oq0 | Added `.dev/gsd-run.sh`, a sourceable shim wrapping the GSD runtime resolver (20 candidate paths + PATH fallback) so bash blocks can `source .dev/gsd-run.sh` instead of re-pasting the full one-liner; sourced failure returns 1 without killing the caller shell, direct execution still exits 1. Documented in CLAUDE.md's GSD Execution Directives section (placed after the GSD:workflow-end marker so it survives regeneration) | 2026-08-04 | 272ff9a,b12d25e | [260804-oq0-add-a-committed-dev-gsd-run-sh-shim-scri](./quick/260804-oq0-add-a-committed-dev-gsd-run-sh-shim-scri/) |
| 260804-oy8 | Added `docs/SESSION_LESSONS.md` capturing two git-hygiene lessons from today's Phase 4 execution session (push periodically to avoid worktree fork-base divergence disabling parallel execution; never git-commit on the main tree while a sequential executor is mid-task) so they're durable in git history, not just external agent memory. Pointed CLAUDE.md at the new doc | 2026-08-04 | 9348807,2aa28cb | [260804-oy8-create-docs-session-lessons-md-capturing](./quick/260804-oy8-create-docs-session-lessons-md-capturing/) |
| 260804-p7a | Disabled deploy-to-ec2 CI job (if: false) with explanatory comment — AWS EC2 host was deleted, so pushes to master stop failing on it; tests and Docker build/push are unaffected. Filed a resolves_phase:5, severity:major todo tracking the Phase 5 rewrite, the Docker Hub tag-accumulation side effect, and a pre-existing truncated curl -X DELETE defect | 2026-08-04 | c350940,6ad98ae | [260804-p7a-disable-the-deploy-to-ec2-job-in-github-](./quick/260804-p7a-disable-the-deploy-to-ec2-job-in-github-/) |

### Roadmap Evolution

- Phase 04.1 inserted after Phase 4: Flyway database migration implementation - paused Phase 5 (Infra Migration) to deliver this for resume purposes (URGENT)

## Deferred Items

Items acknowledged and carried forward:

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Endpoint | `GET /boards/{boardId}/full` nested read (FULL-01..03) | Deferred to v2 | 2026-07-31 |
| Epics | Modernization Epics 3–7 (Flyway/OpenAPI, Redis, Testcontainers project-wide, Observability, K8s) | Deferred to future milestones | 2026-07-31 |
| Kafka | Production (EC2) Kafka deployment (KAFKA-V2-01) | Deferred to v2 | 2026-08-01 |
| Kafka | Cursor/keyset pagination on activity feed (PAGE-V2-01) | Deferred to v2 | 2026-08-01 |
| UAT | Phase 3 human-verification item (production DDL bridge script) | Superseded — deploy target deleted, addressed by v1.2 Phase 5 (INFRA-06) | 2026-08-03 |
| Quick task | 260801-p03-add-explicit-comments-to-taskservice-upd (missing summary) | Acknowledged at v1.1 close | 2026-08-03 |
| Quick task | 260802-rq5-bump-java-version-from-21-to-25-current- (research-only, no summary artifact) | Acknowledged at v1.1 close | 2026-08-03 |
| Quick task | 260802-ryf-enable-virtual-threads-in-spring-boot-co (research-only, no summary artifact) | Acknowledged at v1.1 close | 2026-08-03 |
| Todo | 5 pending todos (schema evolution risk, Java 25 bump, sequence diagram, virtual threads, Snowflake IDs) | Acknowledged at v1.1 close — remain in `.planning/todos/pending/`; schema-evolution-risk todo now targeted by v1.2 Phase 4 | 2026-08-03 |
| Seed | SEED-001 Confluent Schema Registry (Avro/Protobuf) | Promoted into v1.2 scope (Phase 4: SCHEMA-01..06) | 2026-08-04 |
| Infra Polish | INFRA-V2-01..03 (full observability stack, blue-green deploys, multi-broker Redpanda HA) | Deferred to v2 per v1.2 REQUIREMENTS.md | 2026-08-03 |
| Schema Registry Polish | SCHEMA-V2-01..02 (pre-merge schema-compatibility CI check, documented compatibility-mode rationale) | Deferred to v2 per v1.2 REQUIREMENTS.md | 2026-08-03 |

**Known verification overrides: 9 (see above)**

## Session Continuity

Last session: 2026-08-05T11:52:00.000Z
Stopped at: Phase 04.1 Plan 01 complete (V1 tracer applied and proven)
Resume file: .planning/phases/04.1-flyway-database-migration-implementation/04.1-01-SUMMARY.md

## Operator Next Steps

- Plan/execute Phase 04.1 Plan 02/03 to add V2__add_optimistic_locking_version_columns.sql, V3__add_activity_log.sql, V4__add_password_hash_not_null.sql, and the ddl-auto=validate cutover (D-03)

</content>
