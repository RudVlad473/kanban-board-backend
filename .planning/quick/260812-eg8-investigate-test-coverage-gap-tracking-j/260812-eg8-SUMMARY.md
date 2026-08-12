---
phase: quick-260812-eg8
plan: 01
subsystem: testing
tags: [jacoco, coverage, archunit, test-hygiene, gradle]

requires: []
provides:
  - "JaCoCo coverage report (jacocoTestReport) with Lombok/MapStruct/Avro generated code excluded from the denominator -- one documented command answers whether a given src/main class is tested, and how completely"
  - "jacocoTestCoverageVerification ratchet gate (INSTRUCTION >= 0.90, LINE >= 0.90, BRANCH >= 0.75), wired into ./gradlew test itself via finalizedBy, since this repo's CI never runs check"
  - "lombok.config at the repo root (config.stopBubbling = true, addLombokGeneratedAnnotation = true) -- did not exist before this task; makes JaCoCo's built-in @Generated filter actually work"
  - "docs/CODE_STYLE.md rule 13 (test file placement) and architecture/TestPlacementArchTest.java -- an ArchUnit guard, falsified before landing, that fails ./gradlew test if a new test class lands directly in the root package outside the KanbanBoardApplicationTests exemption"
  - "11 stray root-package test files relocated/folded/split into their correct subpackages, closing the drift Phase 7's test-folder restructure had intended to prevent"
affects: [any future GSD session adding a new test class -- rule 13 + the ArchUnit guard now answer "where does this file go" before drift can recur; any future coverage-threshold discussion -- the ratchet's measured provenance is recorded in build.gradle]

actuals:
  tokens: 41000
  tasks: 4
  commits: 6

tech-stack:
  added: [jacoco (Gradle-core plugin, toolVersion 0.8.12, pinned)]
  patterns:
    - "Coverage report and coverage verification share one generated-code exclusion list (jacocoGeneratedCodeExcludes in build.gradle) -- a ratchet checked against a different denominator than the report a human read when choosing the threshold is a bug waiting to be misread"
    - "A new test class's home is decided by two composed rules read together: CODE_STYLE.md rule 4 (which purpose/tier -- service/controller/e2e) and rule 13 (which subpackage -- never the root package), the latter mechanically enforced by TestPlacementArchTest"
    - "FOLD/SPLIT dispositions for stray test files are decided by reading both source and destination files in full, not by nested-class name alone -- two same-named DeleteById groups in this task's own audit tested different properties of the same route and were not duplicates"

key-files:
  created:
    - lombok.config
    - src/test/java/com/vrudenko/kanban_board/architecture/TestPlacementArchTest.java
    - .planning/quick/260812-eg8-investigate-test-coverage-gap-tracking-j/260812-eg8-MEASUREMENTS.md
    - .planning/quick/260812-eg8-investigate-test-coverage-gap-tracking-j/deferred-items.md
    - .planning/todos/pending/2026-08-12-globalexceptionhandlertest-accessdeniedtest-flaky-against-.md
  modified:
    - build.gradle
    - docs/CODE_STYLE.md
    - src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java
    - src/test/java/com/vrudenko/kanban_board/e2e/task/TaskMoveTest.java
    - .planning/todos/completed/2026-08-11-investigate-test-coverage-gap-tracking-and-fix-test-file-p.md (moved from pending/, resolution appended)
  relocated:
    - "ActivityLogCleanupIsolationTest -> activitylog/"
    - "BoardCreationE2ETest -> e2e/board/"
    - "BoardFullReadTest -> controller/"
    - "EventIdGeneratorTest -> config/"
    - "FlywaySchemaProvenanceTest -> config/"
    - "SubtaskLockingTest -> e2e/subtask/ (tracer)"
    - "ThemePersistenceTest -> controller/"
  deleted:
    - "ColumnDeletionTest.java (folded into controller/ColumnControllerTest)"
    - "ColumnOrderingTest.java (folded into controller/ColumnControllerTest)"
    - "TaskOrderingTest.java (split across controller/ColumnControllerTest and e2e/task/TaskMoveTest)"

key-decisions:
  - "D-01 (operator, Task 2 checkpoint): option-a, ratchet at the measured baseline (91.23%/90.93%/78.62%), not report-only-forever (option-b) or a per-class minimum (option-c). The two pre-existing atLeastOneFieldPopulated() zero-coverage gaps are accepted as baseline, not force-fixed."
  - "D-02 (operator, Task 2 checkpoint): no ArchUnit zero-coverage complement -- agreed with this task's own Section 3 finding that JaCoCo's per-method report already subsumes a class-existence check, using this task's own motivating example (UpdateTaskRequestDTO is referenced everywhere but one method sits at 0%) as the concrete reason a binary check would have missed it."
  - "D-03 (operator, Task 2 checkpoint): all 11 file dispositions confirmed exactly as measured in Section 4's table -- including the two duplicate pairs this task's own audit found that the source todo did not anticipate (ColumnDeletionTest/ColumnOrderingTest's same-named-but-different-property DeleteById groups, and TaskOrderingTest's real overlap being TaskMoveTest, not TaskControllerTest as the todo guessed)."
  - "ColumnControllerTest's base class changed from AbstractAppTest to AbstractAppMockMvcTest (a strict superset) to host the folded-in nested classes' real signinCookie() auth alongside the file's existing .with(user()) shortcut -- both styles kept as-is per the fold's own instruction to preserve dialect, not normalise it."
  - "jacocoTestCoverageVerification wired to ./gradlew test via finalizedBy, not by relying on the check lifecycle -- this repo's CI (.github/workflows/deploy.yml) invokes test and spotlessCheck directly and never runs check, so the default Gradle jacoco-plugin wiring would have left the gate silently unenforced."

patterns-established:
  - "docs/CODE_STYLE.md rule 13 + TestPlacementArchTest is the mechanically-unreopenable placement guard, matching this repo's established audit-then-gate-then-dispose precedent (260811-qru)."
  - "A coverage threshold in this repo is recorded with its measured provenance directly in build.gradle (rung, source number, date, quick-task id) -- the same shape as the existing ErrorProne and maxParallelForks comment blocks."

requirements-completed: [COV-01, PLACE-01, PLACE-02]

coverage:
  - id: D1
    description: "One documented command (./gradlew test jacocoTestReport) answers whether a src/main class is tested and how completely, with Lombok/MapStruct/Avro excluded from the denominator"
    requirement: "COV-01"
    verification:
      - kind: other
        ref: "build/reports/jacoco/test/{html/index.html,test/jacocoTestReport.xml} generated; lombok.config present; 260812-eg8-MEASUREMENTS.md Section 1 records the measured baseline by name"
        status: pass
    human_judgment: false
  - id: D2
    description: "The enforcement rung is the one the operator chose from measured numbers at the Task 2 blocking checkpoint, not a guessed threshold"
    requirement: "COV-01"
    verification:
      - kind: other
        ref: "build.gradle jacocoTestCoverageVerification block + its provenance comment; ./gradlew jacocoTestCoverageVerification passes against the measured baseline"
        status: pass
    human_judgment: false
  - id: D3
    description: "Zero test classes remain directly in com.vrudenko.kanban_board beyond the named exemption (KanbanBoardApplicationTests)"
    requirement: "PLACE-01"
    verification:
      - kind: other
        ref: "find src/test/java/com/vrudenko/kanban_board -maxdepth 1 -name '*.java' -- exactly 1 file (KanbanBoardApplicationTests.java)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Creating a new test class in the package root fails ./gradlew test"
    requirement: "PLACE-01"
    verification:
      - kind: unit
        ref: "architecture/TestPlacementArchTest.java, falsified by a throwaway root-package class (confirmed red via ./gradlew test --tests TestPlacementArchTest, then confirmed green again after deletion)"
        status: pass
    human_judgment: false
  - id: D5
    description: "docs/CODE_STYLE.md documents where a new test class belongs, composing with rule 4 and citing its enforcing ArchUnit class"
    requirement: "PLACE-02"
    verification:
      - kind: other
        ref: "docs/CODE_STYLE.md rule 13 -- cross-references rule 4 explicitly, names TestPlacementArchTest as the enforcing mechanism"
        status: pass
    human_judgment: false
  - id: D6
    description: "No test coverage was lost during the relocation/fold/split -- test count non-decreasing, no JaCoCo regression"
    requirement: "PLACE-01"
    verification:
      - kind: other
        ref: "Suite: 430 (pre-work) -> 429 (net of the fold's one genuine dropped duplicate) -> 430 (TestPlacementArchTest added). Final measured: 430 tests, 0 failures. jacocoTestReport total unchanged at 91% instruction / 78% branch against Task 1's baseline (no src/main code changed)."
        status: pass
    human_judgment: false

duration: ~2h10min
completed: 2026-08-12
status: complete
---

# Quick Task 260812-eg8: Investigate Test Coverage Gap Tracking (JaCoCo) and Fix Test File Placement Drift Summary

**Wired a pinned, report-and-ratchet JaCoCo setup (Lombok/MapStruct/Avro excluded from the denominator, enforced via `./gradlew test`) and relocated/folded/split all 11 stray root-package test files into their correct subpackages, gated by a falsified ArchUnit guard so the drift cannot silently recur.**

## Performance

- **Duration:** ~2h10min
- **Completed:** 2026-08-12
- **Tasks:** 4 (tracer measurement + audit, blocking checkpoint, relocation/fold/split + ArchUnit guard, coverage ratchet + todo closure)
- **Files modified:** 20 (5 created, 5 modified in the final commit alone; 11 test files relocated/folded/split across all tasks; 2 `.planning/` todo files)

## Accomplishments

- Wired the Gradle-core `jacoco` plugin (pinned `toolVersion = '0.8.12'`), attached to `test` only (never `fastTest`, which measured +26.4% instrumentation overhead — steeper than the ~5-20% expected, recorded as a real finding rather than rounded down to match the estimate).
- Created `lombok.config` at the repo root (`addLombokGeneratedAnnotation = true`, `config.stopBubbling = true`) — this repo had none before, and without it every Lombok-generated accessor/builder/`equals`/`hashCode` would have systematically deflated the coverage baseline the whole task's D-01 decision depended on.
- Measured a trustworthy baseline (91.23% instruction / 90.93% line / 78.62% branch on hand-written `src/main`), sanity-checked the two-fork merge as non-degenerate, and named the actual zero-coverage classes by name — including confirming an already-filed pending todo (`DeleteBoardByIdRequestDTO`) independently from real execution data, and finding both `Update*RequestDTO.atLeastOneFieldPopulated()` validators genuinely never exercised despite their DTOs being referenced everywhere.
- Presented three evidence-backed D-01 options and a D-02 recommendation at a blocking checkpoint; the operator selected option-a (ratchet at measured baseline) and declined D-02 (ArchUnit zero-coverage complement), agreeing with this task's own finding that a class-existence check would have missed the exact `atLeastOneFieldPopulated()` failure mode JaCoCo caught.
- Audited all 11 stray root-package files by reading every one (and every named sibling/duplicate candidate) in full, producing a disposition table with per-`@Test`/`@Nested` counts and a reason for each RELOCATE/FOLD/SPLIT/EXEMPT call — confirmed unread-verified, not trusted from the plan's own starting frame.
- Executed the confirmed dispositions: 7 relocate 1:1, `ColumnDeletionTest`+`ColumnOrderingTest` fold into `controller/ColumnControllerTest` with zero drops (their same-named `DeleteById` groups tested different properties of the same route), `TaskOrderingTest` splits across `controller/ColumnControllerTest.AddTaskByColumnId` and `e2e/task/TaskMoveTest.MoveToColumn` (1 genuine duplicate dropped, 8 kept), `KanbanBoardApplicationTests` stays exempted at root.
- Added `docs/CODE_STYLE.md` rule 13 and `architecture/TestPlacementArchTest.java`, an ArchUnit guard falsified with a throwaway root-package class (confirmed red, then confirmed green again after deletion) before landing — a written rule alone is what already failed here once.
- Applied the D-01 ratchet to `build.gradle`, wired to `./gradlew test` via `finalizedBy` (this repo's CI never runs `check`, where Gradle's jacoco plugin would otherwise leave verification silently unwired), sharing one generated-code exclusion list with the report so the enforced number and the human-read number can never silently diverge.
- Closed the source todo with a resolution documenting what the todo's own guesses got right and wrong; filed a new `[minor]` todo for a pre-existing, unrelated flaky assertion (`GlobalExceptionHandlerTest.AccessDeniedTest` colliding with RFC 7807's literal `"about:blank"`) discovered while retrying this task's own pre-commit gate.
- Reconciled the final test count explicitly rather than assuming it: 430 (pre-work) → 429 (net of the fold's one dropped duplicate) → 430 again (`TestPlacementArchTest`, one new `@ArchTest`) — matching the plan's own pre-computed arithmetic exactly.

## Task Commits

1. **Task 1 (tracer — wire report-only JaCoCo, audit all 11 stray files):** `06e9e24` (`feat`)
2. **Task 2 (blocking checkpoint — D-01/D-02/D-03 decisions):** no code commit; operator decision relayed and applied in Tasks 3-4
3. **Task 3a (tracer within Task 3 — relocate SubtaskLockingTest, prove the mechanic):** `773f45d` (`refactor`)
4. **Task 3b (relocate/fold/split remaining 10 files, add CODE_STYLE rule 13 + TestPlacementArchTest):** `4c7c933` (`refactor`)
5. **Task 4 (apply the D-01 ratchet, close the source todo):** `5616727` (`feat`)

**Plan metadata:** `fa99743` (plan), `57e7b3f` (context artifact) — both pre-date this execution session.

## Files Created/Modified

See `key-files` in frontmatter for the full list, including the `relocated`/`deleted` breakdown of the 11 stray test files.

## Decisions Made

See `key-decisions` in frontmatter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `ColumnControllerTest`'s base class changed from `AbstractAppTest` to `AbstractAppMockMvcTest`**
- **Found during:** Task 3 (folding `ColumnDeletionTest`/`ColumnOrderingTest` into `ColumnControllerTest`)
- **Issue:** The fold's incoming nested classes call `signinCookie()`, a method only `AbstractAppMockMvcTest` provides; `ColumnControllerTest` extended the narrower `AbstractAppTest`. Compiling as planned would have failed outright.
- **Fix:** Changed the `extends` clause to `AbstractAppMockMvcTest` (a strict superset of `AbstractAppTest` — every existing test in the file keeps working unchanged) rather than rewriting the folded-in tests' auth style to `.with(user())`, preserving the plan's own instruction to carry dialect across unchanged.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java`
- **Verification:** Full suite green (430 tests, 0 failures) after the change; both auth styles (`signinCookie()` and `.with(user())`) coexist correctly in the same file.
- **Committed in:** `4c7c933` (Task 3 commit)

**2. [Rule 3 - Blocking] Renamed a helper method to avoid a same-name/different-semantics collision during the `TaskMoveTest` merge**
- **Found during:** Task 3 (splitting `TaskOrderingTest.MoveToColumn` into `TaskMoveTest.MoveToColumn`)
- **Issue:** `TaskOrderingTest`'s POST-add-task-to-column URL helper and `TaskMoveTest`'s own pre-existing GET-list-tasks helper both wanted the name `getColumnTasksUrl` with different arities and completely different HTTP semantics — legal Java overloading, but confusing and against this codebase's naming clarity.
- **Fix:** Named the newly-added helper `getAddTaskUrl(String columnId)` instead, with a comment explaining the rename's reason.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/e2e/task/TaskMoveTest.java`
- **Verification:** Compiles cleanly; full suite green.
- **Committed in:** `4c7c933` (Task 3 commit)

---

**Total deviations:** 2 auto-fixed (1 bug/mechanical-necessity, 1 blocking/naming-clarity)
**Impact on plan:** Both were necessary consequences of executing the approved FOLD/SPLIT dispositions correctly, not scope creep — neither changes any test's assertions or behavior.

## Issues Encountered

- **Pre-existing flaky assertion discovered mid-task, out of scope.** `GlobalExceptionHandlerTest.AccessDeniedTest` failed twice during pre-commit gate retries (unrelated to any file this task touched) — a `dataFactory.getRandomWord(...)`-generated board name occasionally produces the word `"about"`, colliding with RFC 7807's literal `"type":"about:blank"` boilerplate present in every response. Confirmed as pre-existing flakiness (passes reliably in isolation), not a regression. Recorded in `deferred-items.md` and filed as its own new todo per SCOPE BOUNDARY, not fixed here.
- **Windows sandbox pre-commit hook timeouts, twice.** Each commit runs `.githooks/pre-commit` (`spotlessCheck` + `fastTest`, several minutes). Two early attempts were cut short by tool-call timeouts; one left a Gradle daemon file lock (`Unable to delete directory ...\fastTest\binary`), resolved by `./gradlew --stop` run alone before retrying — matching the documented pattern in `docs/SESSION_LESSONS.md` and prior sessions' summaries. No destructive git operation was used; no test was weakened or skipped to work around the timeout.

## Known Stubs

None.

## Threat Flags

None beyond what the plan's own `<threat_model>` already covers — all four named threats (T-eg8-01 through T-eg8-04) were disposed within scope: T-eg8-01 (JaCoCo artifact tampering) mitigated by the pinned `toolVersion`; T-eg8-02 (report disclosure) accepted, reports stay under gitignored `build/`; T-eg8-03 (fold/split silently dropping assertions) mitigated by the dual non-decreasing-test-count and no-coverage-regression gates, both confirmed with real numbers rather than assumed; T-eg8-04 (an inert ArchUnit rule) mitigated by the falsification teeth-check (red, then green again). T-eg8-SC (package-legitimacy) not applicable — no npm/pip/cargo package was added.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- Coverage gap tracking now exists as a real, enforced, documented mechanism — the source todo's original "is this file tested at all" question is answerable on demand via `./gradlew jacocoTestReport`, and a future class going dark now fails `./gradlew test` via the ratchet.
- Test file placement drift is closed and mechanically unreopenable — `TestPlacementArchTest` fails the build the moment a new test class lands in the root package outside the one named exemption.
- One new `[minor]` todo filed (`GlobalExceptionHandlerTest.AccessDeniedTest` flakiness) — independent, does not block any subsequent work.
- No blockers for subsequent phase work; this was a standalone quick task.

---
*Quick task: 260812-eg8*
*Completed: 2026-08-12*

## Self-Check: PASSED

- FOUND: `lombok.config`
- FOUND: `src/test/java/com/vrudenko/kanban_board/architecture/TestPlacementArchTest.java`
- FOUND: `.planning/quick/260812-eg8-investigate-test-coverage-gap-tracking-j/260812-eg8-MEASUREMENTS.md`
- FOUND: `.planning/quick/260812-eg8-investigate-test-coverage-gap-tracking-j/deferred-items.md`
- FOUND: `.planning/todos/pending/2026-08-12-globalexceptionhandlertest-accessdeniedtest-flaky-against-.md`
- FOUND: `.planning/todos/completed/2026-08-11-investigate-test-coverage-gap-tracking-and-fix-test-file-p.md`
- FOUND: `src/test/java/com/vrudenko/kanban_board/e2e/subtask/SubtaskLockingTest.java`
- FOUND: `src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java`
- FOUND: `src/test/java/com/vrudenko/kanban_board/e2e/task/TaskMoveTest.java`
- FOUND: `src/test/java/com/vrudenko/kanban_board/KanbanBoardApplicationTests.java`
- FOUND commit `06e9e24` (Task 1)
- FOUND commit `773f45d` (Task 3a tracer)
- FOUND commit `4c7c933` (Task 3b)
- FOUND commit `5616727` (Task 4)
- FOUND commit `fa99743` (plan, pre-dates this session)
- FOUND commit `57e7b3f` (context artifact, pre-dates this session)
