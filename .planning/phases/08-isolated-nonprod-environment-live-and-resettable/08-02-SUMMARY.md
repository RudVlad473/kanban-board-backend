---
phase: 08-isolated-nonprod-environment-live-and-resettable
plan: 02
subsystem: backend
tags: [reset-endpoint, kafka-admin-client, spring-security, spring-profile, testcontainers]

# Dependency graph
requires:
  - phase: 08-01
    provides: the live, isolated nonprod stack (docker-compose.nonprod.yml, .env.nonprod, the nonprod Neon branch, redpanda-nonprod) that Task 3 would have deployed onto
provides:
  - ResetService/ResetTruncateService (@Profile("nonprod")) -- a real-broker, real-Postgres-proven two-store reset (Kafka activity topics + every domain/session table)
  - ResetController + NonprodResetSecurityConfiguration (@Profile("nonprod")) -- POST /admin/reset, shared-secret-authenticated, profile-gated, security-chain-isolated from production
  - Three new test classes proving the 204/403 contract, idempotency, listener-restart, migration-history preservation, and profile-absence in a non-nonprod context
affects: [08-03-live-deploy-and-runbook-record (not yet planned/executed -- Task 3 of this plan is blocked, see below)]

# Actuals (#2632)
actuals:
  tokens: 58000
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "First use of @Profile in src/main -- bean-registration gating rather than the pre-existing spring.profiles.active=test property-file-selection mechanism"
    - "Second, path-scoped SecurityFilterChain with @Order(1), profile-gated so the permit rule itself does not exist outside nonprod, leaving SecurityConfiguration's catch-all chain untouched"
    - "AdminClient.deleteRecords() to a partition's own current high watermark, rather than KafkaAdmin.deleteTopics() (unavailable at this project's spring-kafka 3.3.x) or topic delete/recreate"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/service/ResetService.java
    - src/main/java/com/vrudenko/kanban_board/service/ResetTruncateService.java
    - src/main/java/com/vrudenko/kanban_board/controller/ResetController.java
    - src/main/java/com/vrudenko/kanban_board/security/NonprodResetSecurityConfiguration.java
    - src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetServiceE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetControllerE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/security/ResetEndpointProfileGatingTest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java

key-decisions:
  - "Task 3 (live deploy + curl proof against the real nonprod stack) was not attempted -- its own <precondition> was checked and found unmet: this plan's two commits exist only on this worktree's local branch, not pushed, not merged to master, and origin/master itself is still behind plan 08-01. gh run list confirmed the most recent CI/CD run corresponds to the commit already running in production (6755c84, per 08-01-SUMMARY.md), not this plan's work. Deploying without a real image built from these commits would either fail the pull outright or silently deploy a stale/wrong image -- the plan's own live_infrastructure_context explicitly forbids fabricating a tag or skipping the precondition."
  - "ResetServiceE2ETest and ResetControllerE2ETest both extend AbstractKafkaContainerTest directly (not AbstractAppTest/AbstractAppE2ETest), matching the plan's read_first pointer to that harness's own Javadoc reasoning (no unrelated ~20-entity fixture noise racing the broker under test). Domain fixtures for ResetServiceE2ETest are created explicitly through the real services (UserService/BoardService/ColumnService/TaskService) rather than via a shared fixture base."
  - "TDD RED-then-GREEN was not reconstructed as two separate commits per task, despite this repo's own precedent of test(...)/feat(...) commit pairs for tdd=\"true\" tasks. This repo's pre-commit hook runs fastTest (which requires a full, successful compileTestJava) before allowing any commit -- a test file referencing not-yet-existing production symbols (ApiPaths.RESET, ResetService, ResetTruncateService for Task 1; ResetController, NonprodResetSecurityConfiguration for Task 2) cannot compile, so a true compile-failing RED commit cannot pass this repo's own hook. Reconstructing a deliberately-broken-but-compiling stub purely to manufacture an intermediate failing-test commit was judged not worth the added complexity once the full, real implementation was already written and independently verified end-to-end against a real Testcontainers Postgres + Redpanda broker. Both tasks were committed as a single feat(...) commit each instead. See TDD Gate Compliance below."

requirements-completed: [RESET-01 (partial -- code delivered and proven in tests; live deployment/curl proof deferred, see Deviations)]

coverage:
  - id: D-01
    description: "Reset endpoint authenticates via a shared-secret header (X-Reset-Token), not session auth, an IP allowlist, or hostname obscurity"
    requirement: "RESET-01"
    verification:
      - kind: test
        ref: "ResetControllerE2ETest.ResetEndpoint (should_return204AndEmptyStores_when_calledWithTheCorrectToken, should_return403ProblemDetail_when_tokenIsWrong, should_return403ProblemDetail_when_headerIsAbsent, should_return403_when_tokenIsBlank)"
        status: pass
    human_judgment: false
  - id: D-02
    description: "Reset endpoint is profile-gated -- the controller/services/security-chain beans do not exist at all outside the nonprod profile, independent of the token check"
    requirement: "RESET-01"
    verification:
      - kind: test
        ref: "ResetEndpointProfileGatingTest.BeanRegistration (should_registerNoResetBeans_when_nonprodProfileIsInactive, should_registerNoResetSecurityChain_when_nonprodProfileIsInactive)"
        status: pass
    human_judgment: false
  - id: D-03
    description: "Reset target state is genuinely empty -- every domain table, activity_log, and both Spring Session tables truncate to zero rows; both Kafka activity topics trim to their own high watermark; no reseed/fixture data"
    requirement: "RESET-01"
    verification:
      - kind: test
        ref: "ResetServiceE2ETest.ResetAllTest.should_emptyBothStores_when_resetAllCalledAfterRealTraffic, .should_trimBothTopicsToZeroRecords_when_resetAllCalled"
        status: pass
    human_judgment: false
  - id: RESET-01-live
    description: "A live curl against the deployed nonprod hostname returns 204 and empties both stores; the same call against production does not return 204"
    requirement: "RESET-01"
    verification:
      - kind: other
        ref: "NOT PERFORMED -- Task 3's precondition (a Docker Hub image built from this plan's commits) is unmet; see Deviations and the checkpoint below"
        status: fail
    human_judgment: true

# Metrics
duration: ~75min
completed: 2026-08-18
status: incomplete
---

# Phase 8 Plan 2: Isolated Nonprod Environment, Live and Resettable Summary

**A profile-gated, shared-secret-authenticated `POST /admin/reset` endpoint that truncates nonprod's
Postgres tables and trims its Kafka activity topics to zero, proven by real-broker/real-Postgres
tests and an HTTP-level contract test -- but not yet deployed or curl-proven against the live nonprod
stack, because Task 3's own precondition (an image built from this plan's commits) is unmet.**

## Performance

- **Duration:** ~75 min (Tasks 1-2; Task 3 not attempted)
- **Completed:** 2026-08-18 (Tasks 1-2 only)
- **Tasks:** 2 of 3 (Task 3 blocked -- see Deviations)
- **Files modified:** 8 (7 created, 1 modified)

## Accomplishments

- `ResetTruncateService` (`@Profile("nonprod")`): one transactional native `TRUNCATE` across
  `users`, `boards`, `columns`, `tasks`, `subtasks`, `activity_log`, `spring_session_attributes`,
  `spring_session`, deliberately excluding Flyway's own migration-bookkeeping table (proven absent
  by a negative grep gate, `entityManager.flush()`/`clear()` around the statement matching
  `TaskService.deleteAllByColumn`'s documented discipline).
- `ResetService` (`@Profile("nonprod")`): stops every `@KafkaListener` container, trims
  `kanban.activity` and `kanban.activity.dlt` to their own current high watermark via
  `AdminClient.deleteRecords()` (tolerating a not-yet-created topic as already-empty), delegates to
  `ResetTruncateService`, and restarts every listener container in a `finally` block so a failure
  never leaves the consumer permanently stopped.
- `ResetController` + `NonprodResetSecurityConfiguration` (both `@Profile("nonprod")`): a
  `POST /admin/reset` route authenticated by a constant-time (`MessageDigest.isEqual`) shared-secret
  header comparison, with an absent header routed to the identical 403 path as a wrong one (no
  presence oracle), a `@PostConstruct` guard rejecting a null/blank/under-32-character configured
  token, and a second `@Order(1)` `SecurityFilterChain` scoped only to this route --
  `SecurityConfiguration.java` itself is untouched (`git diff --name-only` empty).
- Three new test classes: `ResetServiceE2ETest` (7 cases, real Testcontainers Postgres + Redpanda),
  `ResetControllerE2ETest` (6 cases, real-socket HTTP + real broker), `ResetEndpointProfileGatingTest`
  (2 cases, proves zero reset beans register outside `nonprod`). All 15 pass.
- Full `./gradlew test` (466 tests across the whole suite, including the JaCoCo coverage ratchet and
  all four ArchUnit `LayeringArchTest`/`TestPlacementArchTest` rules) is green.

## Task Commits

Each task was committed atomically:

1. **Task 1: Two-store truncate -- ResetService, ResetTruncateService, and a real-broker proof** -
   `65e3370` (feat)
2. **Task 2: The endpoint -- profile-gated controller, constant-time token check, and a
   profile-absence proof** - `818c14a` (feat)

_Note: both tasks carry `tdd="true"` in the plan; see "TDD Gate Compliance" below for why each
landed as one `feat(...)` commit rather than a separate `test(...)`/`feat(...)` pair._

## TDD Gate Compliance

Neither task's test-then-implement split is visible as two separate commits in `git log`, unlike
this repository's own established `test(...)`/`feat(...)` commit-pair precedent for `tdd="true"`
tasks (e.g. `fbce9bf test(06-02): ...` followed by `6333735 feat(06-02): ...`). Reason: this
repository's `.githooks/pre-commit` hook runs `fastTest`, which requires `compileTestJava` to
succeed, before it will allow any commit. Both new test classes reference production symbols
(`ApiPaths.RESET`, `ResetService`, `ResetTruncateService` for Task 1; `ResetController`,
`NonprodResetSecurityConfiguration` for Task 2) that did not exist until the same task's
implementation half landed -- a test-only commit at either task boundary would not compile and
could not pass this repo's own hook. The tests were still written first in this session (per the
plan's own `<behavior>` blocks) and iterated against the real implementation until every case
passed; what's missing from the historical record is only the separate RED commit, not the RED
step itself. Both tasks' final states were independently verified: `./gradlew test --tests
'...ResetServiceE2ETest'` (7/7), `./gradlew test --tests '...ResetControllerE2ETest'
--tests '...ResetEndpointProfileGatingTest' --tests '...architecture.*'` (all green), and a full
`./gradlew test` run (466/466, JaCoCo ratchet passing) after both commits.

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java` - added `RESET = "/admin/reset"`
- `src/main/java/com/vrudenko/kanban_board/service/ResetTruncateService.java` - new, `@Profile("nonprod")` transactional Postgres truncate
- `src/main/java/com/vrudenko/kanban_board/service/ResetService.java` - new, `@Profile("nonprod")` orchestration (listener pause/resume + Kafka trim + Postgres truncate)
- `src/main/java/com/vrudenko/kanban_board/controller/ResetController.java` - new, `@Profile("nonprod")` POST /admin/reset handler
- `src/main/java/com/vrudenko/kanban_board/security/NonprodResetSecurityConfiguration.java` - new, `@Profile("nonprod")` `@Order(1)` path-scoped security chain
- `src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetServiceE2ETest.java` - new, real-broker/real-Postgres proof
- `src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetControllerE2ETest.java` - new, real-socket HTTP contract proof
- `src/test/java/com/vrudenko/kanban_board/security/ResetEndpointProfileGatingTest.java` - new, profile-absence proof

## Decisions Made

- Extended `AbstractKafkaContainerTest` directly for both new E2E test classes rather than
  `AbstractAppTest`/`AbstractAppE2ETest`, matching the plan's own `read_first` pointer to that
  harness's Javadoc (the ~20-entity fixture set `AbstractAppTest` creates per test method would
  otherwise race unrelated Kafka publish traffic against the very broker these tests assert on).
- Task 1's `ResetServiceE2ETest` builds its own domain fixture (one user/board/column/task/subtask
  chain) directly through the real services (`UserService`, `BoardService`, `ColumnService`,
  `TaskService`) rather than a shared fixture base, since no such base exists on this harness.
- Reworded `ResetTruncateService`'s Javadoc to describe Flyway's migration-bookkeeping table without
  spelling out its literal name, after discovering the plan's own negative grep gate
  (`grep -c 'flyway_schema_history' ...` must equal `0`) would otherwise fail against the
  explanatory comment itself -- the identical class of self-referential gate collision Plan 08-01
  hit and fixed the same way for its Caddyfile wildcard comment.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `ResetTruncateService`'s own explanatory Javadoc tripped the plan's negative grep gate**
- **Found during:** Task 1, after writing the class and running the plan's own verify command
  (`test "$(grep -c 'flyway_schema_history' ...)" = "0"`)
- **Issue:** The Javadoc paragraph explaining why the migration-history table is excluded from the
  `TRUNCATE` list spelled out the literal table name, which is exactly the string the gate checks
  is absent -- so the explanatory comment itself would have failed the gate it was explaining.
- **Fix:** Reworded the paragraph to describe the table by its purpose ("Flyway's own
  migration-bookkeeping table") without ever spelling out the literal name, noting the gate's
  existence in the same sentence so a future reader understands why.
- **Files modified:** `ResetTruncateService.java`
- **Verification:** `grep -c 'flyway_schema_history' src/main/java/.../ResetTruncateService.java`
  returns `0`; full `./gradlew test` re-run after the fix stayed green (466/466).
- **Committed in:** `65e3370` (Task 1 commit)

### Blocking Issues (not auto-fixed -- checkpoint below)

**2. [Precondition unmet] Task 3's live-deploy precondition could not be satisfied**
- **Found during:** Task 3, before any live-infrastructure action was taken
- **Issue:** Task 3's `<precondition>` requires "the commit from Tasks 1-2 has been built and pushed
  to Docker Hub as `rudenkovladimir/kanban-board-backend:<tag>` ... and that tag is resolvable by
  `docker pull` from the VM." This plan's two commits exist only on this worktree's local branch
  (`worktree-agent-a6ea9cdf861ee2364`), never pushed to `origin`, never merged to `master`. `origin/master`
  itself (`6755c84`) is still behind plan 08-01's own work. `gh run list --workflow=deploy.yml`
  confirms the most recent CI/CD run built the commit already running in production
  (`6755c84`, per `08-01-SUMMARY.md`), not anything from this plan.
- **Action taken:** None on the live nonprod/production infrastructure. No VM SSH session was
  opened, no reset token was generated, `docker-compose.nonprod.yml`/`.env.nonprod` were not
  touched, and `docs/INFRA_RUNBOOK.md` was not edited. This matches the plan's own explicit
  instruction: "do not fabricate a tag or skip the precondition -- halt with a clear checkpoint
  report."
- **Resolution required:** A human (or the orchestrator, once this worktree's commits are merged to
  `master`) needs to either (a) merge this plan's two commits to `master` and let the existing
  `deploy.yml` CI job build and push a real image tag, then re-dispatch Task 3 with that tag, or
  (b) build and push the image manually from these exact commits. Task 3 cannot be completed inside
  this worktree-isolated session.

---

**Total deviations:** 1 auto-fixed (bug), 1 blocking (Task 3 precondition, unresolved -- see
checkpoint)
**Impact on plan:** Tasks 1-2 are complete, fully tested, and merge-ready. Task 3 (live deploy +
curl proof + runbook record) did not run at all. RESET-01 is therefore only partially delivered by
this plan: the mechanism exists and is proven in tests, but has not yet been proven live against the
deployed nonprod stack, and `docs/INFRA_RUNBOOK.md` carries no record of it yet.

## Issues Encountered

- **Pre-commit hook timed out on the first Task 1 commit attempt (3 min tool timeout), then the
  plain retry passed clean.** Same pattern `docs/SESSION_LESSONS.md` lesson 2's timeout corollary
  and `08-01-SUMMARY.md`'s own "Issues Encountered" describe: `.githooks/pre-commit` runs
  `spotlessCheck` then `./gradlew fastTest`, which can exceed a short default timeout on a cold
  Gradle daemon. Resolved per the documented recovery: `./gradlew --stop`, ran `fastTest` directly
  to confirm a clean pass (4m 28s, `BUILD SUCCESSFUL`), then retried the plain `git commit` with a
  longer timeout -- succeeded.

## User Setup Required

None for Tasks 1-2 (no new environment variables, no new external accounts). Task 3, once
unblocked, will need the operator (or a re-dispatched executor with SSH access already established
by Plan 08-01) to generate `APP_RESET_TOKEN` on the VM and roll `IMAGE_TAG` -- see that task's own
`<action>` steps in `08-02-PLAN.md`, unchanged by this session.

## Next Phase Readiness

- **Not ready to close this plan.** `RESET-01` is only partially satisfied: `ResetController`/
  `ResetService`/`ResetTruncateService` exist, are profile-gated, and are proven correct against
  real infrastructure in tests -- but the plan's own must-have truths that require a *live* curl
  against the deployed nonprod hostname (empty-baseline proof, production-absence proof, the
  `docs/INFRA_RUNBOOK.md` record) are not yet met.
- Task 3 needs this plan's two commits (`65e3370`, `818c14a`) merged to `master`, a resulting CI
  image tag, and then to be re-run (either by a fresh dispatch of this plan's Task 3, or a follow-up
  quick task) against the already-live nonprod stack from Plan 08-01.
- No changes were made to any shared/live infrastructure by this session -- production and the
  already-live nonprod stack from Plan 08-01 are both untouched.

---
*Phase: 08-isolated-nonprod-environment-live-and-resettable*
*Completed: 2026-08-18 (Tasks 1-2 only; Task 3 blocked)*

## Self-Check: PASSED

- FOUND: `src/main/java/com/vrudenko/kanban_board/service/ResetService.java`
- FOUND: `src/main/java/com/vrudenko/kanban_board/service/ResetTruncateService.java`
- FOUND: `src/main/java/com/vrudenko/kanban_board/controller/ResetController.java`
- FOUND: `src/main/java/com/vrudenko/kanban_board/security/NonprodResetSecurityConfiguration.java`
- FOUND: `src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetServiceE2ETest.java`
- FOUND: `src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetControllerE2ETest.java`
- FOUND: `src/test/java/com/vrudenko/kanban_board/security/ResetEndpointProfileGatingTest.java`
- FOUND commit: `65e3370`
- FOUND commit: `818c14a`
