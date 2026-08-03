---
created: 2026-08-03T15:55:00.346Z
title: Make pre-commit hook gate on tests, not just formatting
area: tooling
severity: major
files:
  - .githooks/pre-commit
---

## Problem

`.githooks/pre-commit` only runs `./gradlew spotlessApply` — it re-formats and re-stages files but never runs `compileJava`, `compileTestJava`, or the test suite (including `LayeringArchTest`). This means an Error Prone violation or an ownership-layering violation isn't caught until CI, even though the tooling to catch it locally already exists. This is Tier 1 (high value) of a backlog of style/lint gaps identified while reviewing the repo's existing lint stack (Spotless+GoogleJavaFormat AOSP, Error Prone, ArchUnit's `LayeringArchTest`, `docs/CODE_STYLE.md`).

## Solution

Add `./gradlew test` (or at minimum `compileJava` plus the ArchUnit-covered test class) to the pre-commit hook after `spotlessApply`, so a commit fails locally on the same violations CI would catch. Weigh commit-time latency against the value of catching violations before push — a full `test` run may be too slow for every commit, in which case a narrower `compileJava`-only gate might be the better trade-off; this needs a decision, not just an implementation.
