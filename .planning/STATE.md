---
gsd_state_version: 1.0
milestone: v1.1
milestone_name: Kafka Activity Feed
current_phase: 03
current_phase_name: activity-log-consumer-reliability-read-api
status: verifying
stopped_at: Completed 03-03-PLAN.md (Phase 3 complete)
last_updated: "2026-08-03T17:20:00.000Z"
last_activity: 2026-08-03
last_activity_desc: Completed quick task 260803-ns9 (deleted the two dead UserMapper entity-to-request-DTO methods that copied the bcrypt hash into a password-named DTO field via MapStruct name matching; removed the now-dead SigninRequestDTO import; added a class Javadoc invariant note; closed the source todo)
progress:
  total_phases: 3
  completed_phases: 2
  total_plans: 6
  completed_plans: 6
  percent: 67
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-01)

**Core value:** Deliver a real, event-driven per-board activity log (Kafka + consumer + idempotent persistence), plus the genuinely-missing "move task between columns" endpoint, as Epic 1 of the backend modernization plan.
**Current focus:** Phase 03 — activity-log-consumer-reliability-read-api

## Current Position

Phase: 03 (activity-log-consumer-reliability-read-api) — EXECUTING
Plan: 3 of 3
Status: Phase complete — ready for verification
Last activity: 2026-08-02 — Phase 03 execution started

Progress: [██████████] 100%

## Performance Metrics

**Velocity:**

- Total plans completed: 3
- Average duration: 32 min
- Total execution time: 1.6 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1 | 3 | 95min | 32min |
| 2 | TBD | - | - |
| 3 | TBD | - | - |

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

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

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

### Pending Todos

- [minor] Create a sequence diagram documenting the full system flow — deferred until all functional epics of the backend modernization plan are complete and the project is ready for frontend hand-off. See `.planning/todos/pending/2026-08-01-create-sequence-diagram-documenting-full-system-flow-for-fro.md`.
- [minor] Bump Java version from 21 to 25 (current LTS) — build.gradle toolchain, Dockerfile (both stages), and CI `java-version` all pinned to 21; not urgent (21 LTS supported until ~2028), but worth doing proactively. See `.planning/todos/pending/2026-08-01-bump-java-version-from-21-to-25-current-lts.md`.
- [minor] Account for schema evolution risk when changing ActivityEvent shapes — a rolling deploy that renames/retypes an event field while old-shape messages are still unconsumed can dead-letter valid (non-poison) messages; Kafka itself enforces no schema. See `.planning/todos/pending/2026-08-01-account-for-schema-evolution-risk-when-changing-activityeven.md`.
- [minor] Enable virtual threads in Spring Boot config (`spring.threads.virtual.enabled=true`) — evaluate JDBC/Hibernate and Spring Session JDBC blocking-call pinning risk first. See `.planning/todos/pending/2026-08-02-enable-virtual-threads-in-spring-boot-config.md`.
- [minor] Use a Snowflake ID generator for activity log events (`eventId`) instead of UUID — for index locality and time-ordering; see also the general note about adopting this as the project's default ID-generation strategy. See `.planning/todos/pending/2026-08-02-use-snowflake-id-generator-for-activity-log-events.md`.
- [major] Hard-gate `compileTestJava` Error Prone findings — currently warning-only via `allErrorsAsWarnings = true`, with a measured 27-finding backlog documented in `build.gradle`. See `.planning/todos/pending/2026-08-03-hard-gate-compiletestjava-error-prone-findings.md`.
- [minor] Add a dependency vulnerability scan (OWASP dependency-check or similar) — no scan exists today despite several manually-pinned third-party libs. See `.planning/todos/pending/2026-08-03-add-dependency-vulnerability-scan.md`.
- [minor] Evaluate PMD/Checkstyle/SpotBugs — likely redundant given Error Prone + ArchUnit + `docs/CODE_STYLE.md`; only revisit if a concrete gap surfaces. See `.planning/todos/pending/2026-08-03-evaluate-pmd-checkstyle-spotbugs.md`.

### Blockers/Concerns

Carried from research (address during Phase 2/3 planning):

- Exact KRaft env-var set and internal-vs-external listener config for `apache/kafka-native` was only web-search-sourced (LOW confidence) — pull the reference compose YAML from `apache/kafka`'s own repo before finalizing Phase 2 planning.
- `DefaultErrorHandler`/`DeadLetterPublishingRecoverer` retry-before-DLT tuning is thinly sourced (LOW-MEDIUM) — worth a research pass during Phase 3 planning to avoid stale `SeekToCurrentErrorHandler`-era tutorials.
- Cursor vs. offset pagination for `GET /boards/{boardId}/activity` is underspecified by the epic; this milestone ships offset `Pageable` per REQUIREMENTS.md (PAGE-V2-01 defers keyset pagination to v2) — confirm during Phase 3 planning before the repository query shape is locked in.
- Production (EC2) Kafka deployment is explicitly out of scope for v1.1 (KAFKA-V2-01) — the dev `docker-compose.yml` must not be reused unmodified as a deploy artifact without a review pass.

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

## Deferred Items

Items acknowledged and carried forward:

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Endpoint | `GET /boards/{boardId}/full` nested read (FULL-01..03) | Deferred to v2 | 2026-07-31 |
| Epics | Modernization Epics 3–7 (Flyway/OpenAPI, Redis, Testcontainers project-wide, Observability, K8s) | Deferred to future milestones | 2026-07-31 |
| Kafka | Production (EC2) Kafka deployment (KAFKA-V2-01) | Deferred to v2 | 2026-08-01 |
| Kafka | Cursor/keyset pagination on activity feed (PAGE-V2-01) | Deferred to v2 | 2026-08-01 |

## Session Continuity

Last session: 2026-08-02T14:54:40.173Z
Stopped at: Completed 03-03-PLAN.md (Phase 3 complete)
Resume file: None

## Operator Next Steps

- Run `/gsd-plan-phase 2` to plan Kafka Foundation, Domain Events & Move Endpoint
