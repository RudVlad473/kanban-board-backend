---
phase: quick/260802-q6n
plan: 01
subsystem: testing
tags: [archunit, junit5, layering, ownership-verification, code-style-enforcement]

# Dependency graph
requires: []
provides:
  - "LayeringArchTest: two build-failing ArchUnit rules enforcing controller->service->repository layering and CODE_STYLE.md rule 2's ownership-verified findById convention"
  - "SubtaskService no longer exposes an unverified, ownership-bypassing findById(String) overload"
affects: [docs/CODE_STYLE.md, service-layer, controller-layer]

actuals:
  tokens: 8500
  tasks: 3
  commits: 3

tech-stack:
  added: ["com.tngtech.archunit:archunit-junit5:1.4.2 (testImplementation)"]
  patterns:
    - "ArchUnit rules as @ArchTest static fields under @AnalyzeClasses, discovered by the already-enabled useJUnitPlatform() with no new Gradle task"
    - "Package-scoped predicates (com.vrudenko.kanban_board.repository.. rather than ..repository..) to avoid false positives against Spring's own repository types"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java
  modified:
    - build.gradle
    - src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java
    - src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java
    - docs/CODE_STYLE.md

key-decisions:
  - "Chose ArchUnit-as-JUnit5-tests over Checkstyle/PMD (no cross-file type resolution, would degrade rule 2 into a defeatable name regex) and over a custom ErrorProne compile-time checker (too large a unit of work for two rules, would entangle with the separately-tracked ErrorProne todo)"
  - "Fixed the pre-existing SubtaskService.findById(String) violation rather than exempting it from rule 2, so the rule could be written at full strength from day one"
  - "Selected domain services by name-ending-with-Service minus two named exceptions (OwnershipVerifierService, UserService) rather than naming the four domain services explicitly, so any future service is automatically covered"

patterns-established:
  - "Both layering rules live in one @AnalyzeClasses class so the class import/graph is cached once, not per rule"

requirements-completed: [QUICK-260802-q6n]

coverage:
  - id: D1
    description: "ArchUnit JUnit5 dependency on the test runtime classpath, discoverable via plain ./gradlew test with no new Gradle task"
    requirement: "QUICK-260802-q6n"
    verification:
      - kind: integration
        ref: "Task 1 <verify>: ./gradlew dependencies --configuration testRuntimeClasspath | grep -c archunit-junit5-engine returned >=1; ./gradlew compileTestJava succeeded"
        status: pass
    human_judgment: false
  - id: D2
    description: "Pre-existing CODE_STYLE.md rule 2 violation (SubtaskService.findById(String) calling subtaskRepository.findById directly, no ownership check) removed; its one test caller repointed to the ownership-verified overload"
    requirement: "QUICK-260802-q6n"
    verification:
      - kind: unit
        ref: "TaskServiceTest#... (line ~373, repointed to findById(userId, subtask.getId()))"
        status: pass
    human_judgment: false
  - id: D3
    description: "LayeringArchTest: Rule 1 fails the build when a controller references com.vrudenko.kanban_board.repository; Rule 2 fails the build when a domain service (other than OwnershipVerifierService/UserService) calls repository.findById directly. Both rules teeth-proofed against deliberate temporary violations during development, then reverted before commit."
    requirement: "QUICK-260802-q6n"
    verification:
      - kind: integration
        ref: "build/test-results/test/TEST-com.vrudenko.kanban_board.architecture.LayeringArchTest.xml: tests=\"2\" failures=\"0\" errors=\"0\""
        status: pass
    human_judgment: false

duration: ~20min
completed: 2026-08-02
status: complete
---

# Quick Task 260802-q6n: Enforce Layering and Ownership Verification with ArchUnit Summary

**Added `com.tngtech.archunit:archunit-junit5:1.4.2` and a `LayeringArchTest` with two build-failing rules — controllers can't reach into repositories, and domain services can't bypass ownership-verified `findById(userId, id)` for a direct `repository.findById(id)` — and removed a genuine pre-existing ownership-verification violation the rule would otherwise have had to be weakened to accommodate.**

## Performance

- **Duration:** ~20 min
- **Tasks:** 3 completed
- **Files modified:** 4 (1 created, 3 modified)

## Accomplishments

- `build.gradle` now pins `com.tngtech.archunit:archunit-junit5:1.4.2` as a `testImplementation` dependency — the aggregator coordinate brings both the ArchUnit API and its JUnit Platform `TestEngine`, discoverable via the project's already-enabled `useJUnitPlatform()` with zero new Gradle wiring
- Found and removed a real, pre-existing CODE_STYLE.md rule 2 violation: `SubtaskService.findById(String id)` (lines 75-83) called `subtaskRepository.findById(id)` directly with no ownership verification at all. It had zero production callers — only one test assertion in `TaskServiceTest`. Repointed that assertion to the existing, correct ownership-verified overload `findById(String userId, String taskId)`, using the `userId` already in scope
- `LayeringArchTest` (new package `com.vrudenko.kanban_board.architecture`) declares two `@ArchTest` rules sharing one `@AnalyzeClasses` configuration:
  - **Rule 1:** no class annotated `@RestController` or residing in `..controller..` may access a `com.vrudenko.kanban_board.repository..` type
  - **Rule 2:** no class residing in `com.vrudenko.kanban_board.service..` with a simple name ending in `Service` — except `OwnershipVerifierService` and `UserService` — may call a `findById` method whose owner resides in `com.vrudenko.kanban_board.repository..`
- Both rules were teeth-proofed before being declared done: a temporary direct `boardRepository.findById()` call added inside `BoardService` made Rule 2 fail and name `BoardService`; a temporary `BoardRepository` field/call added to `BoardController` made Rule 1 fail and name `BoardController`. Both mutations were reverted via `git checkout --` before the final commit — neither was ever committed
- `docs/CODE_STYLE.md` rule 2 now points at `LayeringArchTest` as its mechanical enforcement, so a reader knows the rule is checked, not merely documented
- Full verification confirmed independently of this session's execution: `./gradlew spotlessCheck test` passed, and `build/test-results/test/TEST-com.vrudenko.kanban_board.architecture.LayeringArchTest.xml` shows `tests="2" failures="0" errors="0"`, proving the ArchUnit `TestEngine` was actually discovered and ran (not silently skipped)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add the ArchUnit JUnit5 test dependency** - `ad6b4a3` (chore)
2. **Task 2: Remove the pre-existing ownership-verification violation in SubtaskService** - `04f941d` (fix)
3. **Task 3: Add LayeringArchTest with the two documented rules** - `c3780d7` (feat)

_Note: plan metadata (SUMMARY.md, STATE.md) commit is handled separately by the orchestrator._

## Files Created/Modified

- `build.gradle` - Added `testImplementation 'com.tngtech.archunit:archunit-junit5:1.4.2'` grouped with other `testImplementation` entries; no new task/source set
- `src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java` - Deleted the unverified package-private `findById(String id)` overload (11 lines removed); ownership-verified `findById(String userId, String taskId)` overload untouched
- `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java` - Repointed the one caller (line ~373) from the deleted overload to `findById(userId, subtask.getId())`
- `src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java` - New: two `@ArchTest` rules (controller-to-repository isolation; service ownership-verified `findById`) under one `@AnalyzeClasses` configuration, each with a `.because(...)` explanation
- `docs/CODE_STYLE.md` - Added a note pointing rule 2 at `LayeringArchTest` as its mechanical enforcement

## Decisions Made

- ArchUnit rules as JUnit5 tests chosen over Checkstyle/PMD (no cross-file type resolution — rule 2 would degrade into a name-based regex any rename defeats) and over a custom ErrorProne/annotation-processor checker (much larger unit of work for two rules; would entangle with the separately-tracked ErrorProne adoption todo)
- The pre-existing `SubtaskService.findById(String)` violation was fixed, not exempted, so Rule 2 could be written at full strength instead of being weakened to accommodate a known hole — this is the concrete payoff of the task: a real ownership-verification bypass with zero production callers was removed from the codebase
- Rule 2 selects domain services by "name ends with `Service`, minus two named exceptions" rather than naming the four domain services explicitly, so a fifth service added later is automatically covered instead of silently unguarded
- Both rules scoped to the exact package `com.vrudenko.kanban_board.repository..` rather than the loose `..repository..` glob, to avoid false positives against `org.springframework.data.repository` and `SecurityContextRepository` types

## Deviations from Plan

None - plan executed exactly as written. All three tasks matched their `<action>` blocks; the flagged pre-existing violation was fixed exactly as documented in the plan's `<flagged_finding>` section.

## Issues Encountered

None. `./gradlew spotlessCheck test` passed, and `TEST-com.vrudenko.kanban_board.architecture.LayeringArchTest.xml` confirmed both ArchUnit test cases ran with zero failures — the ArchUnit `TestEngine` was genuinely discovered under plain `./gradlew test`, not silently skipped.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `.planning/todos/pending/2026-08-02-add-archunit-to-enforce-documented-layering-and-ownership-ve.md` is fully satisfied and moved to `.planning/todos/completed/`
- No blockers. Both rules are live and teeth-proofed; a future controller-to-repository or service-to-repository violation now fails the build instead of relying on review
- Caveat carried forward in the rule's `.because(...)` text (not enforced mechanically): Rule 2 catches direct `repository.findById` calls but does not catch re-derivation of a downstream repository call's id from an unverified raw path variable, nor other unverified loaders such as a hand-written `findByX` query — a green build is a floor, not a ceiling, on ownership enforcement

---
*Quick task: 260802-q6n*
*Completed: 2026-08-02*

## Self-Check: PASSED

- FOUND: src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java
- FOUND: build.gradle
- FOUND: src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java
- FOUND: docs/CODE_STYLE.md
- FOUND: ad6b4a3
- FOUND: 04f941d
- FOUND: c3780d7
