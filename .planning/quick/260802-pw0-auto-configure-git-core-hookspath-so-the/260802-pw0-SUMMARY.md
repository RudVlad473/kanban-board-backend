---
phase: quick/260802-pw0
plan: 01
subsystem: infra
tags: [gradle, git-hooks, spotless, dev-tooling]

# Dependency graph
requires: []
provides:
  - "Self-arming core.hooksPath: any ./gradlew invocation in a clone with unset/wrong core.hooksPath sets it to .githooks, idempotently and silently once correct"
  - ".githooks/pre-commit recorded at index mode 100755 so the hook actually executes on Linux/macOS clones, not just Windows"
affects: [build.gradle, dev-onboarding]

actuals:
  tokens: 722
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Gradle configuration-phase git bootstrap via providers.exec (config-cache-compatible), guarded on rootProject.file('.git').exists(), write scoped --local with workingDir pinned to rootProject.projectDir"

key-files:
  created: []
  modified:
    - build.gradle
    - .githooks/pre-commit

key-decisions:
  - "Chose a configuration-phase build.gradle block (providers.exec) over a dedicated Gradle task or a third-party hooks plugin, because only the configuration-phase approach fires on every ./gradlew invocation (including help/tasks), which is the actual requirement"
  - "Probe reads the EFFECTIVE core.hooksPath (git config --get, not --local) and writes --local only when it differs from .githooks, so the mechanism repairs a wrong value and stays silent once correct"
  - "Used git update-index --chmod=+x rather than a plain filesystem chmod, since core.filemode is typically false on Windows and a filesystem-only chmod would not be reflected in the index"

patterns-established:
  - "Config-cache-compatible external process calls at Gradle configuration time should use providers.exec (not ProcessBuilder/project.exec), which Gradle 7.5+ instruments and flags otherwise"

requirements-completed: [QUICK-260802-pw0]

coverage:
  - id: D1
    description: "build.gradle configuration-phase block self-installs core.hooksPath=.githooks on any ./gradlew invocation when unset or wrong, idempotently and without touching global/system config"
    requirement: "QUICK-260802-pw0"
    verification:
      - kind: integration
        ref: "Task 1 <verify> automated script: unset -> ./gradlew help arms it; second run idempotent; wrong value (.git/hooks) repaired; no-.git scratch dir build succeeds without touching repo config; ./gradlew spotlessCheck passes; printed HOOKSPATH_BOOTSTRAP_OK"
        status: pass
    human_judgment: false
  - id: D2
    description: ".githooks/pre-commit is recorded in the git index at mode 100755 so it actually executes on POSIX (Linux/macOS) clones, with logic unchanged"
    requirement: "QUICK-260802-pw0"
    verification:
      - kind: integration
        ref: "Task 2 <verify> automated script: git ls-files -s mode check, comment presence, spotlessApply presence, shebang check, sh -n syntax check; printed HOOK_EXEC_BIT_OK"
        status: pass
    human_judgment: false

duration: 15min
completed: 2026-08-02
status: complete
---

# Quick Task 260802-pw0: Self-Installing Git Hooks Bootstrap Summary

**Added an idempotent configuration-phase block to `build.gradle` that self-arms `core.hooksPath=.githooks` on any `./gradlew` invocation, and fixed `.githooks/pre-commit`'s index mode from 100644 to 100755 so the hook actually runs on Linux/macOS clones.**

## Performance

- **Duration:** ~15 min
- **Tasks:** 2 completed
- **Files modified:** 2

## Accomplishments

- `build.gradle` now probes the effective `core.hooksPath` at configuration time via `providers.exec` and writes `git config --local core.hooksPath .githooks` only when the value is missing or wrong — armed on a fresh clone's very first `./gradlew` command, whatever that command is, with zero manual setup or README instruction
- The bootstrap is scoped and safe: guarded on `rootProject.file('.git').exists()` (skipped entirely for exported source trees / Docker layers with no `.git`), writes `--local` only (never `--global`/`--system`), pins `workingDir` to the root project directory (can't leak into an unrelated repo via `-p <dir>`), and swallows any `git` failure in a try/catch so the build can never fail because of hook wiring
- `.githooks/pre-commit` is now recorded in the git index at mode `100755` (was `100644`), which is the actual reason the hook has never fired on a POSIX machine even after the earlier manual `git config` step — plus a header comment documenting that installation is automatic and the mode is load-bearing
- End-to-end proof observed live during this plan's own execution: the Task 1 commit ran the newly-armed hook, which invoked `./gradlew spotlessApply` and re-staged files before the commit completed

## Task Commits

Each task was committed atomically:

1. **Task 1: Self-install core.hooksPath from build.gradle's configuration phase** - `e831483` (feat)
2. **Task 2: Make the pre-commit hook executable in the index so it runs on POSIX clones** - `ea64adc` (fix)

_Note: plan metadata (SUMMARY.md, STATE.md) commit is handled separately by the orchestrator._

## Files Created/Modified

- `build.gradle` - Appended a delimited "Git hooks bootstrap" section after the existing `tasks.named('test')` block; all prior blocks (plugins, java toolchain, spotless, test, repositories, dependencies) are byte-identical
- `.githooks/pre-commit` - Added a 3-line "Auto-installed" comment after the shebang; index mode changed 100644 → 100755 via `git update-index --chmod=+x`; hook logic (staged-file capture, `spotlessApply`, re-`add` loop) unchanged

## Decisions Made

- Configuration-phase `build.gradle` block (Approach A) chosen over a dedicated Gradle task (Approach B, only fires on tasks that actually run, missing `help`/`tasks`/first-invocation cases) and over a third-party hooks plugin (Approach C, adds a build-classpath dependency for ~20 lines of logic and would generate into `.git/hooks`, duplicating the already-committed `.githooks/` directory) — full trade-off matrix is in the plan's `<design_rationale>`
- Probe checks the *effective* `core.hooksPath` (`git config --get`, merged system/global/local) rather than only `--local`, so the mechanism reflects what git will actually resolve hooks from
- `git update-index --chmod=+x` used instead of a plain filesystem `chmod`, because `core.filemode` is typically `false` on Windows and a filesystem-only chmod is not reflected in the index — the verified fix needed to be POSIX-portable via the index itself, not the working-tree file mode

## Deviations from Plan

None - plan executed exactly as written. Both tasks matched their `<action>` blocks precisely; all `<verify>` automated scripts passed on the first attempt with no auto-fixes required.

## Issues Encountered

None. One informational note: `git update-index --chmod=+x` emitted a Windows CRLF-conversion warning on the working-tree file ("LF will be replaced by CRLF the next time Git touches it") — verified harmless by inspecting the staged blob directly (`git show :.githooks/pre-commit`), which confirmed LF-only line endings in what actually gets committed and cloned.

## User Setup Required

None - no external service configuration required. This is precisely the point of the change: no manual per-clone setup step exists anymore.

## Next Phase Readiness

- `.planning/todos/pending/2026-08-02-auto-configure-git-core-hookspath-so-the-pre-commit-hook-nee.md` is fully satisfied and can be closed
- No blockers. `./gradlew spotlessCheck` and `./gradlew test` both pass with the bootstrap block in place
- `git config --get --global core.hooksPath` confirmed empty — the developer's global config was never touched

---
*Quick task: 260802-pw0*
*Completed: 2026-08-02*

## Self-Check: PASSED

- FOUND: build.gradle
- FOUND: .githooks/pre-commit
- FOUND: .planning/quick/260802-pw0-auto-configure-git-core-hookspath-so-the/260802-pw0-SUMMARY.md
- FOUND: e831483
- FOUND: ea64adc
