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
