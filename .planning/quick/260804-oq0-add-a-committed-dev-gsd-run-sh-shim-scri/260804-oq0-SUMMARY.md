---
phase: quick-260804-oq0
plan: 01
subsystem: infra
tags: [dev-tooling, shell, gsd-core]

requires: []
provides:
  - "Sourceable .dev/gsd-run.sh resolver shim defining gsd_run"
  - "One-line CLAUDE.md documentation pointing future sessions at the shim"
affects: [dev-tooling, quick-task-workflow]

actuals:
  tokens: 5200
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns: ["sourced-vs-executed POSIX sh dual-mode failure handling (return||exit idiom)"]

key-files:
  created:
    - .dev/gsd-run.sh
  modified:
    - .claude/CLAUDE.md

key-decisions:
  - "Approach C (detect sourced vs. executed; return 1 when sourced, exit 1 when executed) chosen over verbatim exit 1 or always-return-1, to preserve the observable failure contract (same stderr message, non-zero status) while keeping the caller's shell alive when sourced"
  - "Documentation placed in the human-owned '## GSD Execution Directives' section strictly after the GSD:workflow-end marker, not inside the GSD-managed block, so it survives regeneration"

patterns-established:
  - "Project-local dev-tooling convenience scripts live under .dev/ (not bin/, which is gitignored, and not .githooks/, which is reserved for git hooks)"

requirements-completed: [QUICK-260804-OQ0]

coverage:
  - id: D1
    description: "Sourcing .dev/gsd-run.sh in a POSIX shell defines gsd_run and successfully invokes gsd-tools"
    requirement: "QUICK-260804-OQ0"
    verification:
      - kind: other
        ref: "bash -c '. ./.dev/gsd-run.sh && gsd_run query config-get workflow' | grep -q '\"security_enforcement\"'"
        status: pass
    human_judgment: false
  - id: D2
    description: "Total resolution failure while sourced reports the error and yields non-zero status without terminating the caller's shell"
    requirement: "QUICK-260804-OQ0"
    verification:
      - kind: other
        ref: "RUNTIME_DIR=/nonexistent HOME=/nonexistent . ./.dev/gsd-run.sh; rc=$? -> rc=1, shell survives"
        status: pass
      - kind: other
        ref: "stderr contains 'npx -y @opengsd/gsd-core@latest'"
        status: pass
    human_judgment: false
  - id: D3
    description: "All 20 resolution candidates transcribed in documented order, faithful to the inline resolver"
    requirement: "QUICK-260804-OQ0"
    verification:
      - kind: other
        ref: "grep -v '^#' .dev/gsd-run.sh | grep -c 'gsd-core/bin' -ge 20"
        status: pass
    human_judgment: false
  - id: D4
    description: "CLAUDE.md documents the shim outside the GSD-managed marker block"
    requirement: "QUICK-260804-OQ0"
    verification:
      - kind: other
        ref: "awk placement check after GSD:workflow-end + marker-count check"
        status: pass
    human_judgment: false

duration: 16min
completed: 2026-08-04
status: complete
---

# Quick Task 260804-oq0: Add a committed .dev/gsd-run.sh shim script Summary

**Extracted the ~15-line `gsd_run` runtime resolver GSD bash blocks re-paste into a committed, sourceable `.dev/gsd-run.sh` shim, and documented one-line usage in `.claude/CLAUDE.md` outside the GSD-managed region.**

## Performance

- **Duration:** ~16 min
- **Started:** 2026-08-04T15:39:15Z (approx, per STATE.md session marker)
- **Completed:** 2026-08-04T15:55:36Z
- **Tasks:** 2/2 completed
- **Files modified:** 2 (1 created, 1 modified)

## Accomplishments

- Created `.dev/gsd-run.sh`, a POSIX `sh` script that, when sourced, transcribes all 20 resolution candidates (plus the PATH-based branch) from the inline GSD resolver, in the exact documented order, defining `gsd_run` on success.
- Implemented dual-mode failure handling (Approach C from the plan's trade-off matrix): on total resolution failure, the script emits the same install-hint stderr message as the inline resolver, then returns status 1 without killing the caller's shell when sourced, or exits status 1 when run as its own process — verified both ways.
- Preserved the inline resolver's `CLAUDE_ENV_FILE` PATH-export side effect unchanged (append-only, guarded, failure-suppressed).
- Marked the file executable in the git index (100755), matching `.githooks/pre-commit`'s existing mode discipline.
- Added a single documentation bullet to `.claude/CLAUDE.md`'s human-owned `## GSD Execution Directives` section, strictly after the `GSD:workflow-end` marker, so it cannot be silently regenerated away.

## Task Commits

Each task was committed atomically:

1. **Task 1: Create the sourceable .dev/gsd-run.sh resolver shim, proven end-to-end** - `272ff9a` (feat)
2. **Task 2: Document the shim in CLAUDE.md, outside the GSD-managed block** - `b12d25e` (docs)

## Deviations from Plan

None - plan executed exactly as written. All four `<verify>` checks in Task 1 and both in Task 2 passed on first attempt; the pre-commit hook (`spotlessApply` + `fastTest`) ran successfully both times, well within the recommended generous timeout.

## Verification

Ran the plan's overall `<verification>` block after both tasks:

1. `bash -c '. ./.dev/gsd-run.sh && gsd_run query config-get workflow'` printed the workflow config JSON and exited 0.
2. The forced-miss source (`RUNTIME_DIR=/nonexistent HOME=/nonexistent`) printed the install-hint error to stderr, returned status 1, and left the calling shell alive.
3. `git status --short` after both commits shows only the pre-existing, out-of-scope `.planning/config.json` modification (present before this task started, not touched by it) — no Java file, no GSD workflow file, no `docs/` file appears.
4. No Java sources changed; `spotlessCheck`/`test` were not gated on this work but ran successfully via the pre-commit hook at both commits (`spotlessApply` + `fastTest`, ~1-3s each, well under the bounded timeout).

## Self-Check

- FOUND: `.dev/gsd-run.sh` (mode 100755 in index)
- FOUND: `.claude/CLAUDE.md` bullet after `GSD:workflow-end`
- FOUND commit `272ff9a` in `git log --oneline`
- FOUND commit `b12d25e` in `git log --oneline`

## Self-Check: PASSED
