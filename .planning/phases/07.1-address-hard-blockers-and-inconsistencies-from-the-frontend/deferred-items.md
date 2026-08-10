# Deferred Items

Out-of-scope discoveries surfaced during plan execution, logged rather than fixed per the
executor's scope-boundary rule (only auto-fix issues directly caused by the current task's
changes).

## 07.1-06: Intermittent `ColumnLockingE2ETest` failure under full `./gradlew test`, unrelated to this plan's changes

**Found during:** Task 2's full-suite verification pass.

**Symptom:** `ColumnLockingE2ETest.concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict()`
occasionally fails with `AssertionError: Status expected:<200> but was:<400>` at
`AbstractAppMockMvcTest.signinCookie()` -- the real `POST /signin` call the test's `@BeforeEach`-less
setup makes returns 400 instead of 200. Traced to `GlobalExceptionHandler.handleMethodArgumentNotValidException`
(400 = `SigninRequestDTO` bean-validation failure), not a `BadCredentialsException` (which maps to 401)
or an ownership/access issue (403) -- meaning the constructed sign-in request itself failed validation,
not the credentials.

**Investigation:** This plan's changes (`BoardController.addColumnByBoardId`, `TaskController.addSubtaskByTaskId`,
their tests) touch neither the auth/session code path nor `SigninRequestDTO`/`AuthenticationController`.
`ColumnLockingE2ETest` itself asserts only on column PUT/version-conflict behavior, not on anything this
plan changed. The failing test passes reliably in isolation (`./gradlew test --tests
"...ColumnLockingE2ETest"`, verified 2/2). Across 4 full-suite (`./gradlew test`) runs during this plan's
verification -- one against the pre-change base commit (a173fec, via a temporary detached worktree) and
three against this plan's diff -- the failure appeared in 2 of the 3 diff runs and did not appear in the
single base-commit run measured, which is too small a sample to attribute causation to this plan's
changes (a single green base run is not proof of no pre-existing flake) but is consistent with an
existing, full-suite-only, order/timing-sensitive flake rather than a deterministic regression: the same
diff produced both a red and a green full-suite run without any code change between them.

**Why not fixed here:** Out of this plan's scope (status-code consistency on two POST endpoints) --
diagnosing a full-suite-only, non-reproducible-in-isolation session/timing flake is its own
investigation, not a drive-by fix. `./gradlew fastTest` (what the pre-commit hook actually runs) is
unaffected: `ColumnLockingE2ETest`'s `E2ETest` suffix excludes it from `fastTest`'s filter, so this
flake cannot block a commit through the hook -- confirmed green across every `fastTest` run in this
plan's verification.

**Suggested next step:** File as a proper todo if it recurs during a future phase's `./gradlew test`
run, with enough repeated-run evidence to either reproduce reliably or rule it out as noise.

## 07.1-07 Task 1: Pre-existing `BoardServiceTest$FindFullByIdQueryCountTest` random-name collision, now surfaced inside `fastTest`

**Found during:** Task 1's verification pass (`./gradlew fastTest` / `./gradlew test`).

**Symptom:** `BoardServiceTest$FindFullByIdQueryCountTest.queryCountDoesNotScaleWithGraphSize()`
intermittently fails with `AppDuplicateResourceException: Board with that name already exists`,
thrown from `UserService.addBoardByUserId` inside the test's own `buildBoardGraph` helper. The test
calls `buildBoardGraph` twice for the *same* `userId` in one method
(`BoardServiceTest.java:397-398`), each time naming the board with
`dataFactory.getRandomWord(ValidationConstants.MIN_BOARD_NAME_LENGTH + 4)` — `DataFactory` 0.8 draws
from a small, fixed word list with no uniqueness guarantee, so the two draws collide by chance
(birthday-paradox risk on a small pool), tripping the board name's per-user unique constraint. This
is a test-design bug in `BoardServiceTest.java`, not application code.

**Investigation:** Reproduced 3 times in a row under `./gradlew fastTest` (266 tests, same failing
method each time) and once under `./gradlew test` (302 tests) in the same session; a separate
`./gradlew test` run in between was fully green (302/302), and running
`BoardServiceTest` alone (`./gradlew test --tests`) passed twice. This is consistent with
timing/seed-dependent randomness in `DataFactory`, not a regression from this task's changes —
task 1 only adds `@Tag` annotations to `build.gradle`, `.githooks/pre-commit`,
`docs/CODE_STYLE.md`, and 9 Kafka/real-socket test classes; it does not touch `BoardServiceTest.java`
or `UserService.java`. Test *counts* were stable and reproducible across every run (302 for `test`,
266 for `fastTest`), which is what task 1's acceptance criteria actually measures for the
teeth/no-shrinkage checks.

**New wrinkle this task introduces:** before this task, `fastTest`'s name-based filter excluded
every `*E2ETest`-suffixed class, but `BoardServiceTest` was never excluded by that filter either — it
has always run inside `fastTest`. So this flake's exposure inside the pre-commit gate is not new; it
was already reachable pre-task-1. Recorded here because task 1's own verification pass is what
surfaced it in this session, and because D-21/D-22 intentionally widen `fastTest`'s membership
elsewhere (the 11 renamed-tier classes), making pre-existing flakes in general more likely to be
observed inside the gate going forward, even though this specific one was already present.

**Update -- fixed after all (escalated to Rule 3):** Initially logged here as out-of-scope and left
unfixed. It then reproduced on 4 consecutive `./gradlew fastTest` runs, including inside the
project's own (unbypassable, per this project's worktree rules -- `--no-verify` is prohibited)
pre-commit hook, blocking every attempt to commit task 1's own in-scope changes. Decompiling
`datafactory-0.8.jar` (`org.fluttercode.datafactory.impl.DefaultContentDataValues.words`) confirmed
the root cause: the bundled word list is literal running text from a story (many short, repeated
filler words -- "we", "was", "is", "no", etc.), so filtering to length >= 5 leaves a small candidate
set with real duplicate entries, not merely a small set of *distinct* words -- the same word is
genuinely more likely to be drawn twice than a naive distinct-word-count would suggest. Re-classified
under Rule 3 (a blocking issue with no `--no-verify` escape hatch) rather than left under the
scope-boundary exclusion, and fixed with a minimal, established in-file pattern:
`buildBoardGraph`'s board name now uses `RandomStringUtils.randomAlphabetic(MAX_BOARD_NAME_LENGTH -
MIN_BOARD_NAME_LENGTH)`, the exact call already used three times elsewhere in
`BoardServiceTest.java` (`testUpdateById_shouldUpdateBoard_whenBoardExists` and siblings) for the
same collision-proofing reason. Column/task/subtask name generation in the same helper is untouched
-- the failure was specifically board-name uniqueness, not any other constraint. Committed as part of
plan 07.1-07 task 1 (`BoardServiceTest.java` was not in task 1's originally-planned file list, but
Rule 3 explicitly permits an unplanned fix once it blocks the current task).

## 07.1-07: Recurring `ColumnLockingTest`/`ColumnLockingE2ETest` signin-400 flake and `EventIdGeneratorTest` uniqueness flake, both pre-existing and out of this plan's scope

**Found during:** Verification passes across all three of this plan's tasks (`./gradlew test`),
independent of any file this plan touches.

**Symptom 1 -- `ColumnLockingTest.update_withoutVersion_returnsBadRequest()`** (named
`ColumnLockingE2ETest` before task 2's rename): `AbstractAppMockMvcTest.signinCookie()` expects
`200` from `POST /signin` and intermittently gets `400`. Reproduced across 3 separate full
`./gradlew test` runs this session (out of ~6 total), never reproduced when the class is run in
isolation (`./gradlew test --tests`, 2/2 clean). Investigated but not resolved: `@Password`'s regex
is permissive enough (`.+$` tail) that no `dataFactory`-generated word content can fail it, and
`@AppEmail` is a stock Jakarta `@Email` constraint unlikely to reject `dataFactory.getEmailAddress()`
output, so a DTO-validation cause was ruled out rather than confirmed. Every reproduction's
`testsuite timestamp` lands in the same second as Kafka consumer/producer "Node disconnected" /
"Bootstrap broker ... could not be established" log lines from `KafkaEventPublisher`'s async publish
path -- circumstantial but consistent evidence this is a Testcontainers-Kafka-broker-timing artifact
specific to this class's Spring context startup window, not a defect in `ColumnLockingTest` or the
signin path itself.

**Symptom 2 -- `EventIdGeneratorTest$GenerateTest.shouldReturnDistinctValues_whenCalledManyTimesRapidly()`**:
asserts 1000 rapid-fire `EventIdGenerator.generate()` calls are all distinct; intermittently observes
999 (one collision). Reproduced twice this session. Neither `EventIdGenerator` nor
`RandFlakeGenerator` (its delegate) is touched by any file this plan modifies.

**Why not fixed here:** Neither file is in this plan's `files_modified` list, and both classes'
production code (`ColumnController`/`AuthenticationController`/`UserAuthenticationProvider` for
symptom 1, `EventIdGenerator`/`RandFlakeGenerator` for symptom 2) is unrelated to 07.1-07's actual
diff (test tagging, 11 file renames, one comment fix). Unlike the `BoardServiceTest` flake fixed
under Rule 3 in task 1, neither of these ever blocked a commit outright -- every occurrence cleared
on the next `./gradlew test` invocation, so there was no unbypassable gate to escalate against.

**Suggested next step:** File as a proper todo (done -- see
`.planning/todos/pending/2026-08-10-investigate-two-recurring-pre-existing-test-flakes-surfa.md`) for
someone to reproduce with `-Dspring.kafka.consumer.properties.session.timeout.ms` tracing (symptom 1)
and a tighter collision-probability review of `RandFlakeGenerator` under rapid sequential calls
(symptom 2), rather than continuing to absorb the ~5-6 minute full-suite cost of chasing either from
inside an unrelated plan.
