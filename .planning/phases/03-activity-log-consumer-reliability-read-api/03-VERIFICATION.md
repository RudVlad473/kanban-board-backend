---
phase: 03-activity-log-consumer-reliability-read-api
verified: 2026-08-02T18:00:00Z
status: passed
score: 9/9 must-haves verified
behavior_unverified: 0
overrides_applied: 0
human_verification:

  - test: "Run docs/plans/backend-modernization/03-activity-log-ddl.sql via psql against the REAL Postgres deploy-target database (not H2, not a local dev instance) before this phase's PR merges."
    expected: "\\d activity_log shows the table, the unique constraint uk_activity_log_event_id on event_id, and the idx_activity_log_board_created_id index on (board_id, created_at DESC, id DESC)."
    why_human: "The real Postgres profile has no spring.jpa.hibernate.ddl-auto set, so Hibernate will never create this table in production. The H2 test profile's create-drop schema generation makes every test in this phase pass regardless of whether this manual step has been done, so a fully green suite proves nothing about the production database. Master auto-deploys to EC2 on every push (per .github/workflows/deploy.yml), so if this table is missing at deploy time, every consumed Kafka event silently exhausts retries and lands on the dead-letter topic instead of ever being persisted — a total feature outage that superficially looks like 'the dead-letter path works.' This cannot be verified from the codebase; it requires access to the real deploy-target database, which this agent does not have."
---

# Phase 3: Activity Log Consumer, Reliability & Read API Verification Report

**Phase Goal:** Board owners can view a durable, deduplicated, paginated activity log covering every mutation type, poison messages are isolated to a dead-letter topic instead of stalling the pipeline, and the full producer-to-persistence path is proven correct against a real Kafka broker.
**Verified:** 2026-08-02T18:00:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A `TaskMovedEvent` published to `kanban.activity` through a REAL Kafka broker results in exactly one persisted `activity_log` row (TEST-01, ACTLOG-02) | ✓ VERIFIED | Full `./gradlew test` run against live Docker/Testcontainers Kafka: `ActivityLogConsumerE2ETest$OnActivityEventTest` — 9/9 tests passing, 0 failures, 0 errors (`build/test-results/test/TEST-...OnActivityEventTest.xml`) |
| 2 | All five `ActivityEvent` types map to a persisted row via an exhaustive switch with no `default` arm — a sixth event type is a compile error | ✓ VERIFIED | `ActivityLogConsumer.deriveActionAndDetailIds` reviewed: `switch (event) { case TaskCreatedEvent ... case TaskMovedEvent ... case TaskDeletedEvent ... case BoardCreatedEvent ... case ColumnCreatedEvent ... }` with no `default`/`case null` branch. Compiles and passes with all 5 types exercised in `ActivityLogConsumerE2ETest`. |
| 3 | `event_id` carries a database-level UNIQUE constraint in both the entity and the hand-written Postgres DDL | ✓ VERIFIED | `ActivityLogEntity.java:59`: `@Column(nullable = false, unique = true) private UUID eventId;`. `03-activity-log-ddl.sql:37`: `event_id uuid NOT NULL CONSTRAINT uk_activity_log_event_id UNIQUE`. |
| 4 | Production DDL bridge script exists, is idempotent, and carries a runbook header | ✓ VERIFIED | `docs/plans/backend-modernization/03-activity-log-ddl.sql` exists (45 lines), every statement uses `IF NOT EXISTS`, header covers WHAT THIS IS / WHEN TO RUN / WHAT THIS IS NOT / SAFE TO RE-RUN. |
| 5 | A redelivered `eventId` is a silent no-op via `existsByEventId` fast path + `DataIntegrityViolationException` backstop — **and** a genuine constraint violation (e.g. `NOT NULL`) is NOT silently absorbed | ✓ VERIFIED | `ActivityLogRecorder.record()` re-checks `existsByEventId` inside the catch block before absorbing (CR-01 fix, commit `fe2fcf2`); rethrows if the row is still absent. Proven live: `ActivityLogIdempotencyE2ETest$RedeliveryTest` (2/2 passing) and `$ConcurrentRecordTest` (1/1 passing, genuine two-thread race via `CountDownLatch`/`ExecutorService`). |
| 6 | `ErrorHandlingDeserializer` wraps the consumer's deserializer so a poison message is delivered as a routable failure instead of stalling the poll loop | ✓ VERIFIED | `application.properties`/`application-test.properties`: both key/value deserializers set to `ErrorHandlingDeserializer` with delegate classes configured; `trusted.packages` scoped to `com.vrudenko.kanban_board.event` (never a wildcard). |
| 7 | `DefaultErrorHandler` retries 3x at ~1s backoff then routes to `kanban.activity.dlt` via `DeadLetterPublishingRecoverer`; both topics are explicit `NewTopic` beans | ✓ VERIFIED | `KafkaConsumerConfig.activityErrorHandler`: `new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L))`; two `TopicBuilder.name(...).partitions(1).replicas(1)` beans for `ACTIVITY` and `ACTIVITY_DLT`. Proven live: `ActivityLogDeadLetterE2ETest$DeadLetterRoutingTest` (1/1). |
| 8 | Dead-lettered record carries the ORIGINAL bytes, byte-for-byte, not base64-re-encoded | ✓ VERIFIED | `deadLetterKafkaTemplate` uses `LinkedHashMap`-ordered `DelegatingByTypeSerializer` with `byte[].class → ByteArraySerializer` taking priority; `@Qualifier("deadLetterKafkaTemplate")` fixes bean-resolution ambiguity (found+fixed during Plan 02). Proven live: `ActivityLogDeadLetterE2ETest$DeadLetterFidelityTest` asserts `Assertions.assertThat(deadLetteredValue).isEqualTo(poisonBytes)` — 1/1 passing. |
| 9 | A poison message does not block the pipeline — a well-formed event published after it is still consumed | ✓ VERIFIED | `ActivityLogDeadLetterE2ETest$NonBlockingTest` — 1/1 passing, asserts the well-formed event's row exists after the poison record on the same single partition. |
| 10 | A null-valued (tombstone) record creates no row and does not stall the consumer | ✓ VERIFIED | `ActivityLogDeadLetterE2ETest$TombstoneTest` — 1/1 passing. |
| 11 | `GET /boards/{boardId}/activity` returns 200 with a `Page` body, authorized via `OwnershipVerifierService.verifyOwnershipOfBoard` (READ-01) | ✓ VERIFIED | `ActivityController`/`ActivityLogService` reviewed; `ActivityReadE2ETest$FindAllByBoardIdTest` — 7/7 passing (owner-reads, ordering, ownership-rejection/not-found, page-boundary, empty-board, clamping, caller-sort-override). |
| 12 | The endpoint is paginated using standard Spring Data `Pageable`, offset-based (READ-02) | ✓ VERIFIED | `ActivityController` takes a `Pageable` parameter; `ActivityLogService` builds `PageRequest.of(pageNumber, pageSize, deterministicSort)`; `spring.data.web.pageable.max-page-size=100` clamps page size. Proven live by the clamping and page-boundary tests. |
| 13 | Feed is newest-first with a deterministic total order (`createdAt DESC, id DESC`) — no duplicate/skipped rows across page boundaries even with identical timestamps | ✓ VERIFIED | `ActivityLogService.findAllByBoardId` discards caller sort, builds `Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))`. Proven live: `shouldReturnEveryRowExactlyOnce_whenManyRowsShareTheSameInstant` (7 same-instant rows, page size 3, no dupes/skips) and `shouldIgnoreCallerSuppliedSort_andStayNewestFirst`. |
| 14 | Consumer package depends on nothing but the `event` package, `ActivityLogRecorder`, `ObjectMapper` — never a domain repository/service | ✓ VERIFIED | `grep -rqE 'TaskRepository|BoardRepository|ColumnRepository|UserRepository|TaskService|BoardService|ColumnService|UserService|OwnershipVerifierService' src/main/java/com/vrudenko/kanban_board/activitylog/` — zero matches. |

**Score:** 14/14 truths verified (0 present-but-behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `entity/ActivityAction.java` | Closed 5-constant enum | ✓ VERIFIED | Exactly `TASK_CREATED, TASK_MOVED, TASK_DELETED, BOARD_CREATED, COLUMN_CREATED` |
| `entity/ActivityLogEntity.java` | Insert-only row, unique `eventId` | ✓ VERIFIED | `unique = true`, `EnumType.STRING`, all 6 non-null columns present |
| `repository/ActivityLogRepository.java` | `existsByEventId` + `findAllByBoardId` | ✓ VERIFIED | Both derived methods present, no extra finder added by Plan 03 |
| `activitylog/ActivityLogRecorder.java` | Idempotent persist, never throws on duplicate | ✓ VERIFIED | Post-CR-01-fix: only absorbs the exception when `existsByEventId` confirms the row is present |
| `activitylog/ActivityLogConsumer.java` | `@KafkaListener`, exhaustive switch | ✓ VERIFIED | 108 lines, no default arm, `LinkedHashMap` insertion-ordered detail map |
| `config/KafkaConsumerConfig.java` | `NewTopic` beans + DLT wiring | ✓ VERIFIED | 202 lines; post-review-fix includes `@Primary`/`@Qualifier` disambiguation, `DisposableBean` lifecycle management for the dead-letter producer factory (WR-01 fix) |
| `docs/plans/backend-modernization/03-activity-log-ddl.sql` | Idempotent production DDL bridge | ✓ VERIFIED | 45 lines, `IF NOT EXISTS` throughout, full runbook header |
| `test/.../ActivityLogConsumerE2ETest.java` | Real-broker E2E proof | ✓ VERIFIED | 356 lines, 9/9 tests passing live |
| `test/.../ActivityLogIdempotencyE2ETest.java` | Redelivery + concurrency proof | ✓ VERIFIED | 268 lines, 4/4 tests passing live (min_lines: 80 exceeded) |
| `test/.../ActivityLogDeadLetterE2ETest.java` | Poison/DLT proof | ✓ VERIFIED | 228 lines, 4/4 tests passing live (min_lines: 90 exceeded) |
| `dto/activity_dto/ActivityLogResponseDTO.java` | Exactly 5 D-10 fields | ✓ VERIFIED | `eventId, action, detail, userId, createdAt` — no `id`, no `boardId`, no `@JsonInclude` |
| `mapper/ActivityLogMapper.java` | MapStruct Spring mapper | ✓ VERIFIED | `ComponentModel.SPRING`, `ReportingPolicy.IGNORE` |
| `controller/ActivityController.java` | Authenticated `GET` endpoint | ✓ VERIFIED | `@PreAuthorize("isAuthenticated()")`, `@Validated` (WR-02 fix applied), route-constant-driven |
| `test/.../ActivityReadE2ETest.java` | Real-HTTP proof | ✓ VERIFIED | 289 lines, 7/7 tests passing live (min_lines: 130 exceeded) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `ActivityLogConsumer` | `kanban.activity` topic | `@KafkaListener(topics = KafkaTopics.ACTIVITY, ...)` | ✓ WIRED | Confirmed in source |
| `ActivityLogConsumer` | `ActivityLogRecorder` | `activityLogRecorder.record(entity)` | ✓ WIRED | Confirmed, and proven live via all E2E suites |
| `KafkaConsumerConfig` | `kanban.activity.dlt` topic | `DeadLetterPublishingRecoverer` destination resolver → `KafkaTopics.ACTIVITY_DLT` | ✓ WIRED | Confirmed + proven live via `ActivityLogDeadLetterE2ETest` |
| `ActivityLogRecorder` | `ActivityLogRepository` | `existsByEventId` fast path, `saveAndFlush` backstop | ✓ WIRED | Confirmed + proven live |
| `ActivityController` | `ActivityLogService` | `activityLogService.findAllByBoardId(...)` | ✓ WIRED | Confirmed + proven live |
| `ActivityLogService` | `OwnershipVerifierService` | `verifyOwnershipOfBoard` first statement, unmodified | ✓ WIRED | `OwnershipVerifierService.java` confirmed unmodified by phase (`git diff` clean); called before any repository access |
| `ActivityLogService` | `ActivityLogRepository` | `findAllByBoardId` with service-constructed deterministic `Pageable` | ✓ WIRED | Confirmed + proven live via page-boundary and caller-sort tests |

### Behavioral Spot-Checks / Full Test Suite Execution

Rather than spot-checking individual commands, the verifier ran the phase's own acceptance-criteria command directly against a live Docker/Testcontainers Kafka broker (Docker Desktop confirmed running, `docker version` reachable):

| Command | Result | Status |
|---------|--------|--------|
| `./gradlew spotlessCheck test` (full suite, real broker via Testcontainers) | `BUILD SUCCESSFUL in 4m 1s`; 79 test-result XML files, 0 failures, 0 errors anywhere in the suite | ✓ PASS |
| `ActivityLogConsumerE2ETest$OnActivityEventTest` | `tests="9" failures="0" errors="0"` | ✓ PASS |
| `ActivityLogIdempotencyE2ETest` (Redelivery/Concurrent/Fresh nested classes) | `tests="1+1+2=4" failures="0" errors="0"` | ✓ PASS |
| `ActivityLogDeadLetterE2ETest` (Routing/Fidelity/NonBlocking/Tombstone nested classes) | `tests="1+1+1+1=4" failures="0" errors="0"` | ✓ PASS |
| `ActivityReadE2ETest$FindAllByBoardIdTest` | `tests="7" failures="0" errors="0"` | ✓ PASS |
| `git diff --name-only -- build.gradle` (phase-wide, since before first Phase 3 commit) | no output | ✓ PASS — no dependency changes required, as the plan claimed |

This directly falsifies the "task completion ≠ goal achievement" risk for this phase: every claimed behavioral proof (redelivery idempotency, concurrent-race constraint arbitration, dead-letter routing + byte fidelity + non-blocking + tombstone handling, ownership/ordering/pagination) was independently re-executed by the verifier against a real broker, not merely re-read from SUMMARY.md.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| ACTLOG-01 | 03-01 | Entity/Repository with UNIQUE eventId constraint | ✓ SATISFIED | `ActivityLogEntity`, `ActivityLogRepository`, DDL script all confirmed |
| ACTLOG-02 | 03-01 | Consumer maps all 5 event types | ✓ SATISFIED | Exhaustive switch, no default, all 5 types tested live |
| ACTLOG-03 | 03-01, 03-02 | Idempotency via unique constraint, exists-check as fast path | ✓ SATISFIED | CR-01-fixed recorder + live redelivery/concurrency proofs |
| READ-01 | 03-03 | Paginated, ownership-authorized read endpoint | ✓ SATISFIED | `ActivityController`/`ActivityLogService`, live E2E proof |
| READ-02 | 03-03 | Standard `Pageable` offset pagination | ✓ SATISFIED | Deterministic two-key sort, live page-boundary + clamping proofs |
| RELY-01 | 03-01, 03-02 | Dead-letter topic isolates poison messages | ✓ SATISFIED | `KafkaConsumerConfig` DLT wiring, live non-blocking + routing proofs |
| RELY-02 | 03-02 | Test proves dead-letter path, not just configuration | ✓ SATISFIED | `ActivityLogDeadLetterE2ETest`, all 4 nested cases live-verified |
| TEST-01 | 03-01 | Testcontainers E2E test for real-broker persistence | ✓ SATISFIED | `ActivityLogConsumerE2ETest`, 9/9 live |
| TEST-02 | 03-02 | Redelivery test asserts exactly one row | ✓ SATISFIED | `ActivityLogIdempotencyE2ETest$RedeliveryTest`, live |

No orphaned requirements — all 9 phase-declared requirement IDs (ACTLOG-01/02/03, READ-01/02, RELY-01/02, TEST-01/02) appear in REQUIREMENTS.md's traceability table mapped to Phase 3, and all are satisfied by at least one of the three plans.

### Anti-Patterns Found

None. Scanned all 14 main-source files and 4 test files touched by this phase for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER` (case-insensitive), `return null|return {}|return []|=> {}`, and failure-injection hook identifiers (`failureInjection|forceFailure|testOnlyFail`). Zero matches (one grep hit on the literal substring `23xxx` in `ActivityLogRecorder.java`'s Javadoc — a reference to the SQL state class code, not a debt marker).

### Code Review Findings — All Resolved

`03-REVIEW.md` (2026-08-02) found 1 critical + 3 warning issues; `03-REVIEW-FIX.md` confirms all 4 were fixed and verified with a full green test suite:

- **CR-01** (critical, data-loss): `ActivityLogRecorder` was silently absorbing *any* `DataIntegrityViolationException`, not just the intended unique-constraint race — a `NOT NULL` violation from a semantically-null event field would have vanished with zero trace. **Fixed** (commit `fe2fcf2`): re-checks `existsByEventId` inside the catch block before absorbing; rethrows otherwise. Verified present in current source.
- **WR-01** (resource lifecycle): dead-letter `ProducerFactory` was never a managed bean. **Fixed** (commits `e4fb50b`→revert `c3ecbe9`→corrected `2b46014`): `KafkaConsumerConfig implements DisposableBean`, closes the factory in `destroy()`. Verified present in current source.
- **WR-02** (validation no-op): `ActivityController` missing `@Validated`. **Fixed** (commit `663c25e`). Verified present in current source (`@Validated` at class level).
- **WR-03** (unstable serialization format): raw `Page<T>` response documented as a deliberate convention rather than wrapped, per commit `4cd13e6`. Verified: comment present in current source.

The two Info-level findings (magic numbers, duplicated group-id constant) were explicitly out of scope for the fix pass and remain informational only — no functional or correctness impact.

### Human Verification Required

1. **Run the production DDL bridge script against the real Postgres deploy target before merging this phase's PR.**
   - **Test:** Execute `docs/plans/backend-modernization/03-activity-log-ddl.sql` via `psql` against the real deploy-target Postgres database (not the H2 test profile).
   - **Expected:** `\d activity_log` shows the table with the `uk_activity_log_event_id` unique constraint on `event_id` and the `idx_activity_log_board_created_id` covering index.
   - **Why human:** The real Postgres profile sets no `spring.jpa.hibernate.ddl-auto`, so Hibernate never creates this table in production — only this manual script does. The H2 test profile's `create-drop` schema generation means every automated test in this phase (including the full-suite run this verifier just executed) passes identically whether or not this step has been done, so a green test suite is not evidence this step is complete. This agent has no access to the production database to confirm the table exists there. Both `03-01-SUMMARY.md`, `03-03-SUMMARY.md`, and `03-03-PLAN.md`'s own `<human-check>` block explicitly flag this as an outstanding manual gate carried to phase-end, and nothing in `.planning/STATE.md` or the current git history indicates it has been executed yet.

### Gaps Summary

No gaps. All 14 derived observable truths (roadmap goal + merged plan must-haves across all three plans) are verified against the actual codebase, not just against SUMMARY.md narrative. The verifier independently re-ran the full test suite against a live Testcontainers Kafka broker (not merely trusting the SUMMARY's claimed prior run) and confirmed 0 failures/0 errors across all 79 test result files, including every activity-log-specific test class. All four code-review findings (1 critical, 3 warning) were confirmed fixed in the current source, not just claimed fixed in 03-REVIEW-FIX.md.

The phase is functionally complete and provably correct in the codebase. The sole remaining item is an operational/deployment gate — running the manual DDL script against the real production database — which cannot be verified from source code and is explicitly flagged by the phase's own plan as a pre-merge human checkpoint. This routes the overall status to `human_needed` rather than `passed`, per the verification decision tree (any non-empty human-verification list overrides an otherwise-clean score).

### Acknowledged Gaps

- **Human Verification item #1 (production DDL bridge script)** — acknowledged open, not resolved. The AWS EC2 instance and its Postgres database (the "real deploy target" this check refers to) were deleted by the operator on 2026-08-03 while migrating off AWS due to unpredictable pricing. There is no longer a deploy target for this script to run against, so the check cannot be completed as written and is not being forced closed against infrastructure that no longer exists. The upcoming v1.2 infra-migration milestone (Oracle Cloud + Neon Postgres + self-hosted Redpanda) will define a new deploy target and needs its own equivalent pre-merge DDL verification step before its first production deploy — tracked there, not here. Acknowledged 2026-08-03; phase 3's UAT (`03-UAT.md`) reflects this item as `skipped` with the same reasoning.

---

*Verified: 2026-08-02T18:00:00Z*
*Verifier: Claude (gsd-verifier)*
