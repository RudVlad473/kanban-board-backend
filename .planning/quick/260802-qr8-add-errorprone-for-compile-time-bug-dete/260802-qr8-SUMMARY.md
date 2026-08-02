---
phase: quick/260802-qr8
plan: 01
subsystem: testing
tags: [errorprone, javac-plugin, compile-time-analysis, static-analysis, gradle]

# Dependency graph
requires: []
provides:
  - "net.ltgt.errorprone 5.1.0 + error_prone_core:2.50.0 wired into every JavaCompile task, hard-gating compileJava (ERROR findings fail the build) while compileTestJava stays warning-only"
  - "5 genuine main-source correctness/hygiene findings fixed in source: 2 dead @Autowired fields, 1 unused constant, 1 locale-default toLowerCase(), 1 undocumented ignored Future"
affects: [build.gradle, ci, docker-build-path]

actuals:
  tokens: 2306
  tasks: 3
  commits: 2

tech-stack:
  added: ["net.ltgt.errorprone:5.1.0 (Gradle plugin)", "com.google.errorprone:error_prone_core:2.50.0 (errorprone configuration)"]
  patterns:
    - "measure -> decide -> apply: land in explicitly-temporary allErrorsAsWarnings measurement mode first, checkpoint the operator on real measured counts against a written decision ladder, then apply the chosen gate strength as its own task"
    - "per-source-set severity split via tasks.named('compileTestJava') { options.errorprone.allErrorsAsWarnings = true } rather than a single global severity, so main and test code can be gated independently"
    - "teeth-proof discipline: a deliberate SelfAssignment mutation is reintroduced and must be observed producing the branch-appropriate outcome (build failure under a hard gate) before the gate is trusted, then reverted via git checkout -- before commit"

key-files:
  created: []
  modified:
    - build.gradle
    - src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java
    - src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
    - src/main/java/com/vrudenko/kanban_board/exception/AppAccessDeniedException.java
    - src/main/java/com/vrudenko/kanban_board/service/UserService.java

key-decisions:
  - "Gate strength: hard-gate-main-only (decision ladder rung 4), selected by the operator at the Task 2 checkpoint from measured counts: 5 main findings (clean after fixing) vs. 27 test findings dominated by FutureReturnValueIgnored noise on Testcontainers Kafka test sends - not worth a triage bill on a quick task"
  - "All 5 main findings fixed in source, none suppressed with @SuppressWarnings - each had an unambiguous correct fix"
  - "lombok.config hypothesis (from the plan's flagged Lombok/ErrorProne friction risk) evaluated and explicitly skipped: none of the 5 main or 27 test findings trace to Lombok-synthesized members, so the file was never created"
  - "KafkaEventPublisher's ignored chained Future (from .send().whenComplete()) fixed by assigning to `var unused`, ErrorProne's own idiomatic pattern for a deliberately-ignored Future whose exception path is already handled elsewhere in the chain, rather than suppressing the check"

patterns-established:
  - "ErrorProne comment block above the config in build.gradle records what the plugin is, why it runs inside javac (covers test/CI/Dockerfile bootJar with no separate task), why both coordinates are pinned exactly, why generated sources are excluded, and the measured finding counts behind the chosen gate strength - same documentation discipline as the git-hooks bootstrap block it sits near"

requirements-completed: [QUICK-260802-qr8]

coverage:
  - id: D1
    description: "ErrorProne wired into every JavaCompile task via net.ltgt.errorprone 5.1.0 + error_prone_core:2.50.0, both pinned exactly; disableWarningsInGeneratedCode and a cross-platform excludedPaths regex for build/generated permanent in both source sets"
    requirement: "QUICK-260802-qr8"
    verification:
      - kind: integration
        ref: "./gradlew dependencies --configuration errorprone --console=plain | grep error_prone_core -> com.google.errorprone:error_prone_core:2.50.0 (n)"
        status: pass
    human_judgment: false
  - id: D2
    description: "compileJava hard-gated (ERROR-severity findings fail the build); compileTestJava scoped to allErrorsAsWarnings only, per the operator's Task 2 checkpoint selection (hard-gate-main-only)"
    requirement: "QUICK-260802-qr8"
    verification:
      - kind: integration
        ref: "./gradlew clean compileJava compileTestJava --console=plain -> BUILD SUCCESSFUL, 0 main warnings, 27 test warnings (unchanged from measurement)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The 5 measured main-source findings (3 UnusedVariable, 1 StringCaseLocaleUsage, 1 FutureReturnValueIgnored) fixed in source: UserService's unused boardMapper/boardRepository fields removed, RandFlakeGenerator's unused TIMESTAMP_BITS constant removed (intent preserved as comment), AppAccessDeniedException uses toLowerCase(Locale.ROOT), KafkaEventPublisher's ignored chained Future assigned to `var unused`"
    requirement: "QUICK-260802-qr8"
    verification:
      - kind: integration
        ref: "./gradlew clean compileJava --console=plain: 0 warning: [...] lines, exit 0"
        status: pass
    human_judgment: false
  - id: D4
    description: "Gate teeth-proofed: a SelfAssignment mutation (id = id;) reintroduced into UserService.findById made ./gradlew clean compileJava FAIL naming [SelfAssignment] and the file, then reverted via git checkout -- with git status clean"
    requirement: "QUICK-260802-qr8"
    verification:
      - kind: integration
        ref: "teeth-proof.log: 'error: [SelfAssignment] Variable assigned to itself' at UserService.java:30, BUILD FAILED; git status clean after revert"
        status: pass
    human_judgment: false
  - id: D5
    description: "Full verification green: ./gradlew clean spotlessCheck test passes (4m27s, Kafka Testcontainers included) and ./gradlew clean bootJar succeeds under the new hard gate, confirming the Dockerfile's build path still compiles"
    requirement: "QUICK-260802-qr8"
    verification:
      - kind: integration
        ref: "final-verify-test.log: BUILD SUCCESSFUL in 4m 27s; bootjar.log: BUILD SUCCESSFUL in 22s"
        status: pass
    human_judgment: false

duration: ~35min
completed: 2026-08-02
status: complete
---

# Quick Task 260802-qr8: Add ErrorProne for Compile-Time Bug Detection Summary

**Wired `net.ltgt.errorprone` 5.1.0 + `error_prone_core:2.50.0` into every `JavaCompile` task with `compileJava` hard-gated and `compileTestJava` warning-only, chosen from a measured 5-main/27-test finding count against a written decision ladder, and fixed all 5 real main-source findings in source rather than suppressing them.**

## Performance

- **Duration:** ~35 min (across Task 1 measurement, Task 2 checkpoint, Task 3 apply-and-document)
- **Tasks:** 3 (Task 1 auto, Task 2 checkpoint:decision, Task 3 auto)
- **Files modified:** 5 (build.gradle + 4 main-source fixes)

## Accomplishments

- ErrorProne is active on every `JavaCompile` task with no new Gradle task and no CI edit — it covers `./gradlew test`, CI's `compileJava`/`compileTestJava`, and the Dockerfile's `./gradlew bootJar` alike, verified independently for each path in this session
- **Gate strength decided from measurement, not assumption:** Task 1 measured 5 main-source findings (3 `UnusedVariable`, 1 `StringCaseLocaleUsage`, 1 `FutureReturnValueIgnored`) and 27 test-source findings (18 `FutureReturnValueIgnored`, 4 `StringCaseLocaleUsage`, 3 `MissingOverride`, 1 `NotJavadoc`, 1 `DefaultCharset`). At the Task 2 checkpoint the operator selected **hard-gate-main-only** (ladder rung 4): main is clean of anything past triage and hard-gated; test findings are lopsided and dominated by low-value noise on Testcontainers Kafka test sends, so `compileTestJava` alone keeps `allErrorsAsWarnings = true`
- All 5 main findings fixed in source (no `@SuppressWarnings` needed — none required it):
  - `UserService`: removed two genuinely-unused `@Autowired` fields (`boardMapper`, `boardRepository`) and their now-unused imports — confirmed no test referenced them
  - `RandFlakeGenerator`: removed the unused `TIMESTAMP_BITS` constant, preserving its documentation intent as a plain comment since nothing in the class referenced it programmatically
  - `AppAccessDeniedException`: `entityName.toLowerCase()` → `entityName.toLowerCase(Locale.ROOT)`, avoiding locale-dependent casing
  - `KafkaEventPublisher`: the chained `Future` returned by `.whenComplete()` was silently discarded even though its exception path is already handled inside that same callback — assigned it to `var unused` (ErrorProne's own idiomatic pattern) with a comment explaining the fire-and-forget intent is deliberate, not accidental
- **Gate teeth-proofed twice** in this session: reproduced the `SelfAssignment` mutation from Task 1's positive control in `UserService.findById` and confirmed `./gradlew clean compileJava` failed, naming `[SelfAssignment] Variable assigned to itself` at the mutated line — proving the hard gate actually fires, not just that ErrorProne runs. Reverted via `git checkout --` and confirmed `git status` clean before any commit
- `lombok.config` hypothesis (flagged in the plan as a live risk given this repo's heavy Lombok use) evaluated and explicitly skipped: all 5 main and 27 test findings trace to hand-written code paths (manual `@Autowired` fields, explicit locale calls, explicit Kafka sends, test assertions) — none to Lombok-synthesized getters/setters/builders — so the file was never created, per the plan's "keep only if the count actually drops" instruction
- `build.gradle` carries a permanent documentation block above the ErrorProne config: what it is, why it runs inside `javac` rather than a separate task, why both coordinates are pinned exactly, why generated sources are excluded, and the measured finding counts behind the chosen gate strength
- Full verification green: `./gradlew clean spotlessCheck test` (4m 27s, Kafka Testcontainers included) and `./gradlew clean bootJar` (22s) both succeed under the new hard gate — confirming the Docker image build path is unaffected

## Task Commits

Each task was committed atomically:

1. **Task 1: Land ErrorProne in measurement mode, prove active, report finding counts** - `575210d` (feat)
2. **Task 2: Checkpoint decision (hard-gate-main-only)** - no commit (decision-only task)
3. **Task 3: Apply the decision, prove teeth, document it** - `46f4d80` (feat)

_Note: SUMMARY.md and todo-move commits below are handled in this same session per explicit operator instruction; plan metadata (STATE.md) is left for the operator to update separately._

## Files Created/Modified

- `build.gradle` - Removed the global `allErrorsAsWarnings = true`; `compileJava` now hard-gated by default ErrorProne severities, `compileTestJava` scoped to `allErrorsAsWarnings = true` via `tasks.named(...)`; added a permanent documentation comment block recording the measured counts and gate-strength rationale
- `src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java` - Assigned the ignored chained `Future` to `var unused` with an explanatory comment
- `src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java` - Removed unused `TIMESTAMP_BITS` constant, preserved intent as a comment
- `src/main/java/com/vrudenko/kanban_board/exception/AppAccessDeniedException.java` - `toLowerCase()` → `toLowerCase(Locale.ROOT)`
- `src/main/java/com/vrudenko/kanban_board/service/UserService.java` - Removed unused `boardMapper`/`boardRepository` fields and imports

## Decisions Made

- **hard-gate-main-only** selected at the Task 2 checkpoint (operator decision, not auto-selected): main sources hard-gated, test sources warning-only, per the plan's decision ladder rung 4 — findings lopsided between source sets, with test noise dominated by one low-value check (`FutureReturnValueIgnored` on Testcontainers Kafka sends) rather than real bugs
- All 5 main findings fixed in source rather than suppressed — none needed a `@SuppressWarnings` escape hatch
- `lombok.config` skipped entirely: the plan's flagged Lombok/ErrorProne friction risk (google/error-prone#5855, #5964, #4802, #4918) did not materialize in this measurement — no crash-outcome checks were triggered, and no finding traced to Lombok-synthesized code — so the hypothesis was tested by inspection and found not to apply, and no unexplained config artifact was left behind
- `KafkaEventPublisher`'s ignored-Future fix used ErrorProne's own recommended `var unused = ...` pattern rather than restructuring the call or suppressing the check, since the exception handling this check exists to protect is already present in the `whenComplete` callback

## Deviations from Plan

None — plan executed exactly as written for the hard-gate-main-only branch. One process note: the first `git checkout -- UserService.java` used to revert the teeth-proof mutation also reverted the file's earlier legitimate fix (both were uncommitted at that point), which was caught immediately by comparing the post-checkout diff against the intended fix and re-applied before proceeding. No incorrect state was ever compiled, tested, or committed.

## Issues Encountered

None. All verification (`compileJava`, `compileTestJava`, `spotlessCheck`, `test`, `bootJar`) passed on first attempt after fixes were applied; the teeth-proof produced the expected `SelfAssignment` build failure on the first mutation.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- `.planning/todos/pending/2026-08-02-add-errorprone-for-compile-time-bug-detection.md` is fully satisfied and moved to `.planning/todos/completed/`
- No blockers. `compileJava` is now a real correctness gate; a future `UnusedVariable`, `SelfAssignment`, `StringCaseLocaleUsage`, or any other ERROR-severity ErrorProne finding in main source will fail the build before tests run
- Carried-forward caveat (not a blocker, stated for a future reader): `compileTestJava` is warning-only, so the 27 measured test-source findings (dominated by `FutureReturnValueIgnored` on Testcontainers Kafka test sends) are not enforced. Re-tightening that scope requires a real triage pass, not assumed from this session's counts, if it is ever revisited
- `error_prone_core` and `net.ltgt.errorprone` are both pinned exactly (`2.50.0` / `5.1.0`); a future version bump is a deliberate reviewable change, not a floating dependency

---
*Quick task: 260802-qr8*
*Completed: 2026-08-02*

## Self-Check: PASSED

- FOUND: build.gradle
- FOUND: src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java
- FOUND: src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
- FOUND: src/main/java/com/vrudenko/kanban_board/exception/AppAccessDeniedException.java
- FOUND: src/main/java/com/vrudenko/kanban_board/service/UserService.java
- FOUND: .planning/todos/completed/2026-08-02-add-errorprone-for-compile-time-bug-detection.md
- FOUND: 575210d
- FOUND: 46f4d80
