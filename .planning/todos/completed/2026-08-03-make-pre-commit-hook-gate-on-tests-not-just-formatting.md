---
created: 2026-08-03T15:55:00.346Z
title: Make pre-commit hook gate on tests, not just formatting
area: tooling
severity: major
files:
  - .githooks/pre-commit
---

## Problem

`.githooks/pre-commit` only ran `./gradlew spotlessApply` — it re-formatted and re-staged files but never ran `compileJava`, `compileTestJava`, or the test suite (including `LayeringArchTest`). This meant an Error Prone violation or an ownership-layering violation wasn't caught until CI, even though the tooling to catch it locally already existed. This was Tier 1 (high value) of a backlog of style/lint gaps identified while reviewing the repo's existing lint stack (Spotless+GoogleJavaFormat AOSP, Error Prone, ArchUnit's `LayeringArchTest`, `docs/CODE_STYLE.md`).

## Decision

The open trade-off (full `test` run too slow per-commit vs. compile-only missing ArchUnit coverage) was resolved as: run the full test suite excluding the Testcontainers-backed E2E classes, scoped to the pre-commit hook only. `build.gradle`'s default `test` task is untouched — CI and any direct `./gradlew test` invocation still run everything, E2E included. Gradle's `test` task has no CLI flag for exclusion patterns (only `--tests` for inclusion), so the filter had to be a build-script-defined task rather than a command-line option.

## Solution (implemented)

Added a `fastTest` task to `build.gradle` — a `Test` task copy of the default `test` task with `filter { excludeTestsMatching '*E2ETest' }` — and added a Part 4 to `.githooks/pre-commit` after the existing `spotlessApply`/re-stage steps that runs `./gradlew fastTest` and aborts the commit (`exit 1`) on failure. This runs ArchUnit's `LayeringArchTest` and all unit/service tests locally pre-commit, while deferring the slower Kafka/Testcontainers E2E suite to CI.
