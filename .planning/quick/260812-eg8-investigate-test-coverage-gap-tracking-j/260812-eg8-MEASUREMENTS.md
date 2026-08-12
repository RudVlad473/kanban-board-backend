# Quick Task 260812-eg8: Coverage Baseline and Test-Placement Audit

**Measured:** 2026-08-12
**Command:** `./gradlew clean test jacocoTestReport --rerun-tasks`
**Status:** Task 1 complete -- report-only, no threshold applied yet (D-01). Zero test files moved.

---

## Section 1 -- Coverage Baseline

### Headline numbers (hand-written `src/main` code only -- Lombok/MapStruct/Avro excluded)

| Metric | Covered | Total | % |
|---|---:|---:|---:|
| Instruction | 4,223 | 4,629 | **91.23%** |
| Line | 972 | 1,069 | **90.93%** |
| Branch | 125 | 159 | **78.62%** |
| Method | 193 | 212 | **91.04%** |
| Class | 62 | 68 | **91.18%** |

Non-degenerate (trade-off 2 sanity check): the two-fork merge is trustworthy. Classes independently
known to be heavily exercised are at or near 100% (`ColumnService` 100%, `SubtaskService` 100%,
`ActivityLogService` 100%, all 4 `controller/` classes 100%, `TaskService` 95.6%, `BoardService`
97.9%), and no class the suite is known to exercise shows a suspicious near-zero split. `maxParallelForks`
was left at 2 -- no re-measurement at 1 fork was needed.

Suite: **430 tests, 0 failures, 0 errors** (matches STATE.md's documented 430-test baseline from
quick task 260811-ufu exactly -- confirms the suite was unaffected by adding coverage instrumentation).

**Wall-clock / instrumentation overhead** (measured via `--profile`, forced re-run, both `clean` and
`jacocoTestReport` included):

| Task | Duration |
|---|---:|
| `:test` (JaCoCo-instrumented, `maxParallelForks=2`) | 349.6s (5m 49.6s) |
| `:jacocoTestReport` | 2.8s |
| `:clean` | 0.5s |
| **Total** | 395.9s (6m 35.7s wall shown by Gradle, includes daemon/startup overhead) |

Documented uninstrumented baseline (`build.gradle`, quick task 260811-ixj, `maxParallelForks=2`):
~276.5s average (283s, 270s).

**Overhead: 349.6s vs 276.5s = +73.1s, approximately +26.4%.** This exceeds the "typically 5-20%"
figure this plan's trade-off 3 cited going in -- recorded here as a real, measured finding rather
than silently rounded down to match the estimate. It is still consistent with trade-off 3's decision
to keep coverage off `fastTest` (the pre-commit gate): +26% on a ~350s run is exactly the kind of
tax that would be felt on every single commit if it were not scoped to `test` only.

### Per-package instruction/line coverage (lowest first)

| Package | Instruction | Line |
|---|---:|---:|
| `dto/subtask_dto` | 0.0% (0/18) | 0.0% (0/3) |
| `dto/task_dto` | 14.3% (3/21) | 25.0% (1/4) |
| `com/vrudenko/kanban_board` (root) | 37.5% (3/8) | 33.3% (1/3) |
| `dto/board_dto` | 50.0% (3/6) | 50.0% (1/2) |
| `handler` | 70.9% (205/289) | 67.7% (44/65) |
| `activitylog` | 76.6% (320/418) | 76.4% (68/89) |
| `config` | 78.9% (385/488) | 77.2% (95/123) |
| `exception` | 86.1% (31/36) | 100.0% (11/11) |
| `constant` | 89.3% (75/84) | 81.3% (13/16) |
| `security` | 94.1% (427/454) | 93.9% (92/98) |
| `service` | 97.9% (1565/1599) | 97.8% (348/356) |
| `entity` | 98.2% (111/113) | 95.5% (21/22) |
| `event/avro`, `mapper`, `controller`, `dto/column_dto`, `event` | 100.0% | 100.0% |

Six packages carry zero *instrumentable* content at all (pure interfaces or fully-Lombok classes
with no hand-written method surviving the `@Generated` filter, so JaCoCo has literally nothing to
count): `dto/activity_dto`, `repository`, `base/entity`, `base/service`, `dto/user_dto`,
`dto/annotation`. This is expected, not a gap -- `repository` is Spring Data interfaces with no
method bodies, and the two `dto/*` packages here are pure-Lombok DTOs the filter correctly zeroed out.

### Classes at or near zero coverage (the todo's actual question, answered by name)

| Class | Instruction | Finding |
|---|---:|---|
| `dto/board_dto/DeleteBoardByIdRequestDTO` | 0/3 | **Confirms an already-filed pending todo** (`2026-08-11-delete-dead-deleteboardbyidrequestdto-class.md`) -- zero references anywhere in the codebase. JaCoCo independently re-derives the same finding from real execution data. |
| `constant/ValidationConstants` | 0/3 | Not a real gap -- the "3 missed instructions" are the synthetic default no-arg constructor of a pure `public static final`-field holder class, never explicitly invoked because nothing needs to instantiate it. |
| `constant/KafkaTopics` | 0/3 | Same shape as `ValidationConstants` -- synthetic constructor only. |
| `constant/ApiPaths` | 0/3 | Same shape -- synthetic constructor only. |
| `dto/task_dto/UpdateTaskRequestDTO` | 0/18 | **Real finding.** All 18 missed instructions belong to one method: `atLeastOneFieldPopulated()`, the `@AssertTrue` cross-field validator CODE_STYLE.md rule 6 mandates. No test in the suite currently submits an `UpdateTaskRequestDTO` with *neither* `title` nor `description` populated (alongside a valid `version`) to exercise this method's branch logic, despite `UpdateTaskRequestDTO` itself being used extensively across `TaskControllerTest`/`TaskOrderingTest`. A binary "does a test class exist for this DTO" check (D-02's candidate ArchUnit rule) would have missed this entirely, since many test classes do reference `UpdateTaskRequestDTO` -- just never in the one shape that exercises this specific validator. |
| `dto/subtask_dto/UpdateSubtaskRequestDTO` | 0/18 | **Identical finding, same method**, on `UpdateSubtaskRequestDTO.atLeastOneFieldPopulated()`. Same "referenced everywhere, this one method never exercised" shape. |

Not fixed here (Task 1 is audit-only, per its own scope) -- both `atLeastOneFieldPopulated()` gaps
and the confirmed-dead `DeleteBoardByIdRequestDTO` are flagged for the operator; see Section 3's
recommendation on whether they warrant follow-up beyond this task.

### Lowest non-zero classes (context for where real gaps, not artifacts, sit)

| Class | Instruction |
|---|---:|
| `config/KafkaEventPublisher` | 30.9% (17/55) |
| `KanbanBoardApplication` | 37.5% (3/8) |
| `config/AvroSchemaRegistrar` | 63.3% (100/158) |
| `handler/GlobalExceptionHandler` | 70.9% (205/289) |
| `security/CurrentUserIdResolver` | 74.4% (29/39) |
| `activitylog/ActivityLogConsumer` | 74.9% (287/383) |

`KafkaEventPublisher` and `AvroSchemaRegistrar` being the two lowest real (non-artifact) classes is
plausible on its face: both carry branches for infrastructure-failure paths (registry lookup
failures, publish-executor rejection) that are deliberately hard to trigger without a broken broker,
and `AvroSchemaRegistrar` is a build/CI-invoked tool class (`registerSchemas` Gradle task) more than
a runtime `src/main` class the request-serving test suite would naturally exercise end to end.
`GlobalExceptionHandler` at 70.9% having the lowest package (`handler`) is worth a specific callout:
it is the single most safety-relevant class in the error-handling design (CLAUDE.md's whole 401/403/
400/409 split routes through it), so its exception-branch coverage is exactly the kind of number
Section 2 below asks the operator to weigh.

---

## Section 2 -- Rung Options for D-01

A coverage percentage on this codebase is a **drift alarm, not a safety property** (trade-off 4): the
91% instruction figure above is dominated by DTO/entity/mapper surface once Lombok/MapStruct/Avro are
excluded, not by the ownership-chain branches that actually carry the access-control model (those are
already covered by dedicated `*ServiceTest`/`*ControllerTest`/`e2e/` classes independent of this
number). Whatever rung is chosen below should be read as "detects a class going dark going forward,"
not "proves this codebase is well tested" -- the suite already proved that, before this task existed.

| Option | Mechanism | What today's 91.23% instruction / 78.62% branch would do |
|---|---|---|
| **Report-only permanently** | No `jacocoTestCoverageVerification` task ever added; `./gradlew jacocoTestReport` stays a manually-invoked, discoverable command. | N/A -- nothing to pass or fail. Zero risk of a spurious red build. Same silent-drift shape the todo was filed about, one layer up: nothing stops a class from going dark unless a human remembers to run the report. |
| **Ratchet at the measured baseline** | `jacocoTestCoverageVerification` with a limit set *just below* today's number (e.g. instruction >= 0.90, branch >= 0.75 -- a few points of headroom so normal code-shape noise doesn't spuriously fail a build that didn't actually regress), attached to `test`. | **Passes today** (91.23% > 90%, 78.62% > 75%). Locks in the current level; a future PR that adds an untested class or a large untested branch would fail the build. Does not force any of today's existing gaps (the two `atLeastOneFieldPopulated()` methods, the dead DTO) to be fixed -- it freezes them as accepted baseline. |
| **Per-class minimum** (e.g. 50% instruction floor per class) | `jacocoTestCoverageVerification` with a `rule { element = CLASS; limit { counter = 'INSTRUCTION'; minimum = 0.50 } }`, plus a named exemption list for classes that legitimately can't clear it. | **Fails today** without an exemption list -- exactly 6 classes are below 50%: `DeleteBoardByIdRequestDTO` (0%), `ValidationConstants` (0%), `KafkaTopics` (0%), `ApiPaths` (0%), `UpdateTaskRequestDTO` (0%), `UpdateSubtaskRequestDTO` (0%). Three of those six (the constant holders) are JaCoCo artifacts, not real gaps, and would need permanent exemption. `DeleteBoardByIdRequestDTO` should be deleted (already a pending todo), not exempted. The two `Update*RequestDTO` classes are the option's real value proposition: it is the only rung of the three that would force `atLeastOneFieldPopulated()` to get a real test, rather than silently accepting it as baseline. |

---

## Section 3 -- D-02 Recommendation (ArchUnit Zero-Coverage Complement)

**Recommendation: do not add it.** Having now seen the real zero-coverage list, a coarse
"does at least one test class exist that references this main class" ArchUnit rule would add
**strictly less** signal than JaCoCo's own per-class/per-method report already provides, for two
concrete reasons observed directly in Section 1's data:

1. **False negatives on the exact failure mode the source todo described.** The todo's motivating
   example was "a controller has 5 routes, only 3 exercised, nothing flags the other 2" -- a
   *partial* coverage gap inside an otherwise-tested class. `UpdateTaskRequestDTO` and
   `UpdateSubtaskRequestDTO` are both referenced by several existing test classes
   (`TaskControllerTest`, `TaskOrderingTest`, `SubtaskLockingTest`, etc.), so a binary
   class-existence check would report both as "covered" -- while their `atLeastOneFieldPopulated()`
   method sits at a genuine 0%. A percentage-based report catches this; a existence check cannot,
   by construction.
2. **No unique true positives.** The one case where a class-existence check and JaCoCo would agree
   is `DeleteBoardByIdRequestDTO` (genuinely zero references anywhere) -- but JaCoCo already reports
   that finding today, with more precision (it distinguishes "zero references" from "referenced but
   one method never exercised," which a class-existence rule cannot).

An exemption list for the coarser rule would also need to carry the same constant-holder /
Lombok-only-class noise (`ValidationConstants`, `KafkaTopics`, `ApiPaths`, `repository/*`,
`base/entity`, `base/service`) that JaCoCo's own package-level report already surfaces as
"zero instrumentable content" without needing a maintained exemption list at all -- those packages
simply have nothing to instrument, so nothing to falsely flag.

If adopted anyway, the exemption list would need at minimum: `ValidationConstants`, `KafkaTopics`,
`ApiPaths` (constant holders, no instantiation expected), plus every `repository/*` interface,
`base/entity`, `base/service`, and `dto/annotation` (all interface-only or annotation-only, no
instrumentable body).

---

## Section 4 -- Disposition Table for D-03 (Fold vs. Relocate vs. Split)

Every row below was verified by reading both the stray file and (where a duplicate/fold candidate
was named) its proposed sibling, per this task's own instruction not to trust
`<starting_evidence>` unread.

| # | File | `@Test` | `@Nested` groups | Current base | Proposed destination | Disposition | Reason |
|---|---|---:|---|---|---|---|---|
| 1 | `ActivityLogCleanupIsolationTest` | 2 | `CleanupTest` | `AbstractAppTest` | `activitylog/ActivityLogCleanupIsolationTest` | **RELOCATE** | No HTTP; a D-02a tripwire for `activity_log` cross-test isolation. Natural sibling of the other `activitylog/*` classes. No duplicate found anywhere. |
| 2 | `BoardCreationE2ETest` | 9 | CreateBoard, DuplicateName, RenameBoard, CrossUserIsolation, ConcurrentCreate | `AbstractAppE2ETest`, `@Tag("realSocket")` | `e2e/board/BoardCreationE2ETest` | **RELOCATE** | Verified by reading `controller/BoardControllerTest.java` in full: it carries `FindAllByUserId`, `DeleteById`, `UpdateById`, `AddColumnByBoardId` -- **zero board-creation coverage of any kind**. This file is the sole coverage of `POST /boards`. `ConcurrentCreate` needs the real-socket tier (genuine concurrent HTTP threads racing a DB unique constraint) per rule 4 -- exactly the peer of `BoardLockingTest` in `e2e/board/`. No duplicate, no drop. |
| 3 | `BoardFullReadTest` | 8 | GetFullBoard, FlatEquivalence | `AbstractAppMockMvcTest` | `controller/BoardFullReadTest` | **RELOCATE** | Verified `BoardControllerTest` has no `/full` coverage either. `GetFullBoard` is a single-endpoint HTTP contract (rule 4's `controller/` purpose exactly). `FlatEquivalence` cross-checks that same endpoint against 3 sibling flat endpoints without needing real infrastructure or genuine concurrency, so it stays in this file rather than becoming a separate `e2e/` class. No duplicate, no drop. |
| 4 | `ColumnDeletionTest` | 4 | `DeleteById` | `AbstractAppMockMvcTest` | `controller/ColumnControllerTest` (merge target) | **FOLD** | Overlaps `ColumnOrderingTest.DeleteById` by nested-class *name* only. Read both: `ColumnDeletionTest.DeleteById` asserts cascade-delete correctness (tasks/subtasks removed, sibling column/task/subtask left untouched) via direct repository queries; `ColumnOrderingTest.DeleteById` asserts position-contiguity after a mid-list delete via `ColumnRepository.findAllByBoardId` sorted comparisons. **These are not duplicates** -- different property, same endpoint. Both target the single `DELETE /boards/{boardId}/columns/{columnId}` route with zero coverage of either property today in `controller/ColumnControllerTest` (which only has `FindAllByBoardId`, `AddTaskByColumnId`, `UpdateById`). Natural single home: fold both sets of `DeleteById` assertions into one merged `DeleteById` nested group there (4 + 1 = 5 tests, zero drops). |
| 5 | `ColumnOrderingTest` | 8 | ColumnCreation, Reorder, DeleteById | `AbstractAppMockMvcTest` | `controller/ColumnControllerTest` (merge target) | **FOLD** | `ColumnCreation` (contiguous positions on create) and `Reorder` (the `PATCH .../reorder` endpoint) are single-endpoint HTTP contracts on routes `ColumnControllerTest` does not currently touch at all. Zero overlap against its existing `FindAllByBoardId`/`AddTaskByColumnId`/`UpdateById` groups. `DeleteById` merges with row 4 above. |
| 6 | `EventIdGeneratorTest` | 3 | `GenerateTest` | `AbstractPostgresContainerTest` | `config/EventIdGeneratorTest` | **RELOCATE** | `EventIdGenerator` itself lives in `config/`; no HTTP; no duplicate anywhere. |
| 7 | `FlywaySchemaProvenanceTest` | 13 | FlywayHistory, SchemaShape, FlywayOnlyArtifacts, SpringSessionCoexistence | `AbstractPostgresContainerTest` | `config/FlywaySchemaProvenanceTest` | **RELOCATE** | Schema/config-level JDBC assertions against `information_schema`; no HTTP; no duplicate. |
| 8 | `KanbanBoardApplicationTests` | 1 | -- | `AbstractPostgresContainerTest` | **stays at root** | **EXEMPT** | Spring Boot's conventional root-package context-load smoke test, generated by Spring Initializr alongside the `@SpringBootApplication` class. Trade-off 5 confirms moving it down into a subpackage is *safe* (a `@SpringBootTest` with no `classes` attribute walks up the hierarchy for `@SpringBootConfiguration`), but idiomatic Spring Boot convention keeps this specific class beside the application class -- recommended as the sole named exemption on the ArchUnit placement rule's list, for Task 2 to confirm. |
| 9 | `SubtaskLockingTest` | 4 | `UpdateById` | `AbstractAppMockMvcTest` | `e2e/subtask/SubtaskLockingTest` | **RELOCATE** | This plan's own unambiguous first move (Task 3's tracer). The file's own Javadoc says "Modeled on `e2e.task.TaskLockingTest` and `e2e.column.ColumnLockingTest`" -- confirmed by directory listing: `e2e/{board,column,task}/*LockingTest` already exist; `e2e/subtask/` is the missing sibling. No duplicate. |
| 10 | `TaskOrderingTest` | 10 | TaskCreation (1), MoveToColumn (9) | `AbstractAppMockMvcTest` | **SPLIT**: `TaskCreation` -> `controller/ColumnControllerTest.AddTaskByColumnId`; `MoveToColumn` -> `e2e/task/TaskMoveTest.MoveToColumn` | **SPLIT** | Verified by reading both target files in full -- the todo's own guess (fold everything into `TaskControllerTest`) does not hold. `TaskCreation`'s one test posts to `getColumnTasksUrl(columnId)`, which is `ColumnController`'s add-task route (`POST /boards/{boardId}/columns/{columnId}`), **not** `TaskController`'s own routes -- its natural home is `ColumnControllerTest.AddTaskByColumnId`, which today has 2 tests (add succeeds / column-not-found) and zero position-contiguity coverage; the moved test adds a genuinely new case, not a duplicate. `MoveToColumn`'s 9 tests overlap `e2e/task/TaskMoveTest.MoveToColumn`'s existing tests on exactly **one** test (see row 11's drop list) -- the other 8 add position-value assertions (`positionsOf`, `orderedTaskIds`) `TaskMoveTest` does not currently make anywhere. |
| 11 | (carried from row 10) | -- | -- | -- | -- | **1 drop, 8 keep** | **DROP** `shouldReturnBadRequest_whenTargetColumnIsOnDifferentBoard_beforePositionWorkRuns` -- read side by side against `TaskMoveTest.CrossBoardTarget.shouldReturnBadRequest_whenTargetColumnIsOnDifferentBoardOwnedBySameUser`: identical arrange (creates a column on `mockEmptyBoards.get(0)`, attempts to move `mockPopulatedTask` there), identical assert (400); `TaskMoveTest`'s version is already a strict superset (also asserts `recordedMovedEvents().isEmpty()` and source-column membership). Genuine duplicate. **KEEP** (carry across unchanged, all add position-value assertions `TaskMoveTest` does not make): `shouldMoveThirdTaskToFront_andShiftOthersDown_whenTargetPositionIsZeroInSameColumn`; `shouldLeaveBothColumnsContiguous_whenMovingTaskToDifferentColumnAtPositionZero`; `shouldAppendAtEnd_whenTargetPositionIsOmitted`; `shouldClampToEnd_whenTargetPositionExceedsDestinationSize`; `shouldReturnBadRequest_whenTargetPositionIsNegative`; `shouldReturnConflict_andLeavePositionsUnchanged_whenVersionIsStale` (distinct from `TaskMoveTest.StaleVersion`'s test -- that one asserts membership + zero events after a prior successful move + stale retry; this one asserts exact position values on two fresh tasks with no prior successful move); `shouldReturnSameOrderTwice_whenReadingSameColumnRepeatedly`; `shouldReturnTasksSortedByPosition_overHttp`. |
| 12 | `ThemePersistenceTest` | 9 | GetTheme, UpdateTheme | `AbstractAppMockMvcTest` | `controller/ThemePersistenceTest` | **RELOCATE** | Verified: no `controller/UserControllerTest` exists anywhere in the tree. This file is the de facto `UserController` test (`GET`/`PUT /api/users/me/theme`). No duplicate. Filename kept as-is (accurately describes what it tests) rather than renamed to `UserControllerTest`, to keep this a pure relocation with no identity change. |

**Net effect on file count:** 11 stray files -> 0 remain in the root package (`KanbanBoardApplicationTests`
excepted, per row 8) once Task 3 executes every row above. 5 relocate 1:1 (`ActivityLogCleanupIsolationTest`,
`BoardCreationE2ETest`, `BoardFullReadTest`, `EventIdGeneratorTest`, `FlywaySchemaProvenanceTest`), 1
relocates keeping its own identity (`ThemePersistenceTest`), 1 relocates as the unambiguous tracer
(`SubtaskLockingTest`), 2 fold into the same existing file (`ColumnDeletionTest` + `ColumnOrderingTest`
-> `controller/ColumnControllerTest`), 1 splits across two existing files with one dropped duplicate
(`TaskOrderingTest`), and 1 stays exempted (`KanbanBoardApplicationTests`).

**Test-count arithmetic for Task 3's non-decreasing gate:** 11 files carry 65 `@Test` methods today
(2+9+8+4+8+3+13+1+4+10+9... wait, counted directly: 2+9+8+4+8+3+13+1+4+10+9 = enumerate by row:
ActivityLogCleanupIsolationTest 2, BoardCreationE2ETest 9, BoardFullReadTest 8, ColumnDeletionTest 4,
ColumnOrderingTest 8, EventIdGeneratorTest 3, FlywaySchemaProvenanceTest 13,
KanbanBoardApplicationTests 1, SubtaskLockingTest 4, TaskOrderingTest 10, ThemePersistenceTest 9 =
**71 tests total** across the 11 files). Exactly 1 test is dropped as a genuine duplicate (row 11).
Task 3's gate: final suite test count >= 430 - 1 + 0 = **429 is the floor this task's own drop
accounts for, but since nothing outside these 71 changes, the real expectation is 430 total unchanged
minus the 1 duplicate removed, i.e. final count should read 429 net of this task's own bookkeeping --
Task 3 must show the actual post-relocation number and reconcile it against this arithmetic explicitly,
since a second, unnoticed drop during the mechanical fold/split work is exactly what the test-count
gate exists to catch.**

---

## Self-Check

- `test -f build/reports/jacoco/test/jacocoTestReport.xml` -- present.
- `test -f build/reports/jacoco/test/html/index.html` -- present.
- `test -f lombok.config` -- present.
- `./gradlew clean test jacocoTestReport` -- BUILD SUCCESSFUL, 430/430 tests green, twice
  (once for the baseline run, once more with `--profile` for accurate task-level timing).
- Zero test files moved or modified in this task.

---

## Section 5 -- Closing State (Task 4)

**D-01 -- rung in force:** option-a, ratchet at the measured baseline. `build.gradle`'s
`jacocoTestCoverageVerification` enforces `INSTRUCTION >= 0.90`, `LINE >= 0.90`,
`BRANCH >= 0.75` -- a few points below Task 1's measured 91.23% / 90.93% / 78.62% (Section 1), so
today's suite passes with headroom against ordinary code-shape noise rather than an exact-equality
gate that would spuriously fail the next PR. Wired into `./gradlew test` itself via
`tasks.named('test') { finalizedBy jacocoTestCoverageVerification }`, since this repo's CI
(`.github/workflows/deploy.yml`) invokes `./gradlew test` and `./gradlew spotlessCheck` directly
and never runs `check` (which is where Gradle's jacoco plugin would otherwise leave the
verification task unwired). `fastTest` (the pre-commit gate) remains untouched -- no coverage
instrumentation, per trade-off 3. The two zero-coverage `atLeastOneFieldPopulated()` gaps
(`UpdateTaskRequestDTO`, `UpdateSubtaskRequestDTO`) and the confirmed-dead
`DeleteBoardByIdRequestDTO` are accepted as pre-existing baseline, not force-fixed by this
ratchet, per the Task 2 decision.

**D-02 -- ArchUnit zero-coverage rule:** **no**, per operator decision at the Task 2 checkpoint,
agreeing with this document's own Section 3 recommendation. JaCoCo's per-method report already
subsumes the coarser class-existence check -- Section 3 showed a concrete case
(`UpdateTaskRequestDTO`/`UpdateSubtaskRequestDTO`) where a class-existence rule would have reported
"covered" while the class's own most safety-relevant method sat at 0%, which is precisely the
failure mode a class-existence check cannot see by construction. Not implemented; this reasoning is
recorded here so the option is not silently re-litigated in a future session without first reading
why it was declined.

**D-03 -- file dispositions:** confirmed and executed exactly as Section 4's table states, all 11
rows, zero changes at the Task 2 checkpoint. See the quick task's Task 3 commit for the full
disposition-to-execution mapping.

**Final test count reconciliation:** pre-work baseline 430 (STATE.md, quick task 260811-ufu) -> 429
after the fold/split's one genuine dropped duplicate (Section 4's arithmetic, confirmed exactly by
Task 3's actual run) -> 430 again after Task 3 added `TestPlacementArchTest` (1 new `@ArchTest`).
Final count as measured by `./gradlew clean test --rerun-tasks` at Task 4 close: **430 tests, 0
failures, 0 errors** -- non-decreasing against the 430 pre-work baseline, with the arithmetic
explicitly reconciled rather than silently assumed to match.

**Coverage regression check (Task 3's gate, T-eg8-03):** post-relocation `jacocoTestReport` total:
91% instruction (406 of 4,629 missed -- identical to Task 1's 91.23% baseline), 78% branch (34 of
159 missed -- identical to Task 1's 78.62% baseline). No `src/main` code changed in this quick
task, so an identical total was the expected outcome; confirmed, not assumed.
