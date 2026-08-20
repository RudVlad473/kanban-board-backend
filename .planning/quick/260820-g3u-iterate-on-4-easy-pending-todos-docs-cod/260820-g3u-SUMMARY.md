---
quick_id: 260820-g3u
status: complete
completed: 2026-08-20
---

# Quick Task 260820-g3u: 4 easy pending todos, plus one real bug found along the way

**Four small, well-scoped pending todos closed — a docs correction, a test-tier tag fix, a
readability cleanup, and a genuine flaky-test root-cause fix — and a real gap in Phase 10's
Gradle dependency-verification metadata found and fixed in the process.**

## What was done

1. **`docs/CODE_STYLE.md` rule 4 correction** (commit `7399e0e`): two falsified sentences about
   the `.with(user(userId))` MockMvc-shortcut refusal mechanism corrected, citing quick task
   260813-m9x's own probe evidence — `SessionManagementFilter`'s own DSL-composed strategy (not
   the `sessionAuthenticationStrategy` bean) enforces it, and the refusal is a bare `sendError`,
   not the RFC 7807 envelope.
2. **`HistoricalActivityEventReconstructorTest` tagged `@Tag("kafka")`** (commit `4ffa736`) —
   the sole `AbstractKafkaContainerTest` subclass in the repo not already tagged, so it ran (and
   started a real Redpanda container) inside the pre-commit `fastTest` gate on every commit.
   Verified live both directions.
3. **Bare `print()` static import qualified** across 4 `*ControllerTest` classes, 41 call sites
   (commit `2c5a196`) — `import static ...MockMvcResultHandlers.print;` replaced with a plain
   class import, call sites qualified as `MockMvcResultHandlers.print()`. Zero new tooling.
4. **`ResetServiceE2ETest`'s flaky race fixed** (commit `df68443`) — root-caused live:
   `KafkaEventPublisher.onActivityEvent` is `@Async` on `AFTER_COMMIT`, so `createDomainFixture()`'s
   4 domain-service calls return with no guarantee their activity events reached the broker.
   `resetService.resetAll()` called immediately after could race a late-arriving publish past its
   own topic-trim step, landing a stray row in `activity_log` after the Postgres truncate. Fixed
   by awaiting a relative gain of 4 rows before calling `resetAll()` (not a hardcoded absolute
   total — sibling tests in the same class have the identical gap and could contaminate a fixed
   total).

**Unplanned, found while verifying #4** (commit `e7cc0e1`): the first real local `compileTestJava`
since Phase 10 generated `gradle/verification-metadata.xml` failed dependency verification —
`guava-33.5.0-jre.pom` had no recorded checksum even though its `.jar`/`.module` did. Fixed by
regenerating via `--write-verification-metadata sha256 compileTestJava` (a bare `help` invocation
does not reproduce or fix it). The earlier "already resolved" todo for that metadata file was
corrected with an addendum (not rewritten), and a new pending todo filed for the still-missing CI
staleness check that let this specific gap go undetected through every Phase 10 CI run.

## Verification

- `spotlessCheck` and `compileTestJava` both pass clean after every change.
- `fastTest` passes; confirmed `HistoricalActivityEventReconstructorTest` is excluded from its
  results and still present/passing under the full `test` task.
- `ResetServiceE2ETest` alone: 3 consecutive clean isolated reruns (`--tests
  '*ResetServiceE2ETest' --rerun`).
- Full `./gradlew test` (474 tests): 2 clean runs. One run in between hit a single failure in the
  same test class, but a *different* symptom (`Connection to node -1 (localhost/127.0.0.1:9092)
  could not be established` at the very first `Awaitility` wait, not the race this fix targets) —
  traced to this session's own repeated heavy Testcontainers load on a freshly-restarted Docker
  Desktop, not a defect in the fix. Noted for transparency rather than omitted.

## Commits

Five atomic commits: `7399e0e` (docs), `4ffa736` (tag), `2c5a196` (static import), `e7cc0e1`
(verification-metadata gap), `df68443` (flaky-test fix), plus this task's own closing docs commit.

## Next Phase Readiness

No blockers. All four originally-scoped todos closed; one new todo filed
(`2026-08-20-no-ci-check-for-stale-gradle-verification-metadata.md`) for a real, separate gap this
work surfaced.
