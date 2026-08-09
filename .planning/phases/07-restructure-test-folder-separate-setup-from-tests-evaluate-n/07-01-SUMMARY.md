---
phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n
plan: 01
subsystem: testing
tags: [junit5, mockmvc, testcontainers, spring-boot-test, restassured, refactor]

# Dependency graph
requires: []
provides:
  - "support/containers/, support/fixtures/, support/listeners/ populated with all five shared test-infrastructure files (D-01 complete)"
  - "AbstractAppMockMvcTest — the shared MockMvc fixture base every tier-downgrade in plans 02-06 builds on"
  - "ColumnLockingE2ETest converted to the in-process tier as the worked reference for plans 02-06"
  - "Assumption A2 (RESEARCH.md) empirically proven true: Spring Boot 3.5.0 auto-configures the full security filter chain under @AutoConfigureMockMvc with spring-security-test on the classpath — no .apply(springSecurity()) needed"
  - "Real brand-new-import vs edited-import counts (21 new / 22 edited) replacing RESEARCH.md's ~25/~22 estimate"
affects: [07-02, 07-03, 07-04, 07-05, 07-06, 07-07]

# Actuals (#2632)
actuals:
  tokens: 14237
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Three-way-split support/ package (containers/ | fixtures/ | listeners/) for shared test infrastructure, replacing flat/interspersed placement"
    - "AbstractAppMockMvcTest.signinCookie() — real POST /signin through mockMvc.perform(), never .with(user()), for the small set of classes that must keep exercising the real auth path under the in-process tier"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppMockMvcTest.java
  modified:
    - src/test/java/com/vrudenko/kanban_board/support/containers/AbstractPostgresContainerTest.java
    - src/test/java/com/vrudenko/kanban_board/support/containers/AbstractKafkaContainerTest.java
    - src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppTest.java
    - src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/support/listeners/RecordingActivityEventListener.java
    - src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java
    - "36 other test classes with import-only edits (path repoint or brand-new import)"

key-decisions:
  - "Deleted e2e/board/BoardE2ETest.java and service/SubtaskServiceTest.java after re-verifying zero @Test methods and zero external references with a fresh grep immediately before each delete (TEST-04)"
  - "AbstractAppMockMvcTest carries no class-level @SpringBootTest/@AutoConfigureMockMvc, matching AbstractAppE2ETest's own precedent of leaving Spring Boot test annotations to concrete subclasses"

patterns-established:
  - "New shared test-infrastructure files land under support/{containers,fixtures,listeners}/ by concern, never flat or interspersed with concrete test classes"

requirements-completed: [TEST-01, TEST-03, TEST-04]

coverage:
  - id: D1
    description: "All five shared-infrastructure files relocated into support/containers/, support/fixtures/, support/listeners/; whole test tree compiles and non-E2E suite passes"
    requirement: "TEST-01"
    verification:
      - kind: unit
        ref: "./gradlew compileTestJava"
        status: pass
      - kind: unit
        ref: "./gradlew fastTest (76 test classes, includes ActivityEventPublicationTest autowiring the relocated listener)"
        status: pass
      - kind: e2e
        ref: "./gradlew test --tests '*ActivityLogConsumerE2ETest' (proves @DynamicPropertySource discovery survives the AbstractKafkaContainerTest package move)"
        status: pass
    human_judgment: false
  - id: D2
    description: "AbstractAppMockMvcTest exists and is proven by one real converted class (ColumnLockingE2ETest) — the phase-wide proof of RESEARCH.md Assumption A2"
    requirement: "TEST-03"
    verification:
      - kind: e2e
        ref: "./gradlew test --tests '*ColumnLockingE2ETest' — 3/3 tests pass authenticating via real POST /signin + cookie relay under MockMvc"
        status: pass
    human_judgment: false
  - id: D3
    description: "Zero test classes with zero @Test methods remain anywhere under src/test/java"
    requirement: "TEST-04"
    verification:
      - kind: other
        ref: "tree-wide scan: for every concrete (non-abstract, non-@Component) *.java under src/test/java, grep -c '@Test' > 0 — only 2 false positives found, both legitimate non-JUnit-@Test classes (a plain helper class and an ArchUnit @ArchTest class), not stub tests"
        status: pass
    human_judgment: false

# Metrics
duration: 26min
completed: 2026-08-09
status: complete
---

# Phase 7 Plan 1: Fixture Relocation, AbstractAppMockMvcTest, Assumption A2 Proof Summary

**Relocated all five shared test-infrastructure files into a 3-way-split `support/` package, added `AbstractAppMockMvcTest` as the in-process auth fixture, deleted two empty test classes, and empirically proved Assumption A2 (real signin+cookie-relay authentication works under MockMvc) by converting `ColumnLockingE2ETest`.**

## Performance

- **Duration:** 26 min
- **Started:** 2026-08-09T12:14:00Z (approx, first file reads)
- **Completed:** 2026-08-09T12:41:14Z
- **Tasks:** 3
- **Files modified:** 46 (2 created, 2 deleted, 42 modified — includes the 5 moved shared-infrastructure files)

## Accomplishments

- All five shared-infrastructure files (`AbstractPostgresContainerTest`, `AbstractKafkaContainerTest`, `AbstractAppTest`, `AbstractAppE2ETest`, `RecordingActivityEventListener`) relocated into `support/containers/`, `support/fixtures/`, `support/listeners/` respectively — no shared base class or Spring test component remains interspersed with concrete test classes (D-01, TEST-01)
- New `AbstractAppMockMvcTest` fixture base created, providing two `signinCookie()` overloads that drive a genuine `POST /signin` through `mockMvc.perform()` (never `.with(user())`) — the auth idiom every tier-downgrade in plans 02-06 depends on (TEST-03)
- **Assumption A2 proven true, not assumed:** `ColumnLockingE2ETest` converted from the real-socket RestAssured/RANDOM_PORT tier to the in-process MockMvc tier; all 3 test methods pass (3/3, 0 failures), confirming Spring Boot 3.5.0 auto-configures the full `springSecurityFilterChain` under `@AutoConfigureMockMvc` with `spring-security-test` on the classpath, with no `.apply(springSecurity())` call needed
- Two empty test classes deleted (`e2e/board/BoardE2ETest.java`, `service/SubtaskServiceTest.java`), each re-verified zero `@Test` methods and zero external references via a fresh grep immediately before deletion — closes TEST-04
- Zero production-code changes: `git diff` confirms nothing under `src/main/java` was touched

## Task Commits

Each task was committed atomically:

1. **Task 1: Relocate AbstractPostgresContainerTest into support/containers — one class, every touch-point type** - `c0cf3ea` (refactor)
2. **Task 2: Relocate the remaining four shared-infrastructure files and repair every import in the tree** - `15c117e` (refactor)
3. **Task 3: Add the shared MockMvc fixture base, delete the two empty test classes, and prove both by converting ColumnLockingE2ETest** - `747445d` (feat)

**Plan metadata:** (pending — final docs commit follows this SUMMARY)

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppMockMvcTest.java` - New in-process auth fixture base (created)
- `src/test/java/com/vrudenko/kanban_board/support/containers/AbstractPostgresContainerTest.java` - Moved from root package; Javadoc `{@link}` tags repointed to final destinations
- `src/test/java/com/vrudenko/kanban_board/support/containers/AbstractKafkaContainerTest.java` - Moved from `activitylog/`; Javadoc repointed
- `src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppTest.java` - Moved from root package
- `src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppE2ETest.java` - Moved from root package
- `src/test/java/com/vrudenko/kanban_board/support/listeners/RecordingActivityEventListener.java` - Moved from flat `support/`
- `src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java` - Converted RestAssured/RANDOM_PORT → MockMvc/AbstractAppMockMvcTest, same 3 assertions preserved
- `src/test/java/com/vrudenko/kanban_board/e2e/board/BoardE2ETest.java` - Deleted (0 `@Test` methods)
- `src/test/java/com/vrudenko/kanban_board/service/SubtaskServiceTest.java` - Deleted (0 `@Test` methods)
- 36 other test classes across `controller/`, `service/`, `security/`, `event/`, `e2e/*/`, `activitylog/`, and root package - import-only edits (path repoint or brand-new import), listed in full below

## Decisions Made

- Deleted the two empty test classes outright rather than leaving a TODO comment (RESEARCH.md offered both options) — nothing referenced them and D-04 scopes this phase to executing, not deferring, decided work
- `AbstractAppMockMvcTest` carries no class-level `@SpringBootTest`/`@AutoConfigureMockMvc`, matching `AbstractAppE2ETest`'s own precedent, per the plan's explicit instruction

## Deviations from Plan

None — plan executed exactly as written. Both empirical proofs the plan called for (Kafka `@DynamicPropertySource` discovery across the package move; Assumption A2 authentication under MockMvc) came back positive on the first run, so no fallback branch (replan to 11/11 KEEP/DOWNGRADE split) was needed.

## Issues Encountered

- The pre-commit hook's `fastTest` run (`.githooks/pre-commit`) takes ~3.5 minutes per commit against the real Testcontainers PostgreSQL/Redpanda stack; the first commit attempt hit this session's default 3-minute bash timeout and was retried with an extended timeout. A stray Gradle daemon left over from the timed-out run briefly held a file lock on `build/test-results/fastTest/binary/output.bin`; resolved with `./gradlew --stop` before retrying. No code change required — a tooling/environment note only, consistent with `docs/SESSION_LESSONS.md`'s existing guidance on this repo's git/build hygiene.

## Import Blast-Radius: Real Counts (per plan's `<output>` requirement)

RESEARCH.md estimated ~25 brand-new imports and ~22 edited imports. Real counts, measured directly against the diff:

- **21 files** needed a brand-new import line (previously relied on implicit same-package visibility before their base class moved out):
  - 4 for `AbstractPostgresContainerTest` (Task 1): `AbstractAppTest.java`, `EventIdGeneratorTest.java`, `FlywaySchemaProvenanceTest.java`, `KanbanBoardApplicationTests.java`
  - 1 for `AbstractAppTest` (Task 2): `ActivityLogCleanupIsolationTest.java`
  - 7 for `AbstractAppE2ETest` (Task 2): `BoardCreationE2ETest.java`, `BoardFullReadE2ETest.java`, `ColumnDeletionE2ETest.java`, `ColumnOrderingE2ETest.java`, `SubtaskLockingE2ETest.java`, `TaskOrderingE2ETest.java`, `ThemePersistenceE2ETest.java`
  - 9 for `AbstractKafkaContainerTest` (Task 2): all 9 files under `activitylog/` extending it
- **22 import lines** were path-only edits (file already carried an explicit import):
  - 1 for `AbstractPostgresContainerTest` (Task 1): `activitylog/AbstractKafkaContainerTest.java`
  - 12 for `AbstractAppTest` (Task 2): 4 `controller/*ControllerTest.java`, `event/ActivityEventPublicationTest.java`, `security/AuthenticationControllerTest.java`, 6 `service/*ServiceTest.java`
  - 7 for `AbstractAppE2ETest` (Task 2): `e2e/activity/ActivityReadE2ETest.java`, `e2e/board/BoardE2ETest.java`, `e2e/column/ColumnLockingE2ETest.java`, `e2e/task/TaskLockingE2ETest.java`, `e2e/task/TaskMoveE2ETest.java`, `security/SessionPersistenceE2ETest.java`, `security/UserPersistenceE2ETest.java`
  - 2 for `RecordingActivityEventListener` (Task 2): `e2e/task/TaskMoveE2ETest.java`, `event/ActivityEventPublicationTest.java` (same two files already counted above for their `AbstractAppE2ETest`/`AbstractAppTest` edit — no new files, just a second import line each)
- **1 file** used a fully-qualified-name edit shape instead of an import line: `event/avro/ActivityEventAvroMapperTest.java` (`extends com.vrudenko.kanban_board...AbstractPostgresContainerTest` in both its `extends` clause and its Javadoc)

The edited-import count (22) matches RESEARCH.md's estimate exactly. The brand-new-import count (21) came in slightly below the ~25 estimate.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `support/` package fully populated per D-01; plans 02-06 can now relocate/edit files against stable final import paths
- `AbstractAppMockMvcTest` proven working end-to-end; plans 02 and 06 (which RESEARCH.md flagged as dependent on Assumption A2) may proceed as scoped — no replan to the 11/11 KEEP/DOWNGRADE split is needed
- `ColumnLockingE2ETest` stands as the worked MockMvc-conversion reference for the remaining downgrade classes named in RESEARCH.md's verdict table
- TEST-04 closed; no remaining empty test-stub classes anywhere under `src/test/java`

---
*Phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n*
*Completed: 2026-08-09*

## Self-Check: PASSED

- All 8 claimed files confirmed present on disk (5 relocated support/ files, AbstractAppMockMvcTest, ColumnLockingE2ETest, this SUMMARY.md)
- Both deleted files (`e2e/board/BoardE2ETest.java`, `service/SubtaskServiceTest.java`) confirmed absent
- All 3 task commit hashes (`c0cf3ea`, `15c117e`, `747445d`) confirmed present in `git log`
