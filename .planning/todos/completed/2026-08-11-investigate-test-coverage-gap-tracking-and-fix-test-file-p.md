---
created: 2026-08-11T00:00:00.000Z
title: Investigate test coverage gap tracking (JaCoCo) and fix test file placement drift into the package root
area: backend
severity: minor
files:
  - build.gradle
  - docs/CODE_STYLE.md
  - src/test/java/com/vrudenko/kanban_board/ActivityLogCleanupIsolationTest.java
  - src/test/java/com/vrudenko/kanban_board/BoardCreationE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/BoardFullReadTest.java
  - src/test/java/com/vrudenko/kanban_board/ColumnDeletionTest.java
  - src/test/java/com/vrudenko/kanban_board/ColumnOrderingTest.java
  - src/test/java/com/vrudenko/kanban_board/EventIdGeneratorTest.java
  - src/test/java/com/vrudenko/kanban_board/FlywaySchemaProvenanceTest.java
  - src/test/java/com/vrudenko/kanban_board/KanbanBoardApplicationTests.java
  - src/test/java/com/vrudenko/kanban_board/SubtaskLockingTest.java
  - src/test/java/com/vrudenko/kanban_board/TaskOrderingTest.java
  - src/test/java/com/vrudenko/kanban_board/ThemePersistenceTest.java
resolved: 2026-08-12T00:00:00.000Z
resolved_by: Quick task 260812-eg8
---

## Problem

Two related test-hygiene gaps, surfaced in the same conversation and bundled here since a single
audit pass over the test tree would naturally touch both.

**1. No test coverage gap tracking exists today.** There is currently no reliable way to answer
"is this `src/main` file tested at all, and if so, is it fully covered?" — e.g. a controller file
might have a corresponding `*ControllerTest` but only 3 of its 5 routes actually exercised, and
nothing flags the other 2. `./gradlew test` passing proves existing tests pass, not that coverage
is complete or even present for a given class.

**2. Eleven test files sit directly in the `com.vrudenko.kanban_board` package root**, bypassing
the subpackage structure (`e2e/`, `controller/`, `service/`, `security/`, `activitylog/`, `dto/`,
`event/`, `handler/`, `config/`, `architecture/`, `support/`) that Phase 7's test-folder
restructure (`.planning/phases/07-restructure-test-folder-separate-setup-from-tests-evaluate-n/`)
deliberately established:

- `ActivityLogCleanupIsolationTest.java`
- `BoardCreationE2ETest.java`
- `BoardFullReadTest.java`
- `ColumnDeletionTest.java`
- `ColumnOrderingTest.java`
- `EventIdGeneratorTest.java`
- `FlywaySchemaProvenanceTest.java`
- `KanbanBoardApplicationTests.java`
- `SubtaskLockingTest.java`
- `TaskOrderingTest.java`
- `ThemePersistenceTest.java`

Observed cause: across GSD sessions, when a new test class is created mid-task, the executor
sometimes has no explicit guidance on *where* a new test file belongs and defaults to the package
root rather than the matching subpackage — the convention lives only implicitly in the existing
directory layout, not written down anywhere an executor would read it before creating a file.

One concrete example of the deeper problem this causes: `TaskOrderingTest` (root) almost certainly
duplicates coverage that belongs as a `@Nested` group inside `controller/TaskControllerTest`
(matching the pattern `docs/CODE_STYLE.md` rule 4/5 already establishes for other controllers) —
this is exactly the kind of drift that's invisible without either a placement rule or a coverage
audit to catch it.

## Solution

Not scoped in detail here — needs its own investigation before implementation, per this repo's
established design-fork pattern. At minimum the investigation should answer:

1. **Coverage tracking approach:** evaluate JaCoCo (`jacocoTestCoverageVerification`) for
   per-class/per-branch coverage gating — this is the tool that can actually catch "route exists,
   isn't tested," not just "test file is missing." Decide scope (which packages/classes are
   gated), threshold, and whether it's a hard `./gradlew test` gate or a separate report-only task,
   following the same measure-first-then-pick-a-rung approach this repo used for the ErrorProne
   rollout (`.planning/quick/260802-qr8-*`, `260803-v23-*`). Consider pairing with a lightweight
   ArchUnit rule for the *zero-coverage* case (no test class exists at all) — cheaper to check than
   coverage %, but coarse (proves a test class exists, not that it's complete), and needs an
   exemption list for classes legitimately untested in isolation (e.g. simple DTOs only exercised
   via controller tests).
2. **Test file placement guidance:** add an explicit rule to `docs/CODE_STYLE.md` (or wherever
   future GSD executors reliably read it, e.g. referenced from CLAUDE.md) stating which subpackage
   a new test class belongs in based on what it tests, so this doesn't silently recur every few
   sessions.
3. **Fix the existing 11 stray files:** for each, move it into its correct subpackage matching
   Phase 7's convention — and for `TaskOrderingTest` specifically, investigate whether it should be
   deleted in favor of folding its cases as a `@Nested` group into `controller/TaskControllerTest`
   rather than just relocated as-is (check the other root-level files for the same
   duplicate-vs-relocate question, e.g. `ColumnOrderingTest`, `ColumnDeletionTest`,
   `SubtaskLockingTest` against their sibling `*ControllerTest`/`*E2ETest` classes).

## Resolution (260812-eg8)

**Coverage tracking (question 1): JaCoCo, report-only measured first, then a ratchet gate.**
Gradle-core `jacoco` plugin, pinned `toolVersion = '0.8.12'`, attached to `test` only (never
`fastTest`, +26.4% instrumentation overhead measured -- steeper than the "typically 5-20%" this
todo's own investigation expected, recorded as a real finding rather than rounded down).
Denominator made honest per Lombok/MapStruct/Avro generated code (new `lombok.config` at the repo
root -- this repo had none before, the single highest-value finding of the whole task).
`jacocoTestCoverageVerification` now enforces `INSTRUCTION >= 0.90`, `LINE >= 0.90`,
`BRANCH >= 0.75` -- a ratchet set a few points below the measured baseline (91.23% / 90.93% /
78.62%), chosen by the operator from real numbers at a blocking checkpoint (`260812-eg8-MEASUREMENTS.md`),
mirroring this repo's ErrorProne rollout precedent. Wired into `./gradlew test` itself via
`finalizedBy`, since this repo's CI never runs `check`. The ArchUnit zero-coverage complement this
todo asked about (question 1's second half) was investigated and explicitly declined -- see
`260812-eg8-MEASUREMENTS.md` Section 3 for why it would have added strictly less signal than
JaCoCo's own per-method report on this codebase's own motivating example.

**Test placement guidance (question 2): `docs/CODE_STYLE.md` rule 13** (which subpackage; composes
with rule 4's purpose test) plus `architecture/TestPlacementArchTest.java`, an ArchUnit guard that
fails `./gradlew test` if a new test class lands directly in the root package outside the single
named exemption (`KanbanBoardApplicationTests`). Falsified with a throwaway root-package class
before landing (confirmed red, then green again after deletion) -- a written rule alone is what
already failed here once, so this one was made mechanically unreopenable.

**The 11 stray files: fixed, per an audited disposition table, not per this todo's own guesses.**
7 relocate 1:1 (`ActivityLogCleanupIsolationTest`, `BoardCreationE2ETest`, `BoardFullReadTest`,
`EventIdGeneratorTest`, `FlywaySchemaProvenanceTest`, `SubtaskLockingTest`, `ThemePersistenceTest`
-- the last two keep their own filenames rather than being renamed to match a `*ControllerTest`
convention, since each is the de facto single-purpose test for a route with no existing sibling
class). `ColumnDeletionTest` + `ColumnOrderingTest` fold into `controller/ColumnControllerTest`
(0 drops -- their same-named `DeleteById` nested classes test different properties of the same
route, not the same thing twice). `TaskOrderingTest` splits across
`controller/ColumnControllerTest.AddTaskByColumnId` (1 test) and
`e2e/task/TaskMoveTest.MoveToColumn` (9 tests examined, 1 dropped as a genuine duplicate, 8 kept).
`KanbanBoardApplicationTests` stays exempted at root, per the operator's confirmation at the Task 2
checkpoint. Full audit trail (per-file `@Test`/`@Nested` counts, verified-not-assumed disposition
reasons) in `260812-eg8-MEASUREMENTS.md` Section 4.

**What this todo's own investigation got right and wrong, found only by reading every file:**
- **Right:** `ColumnOrderingTest`/`ColumnDeletionTest` and `SubtaskLockingTest` needed the same
  duplicate-vs-relocate scrutiny this todo asked for.
- **Wrong (corrected):** `TaskOrderingTest` does **not** fold into `controller/TaskControllerTest`
  as this todo predicted -- `TaskControllerTest` has no move or creation group at all. The real
  overlap was elsewhere: `TaskOrderingTest.TaskCreation` posts to `ColumnController`'s own
  add-task-by-column-id route (not any `TaskController` route), and
  `TaskOrderingTest.MoveToColumn` overlaps `e2e/task/TaskMoveTest.MoveToColumn`, an existing class
  this todo did not name at all.
- **Not anticipated by this todo:** a second duplicate pair, `ColumnDeletionTest.DeleteById` /
  `ColumnOrderingTest.DeleteById` (same nested-class name, different tested property -- neither a
  duplicate of the other, discovered only by reading both bodies); and that `ThemePersistenceTest`
  is the de facto `UserController` test, standing in for a `controller/UserControllerTest` that
  does not exist anywhere in the tree.

**Out-of-scope finding filed separately:** a pre-existing, unrelated flaky assertion in
`GlobalExceptionHandlerTest.AccessDeniedTest` (a random-word DTO fixture occasionally collides with
RFC 7807's literal `"about:blank"` boilerplate) was discovered while retrying this task's own
pre-commit gate -- recorded in
`.planning/quick/260812-eg8-investigate-test-coverage-gap-tracking-j/deferred-items.md`, not fixed
here (out of this task's own file scope).

Full suite: 430 -> 429 (the fold/split's one genuine dropped duplicate) -> 430 again
(`TestPlacementArchTest`, one new `@ArchTest`). Final measured count: 430 tests, 0 failures, 0
errors. `spotlessCheck` and `./gradlew test` (now enforcing the coverage ratchet) both green.
