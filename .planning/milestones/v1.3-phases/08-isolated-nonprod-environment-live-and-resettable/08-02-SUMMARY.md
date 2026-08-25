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
  - A live-verified rollout of the reset-capable image onto the deployed nonprod stack, with a real curl contract proof recorded in docs/INFRA_RUNBOOK.md
affects: []

# Actuals (#2632)
actuals:
  tokens: 60000
  tasks: 3
  commits: 3

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
    - docs/INFRA_RUNBOOK.md

key-decisions:
  - "Task 3 (live deploy + curl proof against the real nonprod stack) was originally not attempted in the session that produced Tasks 1-2 -- its own <precondition> was checked and found unmet at that time: those two commits existed only on a local worktree branch, unmerged. The orchestrator subsequently merged commits 65e3370/818c14a to master and pushed; CI run 32141273073 built and pushed image rudenkovladimir/kanban-board-backend:777cb27 to Docker Hub, satisfying Task 3's precondition. This dispatch re-ran Task 3 only, against that now-real image tag, in a fresh worktree based on 777cb27."
  - "ResetServiceE2ETest and ResetControllerE2ETest both extend AbstractKafkaContainerTest directly (not AbstractAppTest/AbstractAppE2ETest), matching the plan's read_first pointer to that harness's own Javadoc reasoning (no unrelated ~20-entity fixture noise racing the broker under test). Domain fixtures for ResetServiceE2ETest are created explicitly through the real services (UserService/BoardService/ColumnService/TaskService) rather than via a shared fixture base."
  - "TDD RED-then-GREEN was not reconstructed as two separate commits per task, despite this repo's own precedent of test(...)/feat(...) commit pairs for tdd=\"true\" tasks. This repo's pre-commit hook runs fastTest (which requires a full, successful compileTestJava) before allowing any commit -- a test file referencing not-yet-existing production symbols (ApiPaths.RESET, ResetService, ResetTruncateService for Task 1; ResetController, NonprodResetSecurityConfiguration for Task 2) cannot compile, so a true compile-failing RED commit cannot pass this repo's own hook. Reconstructing a deliberately-broken-but-compiling stub purely to manufacture an intermediate failing-test commit was judged not worth the added complexity once the full, real implementation was already written and independently verified end-to-end against a real Testcontainers Postgres + Redpanda broker. Both tasks were committed as a single feat(...) commit each instead. See TDD Gate Compliance below."
  - "Task 3's reset token was regenerated on the VM via the plan's literal `openssl rand -base64 48` command rather than reusing the 64-hex-character APP_RESET_TOKEN value plan 08-01 had already placed in .env.nonprod as a stack-shape placeholder (that file's key existed before this task, but the endpoint consuming it had never been deployed) -- following the plan's <action> text exactly rather than treating the pre-existing placeholder as sufficient."

requirements-completed: [RESET-01]

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
        ref: "docs/INFRA_RUNBOOK.md 'Nonprod reset endpoint — Plan 08-02' section: correct-token call -> 204, all eight tables 0/0 and flyway_schema_history unchanged at 7, both Kafka topics' log-start offset equal to high-watermark; wrong-token and absent-header calls both 403 ACCESS_DENIED with byte-identical bodies; same call against production -> 401 (not 204), production health 200, production row counts unchanged (3 users/2 boards, matching plan 08-01's baseline); post-reset board create appears in the activity feed and a second/third reset call both return 204 (idempotent, including against an already-empty store)"
        status: pass
    human_judgment: true

# Metrics
duration: ~95min (Tasks 1-2 ~75min in the original session; Task 3 ~20min in this re-dispatch, after the merge/CI/image-build precondition was satisfied by the orchestrator)
completed: 2026-08-18
status: complete
---

# Phase 8 Plan 2: Isolated Nonprod Environment, Live and Resettable Summary

**A profile-gated, shared-secret-authenticated `POST /admin/reset` endpoint that truncates nonprod's
Postgres tables and trims its Kafka activity topics to zero, proven by real-broker/real-Postgres
tests, an HTTP-level contract test, and a live curl against the deployed nonprod hostname that
genuinely emptied both stores while proving production has no such route.**

## Performance

- **Duration:** ~95 min total (Tasks 1-2: ~75 min in the original session; Task 3: ~20 min in a
  re-dispatch after the orchestrator merged Tasks 1-2 to master and CI built/published the image)
- **Completed:** 2026-08-18
- **Tasks:** 3 of 3
- **Files modified:** 9 (7 created, 2 modified: `ApiPaths.java`, `docs/INFRA_RUNBOOK.md`)

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
- **Task 3, live against the deployed nonprod stack:** rolled `app-nonprod` to image tag `777cb27`
  (CI run `32141273073`), confirmed the `nonprod` profile active from the boot log, created a full
  user/board/column/task/subtask chain through the public API and confirmed all four resulting
  events in the activity feed, then called `POST /api/admin/reset` with the correct token from
  off-VM: `204`, empty body, no `Set-Cookie`. Independently re-queried all eight Postgres tables
  (all `0`, `flyway_schema_history` unchanged at `7`) and both Kafka topics via `rpk topic
  describe` (`kanban.activity` and `kanban.activity.dlt` both log-start-offset ==
  high-watermark). Wrong-token and absent-header calls both returned `403` with byte-identical
  `ACCESS_DENIED` ProblemDetail bodies. The same call against production's hostname returned
  `401` (not `204`) -- production's own catch-all security chain, since no reset bean is
  registered there -- with production's health endpoint staying `200` and its row counts
  unchanged (`3` users / `2` boards, matching plan 08-01's own recorded baseline). Created one
  more board post-reset and confirmed it landed in the activity feed (consumer survived), then
  called reset twice more, both returning `204` -- once against real remaining state, once
  against an already-empty store. Recorded in full in `docs/INFRA_RUNBOOK.md`'s new "Nonprod
  reset endpoint -- Plan 08-02" section.

## Task Commits

Each task was committed atomically:

1. **Task 1: Two-store truncate -- ResetService, ResetTruncateService, and a real-broker proof** -
   `65e3370` (feat)
2. **Task 2: The endpoint -- profile-gated controller, constant-time token check, and a
   profile-absence proof** - `818c14a` (feat)
3. **Task 3: Deploy the reset-capable image to nonprod and prove the curl contract live** -
   `c83d36e` (docs, `docs/INFRA_RUNBOOK.md`)

_Note: both tasks 1-2 carry `tdd="true"` in the plan; see "TDD Gate Compliance" below for why each
landed as one `feat(...)` commit rather than a separate `test(...)`/`feat(...)` pair. Task 3 carries
no `tdd` attribute -- it is a live-infrastructure verification task, not a code-behavior task._

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
- `docs/INFRA_RUNBOOK.md` - new "Nonprod reset endpoint -- Plan 08-02" section: rollout sequence,
  live curl contract (204/403), independent Postgres/Kafka verification, production negative
  result, consumer-survival/idempotency proof, token-rotation operator note

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

### Blocking Issues (resolved by a later dispatch)

**2. [Precondition unmet, then resolved] Task 3's live-deploy precondition was initially unmet**
- **Found during:** Task 3, in the original session, before any live-infrastructure action was
  taken
- **Issue:** Task 3's `<precondition>` requires "the commit from Tasks 1-2 has been built and pushed
  to Docker Hub as `rudenkovladimir/kanban-board-backend:<tag>` ... and that tag is resolvable by
  `docker pull` from the VM." At that point this plan's two commits existed only on a worktree's
  local branch, never pushed to `origin`, never merged to `master`; `origin/master` was still behind
  plan 08-01's own work, and `gh run list --workflow=deploy.yml` confirmed the most recent CI/CD run
  built the commit already running in production (`6755c84`), not anything from this plan.
- **Action taken at that time:** None on the live nonprod/production infrastructure. Matched the
  plan's own explicit instruction: "do not fabricate a tag or skip the precondition -- halt with a
  clear checkpoint report."
- **Resolution:** The orchestrator subsequently merged commits `65e3370`/`818c14a` to `master` and
  pushed to `origin`. CI run `32141273073` (tests, build, Flyway verify, deploy-to-netcup) completed
  successfully and published `rudenkovladimir/kanban-board-backend:777cb27` to Docker Hub; production
  was confirmed healthy (`200`) after that redeploy. This dispatch re-ran Task 3 only, from a fresh
  worktree based on `777cb27`, with the precondition now genuinely satisfied -- see the "Live
  reset-endpoint rollout" accomplishment above and `docs/INFRA_RUNBOOK.md`'s new section for the
  full live proof.

---

**Total deviations:** 1 auto-fixed (bug), 1 precondition-blocked-then-resolved (Task 3, closed by
this dispatch)
**Impact on plan:** All three tasks are now complete. RESET-01 is fully delivered: the mechanism
exists, is proven in tests, and is proven live against the deployed nonprod stack, with the full
record in `docs/INFRA_RUNBOOK.md`.

## Issues Encountered

- **Pre-commit hook timed out on the first Task 1 commit attempt (3 min tool timeout), then the
  plain retry passed clean.** Same pattern `docs/SESSION_LESSONS.md` lesson 2's timeout corollary
  and `08-01-SUMMARY.md`'s own "Issues Encountered" describe: `.githooks/pre-commit` runs
  `spotlessCheck` then `./gradlew fastTest`, which can exceed a short default timeout on a cold
  Gradle daemon. Resolved per the documented recovery: `./gradlew --stop`, ran `fastTest` directly
  to confirm a clean pass (4m 28s, `BUILD SUCCESSFUL`), then retried the plain `git commit` with a
  longer timeout -- succeeded.

## User Setup Required

None. Task 3 generated its own `APP_RESET_TOKEN` on the VM (`openssl rand -base64 48`, written
directly into `/opt/deploy/kanban-board-nonprod/.env.nonprod`, mode `600`, owned by `deploy`,
never typed into this session's transcript or any committed file) and rolled `IMAGE_TAG` itself
via SSH using the deploy key already established by Plan 08-01 -- no manual operator action
needed for any of the three tasks in this plan.

## Next Phase Readiness

- **Ready to close this plan.** `RESET-01` is fully satisfied: `ResetController`/`ResetService`/
  `ResetTruncateService`/`NonprodResetSecurityConfiguration` exist, are profile-gated, are proven
  correct against real infrastructure in tests, and are now proven live against the deployed
  nonprod hostname -- empty-baseline proof, production-absence proof, idempotency, and consumer
  survival are all recorded with real command output in `docs/INFRA_RUNBOOK.md`'s "Nonprod reset
  endpoint -- Plan 08-02" section.
- Nonprod now carries the reset-capable image (`777cb27`) and both nonprod stores are empty as a
  direct result of this task's own live verification calls -- a genuinely clean baseline, not an
  artifact requiring cleanup.
- Production was not touched by any part of this plan: `SecurityConfiguration.java` is unmodified
  (verified by `git diff --name-only`), and production's health/row-counts were independently
  confirmed unchanged before and after Task 3's live calls.
- No blockers remain for this plan. A future Playwright E2E suite (out of this plan's scope) can
  now rely on `POST /api/admin/reset` as a real `beforeEach` baseline against the live nonprod
  deploy, per this plan's original purpose statement.

---
*Phase: 08-isolated-nonprod-environment-live-and-resettable*
*Completed: 2026-08-18 (all 3 tasks; Task 3 executed in a later re-dispatch after the merge/CI/image
precondition was satisfied)*

## Self-Check: PASSED

- FOUND: `src/main/java/com/vrudenko/kanban_board/service/ResetService.java`
- FOUND: `src/main/java/com/vrudenko/kanban_board/service/ResetTruncateService.java`
- FOUND: `src/main/java/com/vrudenko/kanban_board/controller/ResetController.java`
- FOUND: `src/main/java/com/vrudenko/kanban_board/security/NonprodResetSecurityConfiguration.java`
- FOUND: `src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetServiceE2ETest.java`
- FOUND: `src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetControllerE2ETest.java`
- FOUND: `src/test/java/com/vrudenko/kanban_board/security/ResetEndpointProfileGatingTest.java`
- FOUND: `docs/INFRA_RUNBOOK.md` section "Nonprod reset endpoint — Plan 08-02"
- FOUND commit: `65e3370`
- FOUND commit: `818c14a`
- FOUND commit: `c83d36e`
- Live verification (not a repo artifact, recorded as command output in docs/INFRA_RUNBOOK.md):
  correct-token call returned 204 and all eight nonprod tables independently re-queried at 0;
  wrong-token and absent-header calls both returned 403 with identical ACCESS_DENIED bodies;
  the same call against production returned 401 (not 204) with production health staying 200 and
  row counts unchanged; a post-reset board create appeared in the activity feed and two further
  reset calls both returned 204 (idempotent, including against an already-empty store).
