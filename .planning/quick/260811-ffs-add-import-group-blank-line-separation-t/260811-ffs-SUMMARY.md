---
phase: quick-260811-ffs
plan: 01
subsystem: build-tooling
tags: [spotless, code-style, import-order, formatting]
dependency-graph:
  requires: []
  provides: [import-group-ordering]
  affects: [src/main/**/*.java, src/test/**/*.java]
tech-stack:
  added: []
  patterns:
    - "Spotless importOrder() with explicit 5-group spec instead of default ASCII sort"
key-files:
  created: []
  modified:
    - build.gradle
    - docs/CODE_STYLE.md
    - 131 files under src/main/**/*.java and src/test/**/*.java (import blocks only)
decisions:
  - "Locked group order: java, javax, com.vrudenko (first-party), third-party catch-all, static -- each blank-line separated (operator decision, not re-litigated)"
  - "Task 1+2 committed together (pre-commit hook's spotlessCheck gates both; a config-only intermediate commit would fail the hook by construction); Task 3's docs change committed separately to keep the 161-file reformat isolated and blame-ignorable"
metrics:
  duration: 45min
  completed: 2026-08-11
status: complete
actuals:
  tokens: 42000
  tasks: 3
  commits: 2
---

# Phase quick-260811-ffs Plan 01: Import Group Blank-Line Separation Summary

Reconfigured Spotless's `importOrder()` from an undifferentiated ASCII-sorted block to an explicit five-group order (java, javax, com.vrudenko first-party, third-party catch-all, static), reformatted all 131 affected Java files, and documented the convention as CODE_STYLE.md rule 10.

## What Was Built

- **`build.gradle`**: `spotless { java { importOrder(...) } }` now reads `importOrder('java', 'javax', 'com.vrudenko', '', '\\#')` — five groups, each blank-line separated in the generated output. A comment above the call records the group order, the Groovy `'\\#'` escaping rationale (doubled backslash produces the single literal `\#` token Spotless's `ImportSorterImpl` matches static imports on), and that the `javax` group is deliberate future-proofing (Spring Boot 3 uses `jakarta.*`, so it currently matches nothing).
- **131 Java files** under `src/main` and `src/test` reformatted via `spotlessApply` — import blocks only, no hand edits. (161 files were in scope per the plan's estimate; 30 had import blocks that already happened to conform to the new grouping, or had too few distinct groups to show a diff, so `spotlessApply` produced no change for those.)
- **`docs/CODE_STYLE.md`**: new `### 10.` rule documenting the five-group order, naming `build.gradle`'s `importOrder` call as the enforcing mechanism, explicitly addressing that this is the one rule in the file that IS mechanically enforced (unlike rules 1-9) while still recording the *why* behind first-party sitting third rather than last, and a real before/after example drawn from `TaskControllerTest.java`'s actual reformat diff.

## How Verification Actually Went

**Task 1 (tracer):** Added the `importOrder(...)` call to `build.gradle` before touching any source file. `./gradlew spotlessCheck` exited non-zero as expected — the reported failure mode was genuine per-file format violations (diffs showing import blocks being reordered into the new groups), not a Groovy script-compilation error or a complaint about the group spec. This confirmed the `'\\#'` escaping parsed correctly before the expensive full-tree rewrite.

**Task 2 (reformat + 4-layer verification):**
- `./gradlew spotlessApply` rewrote 131 files.
- **Layer 1 (idempotency):** `./gradlew spotlessCheck` PASSED on the very first run immediately after `spotlessApply` — the pipeline turned out to be idempotent on the first attempt. No Spotless step reordering (moving `removeUnusedImports()` above `importOrder()`) was needed; the theoretical non-idempotency risk documented in the plan's trade-offs section did not materialize in practice.
- **Layer 2 (structural correctness):** All three grep loops (static-imports-last across 23 files, java-imports-first, com.vrudenko-precedes-org) emitted zero FAIL lines.
- **Layer 3 (exemplar):** `TaskControllerTest.java`'s import block, printed and inspected, shows java → com.vrudenko → third-party → static, each separated by exactly one blank line — matching the locked order.
- **Layer 4 (nothing broke):** `./gradlew test` — **382 tests, 0 failures, 0 errors, 100% successful**. Test execution itself took 3m 29s (per Gradle's HTML report); the full `./gradlew test` invocation (including a fresh daemon start, `compileJava`/`compileTestJava` up-to-date checks) took 6m 44s wall-clock. This is well above the plan's "~210+ tests" baseline reference — the codebase has grown substantially (Schema Registry, Kafka activity log, session hardening, Phase 07.1 work) since that baseline was recorded.

**Task 3:** `docs/CODE_STYLE.md` rule 10 added and verified: `### 10.` heading present, `**Why:**` count is exactly 10 (one per rule, confirming the new rule's Why-line matches the file's established format rather than a custom heading that would have broken the count), and `./gradlew spotlessCheck` still passes (a markdown-only change is outside Spotless's `src/**/*.java` target, so this is a regression guard, not a new gate).

## Commits

Two atomic commits, per the plan's forced grouping (the pre-commit hook's `spotlessCheck` gates Task 1 and Task 2 together — a config-only intermediate commit would fail the hook by construction):

- **`9668ba7`** — `feat(quick-260811-ffs): group imports into 5 blank-line-separated blocks` — the mechanical reformat: `build.gradle` config change + 131 reformatted source files, 132 files changed. **This is the blame-ignorable commit** — a candidate for a future `.git-blame-ignore-revs` entry, since it touches every import block in the codebase but changes no logic.
- **`3f4784a`** — `docs(quick-260811-ffs): add CODE_STYLE.md rule 10 for import grouping` — the docs-only rule addition, kept separate specifically so the reformat commit above stays pure.

Both commits passed the `.githooks/pre-commit` hook (`spotlessCheck` + `fastTest`) on their own.

## Deviations from Plan

None — plan executed exactly as written, including the exact `'\\#'` escaping specified, the exact five-group order, and the two-commit split.

## Scope Discipline

Per the plan's explicit instruction, the pre-existing redundant static-import pair in `TaskControllerTest.java` (`MockMvcRequestBuilders.*` alongside `MockMvcRequestBuilders.put`) was left untouched — that redundancy predates this change and cleaning it up would have put non-formatting content into a commit whose entire value depends on being purely mechanical.

## Known Stubs

None.

## Self-Check: PASSED

- `build.gradle` contains the five-group `importOrder(...)` call — confirmed via `grep -n "importOrder" build.gradle`.
- `docs/CODE_STYLE.md` contains `### 10.` — confirmed.
- Commit `9668ba7` exists in `git log` — confirmed.
- Commit `3f4784a` exists in `git log` — confirmed.
- Working tree clean after both commits — confirmed via `git status --short`.
