---
created: 2026-08-03T15:55:00.346Z
title: Hard-gate compileTestJava Error Prone findings
area: tooling
severity: major
files:
  - build.gradle
---

## Problem

`build.gradle` currently sets `options.errorprone.allErrorsAsWarnings = true` on `compileTestJava`, so test-source Error Prone findings never fail the build — only `compileJava` (main sources) is hard-gated. The build.gradle comment documents a measured backlog from quick task 260802-qr8: 27 test-source findings (18 `FutureReturnValueIgnored`, overwhelmingly unchecked Testcontainers Kafka test sends, plus `StringCaseLocaleUsage`/`MissingOverride`/`NotJavadoc`/`DefaultCharset` noise). This is Tier 1 (high value) of a backlog of style/lint gaps identified while reviewing the repo's existing lint stack (Spotless+GoogleJavaFormat AOSP, Error Prone, ArchUnit's `LayeringArchTest`, `docs/CODE_STYLE.md`).

## Solution

Triage the 27 findings — fix or explicitly suppress each with a documented reason (matching the rigor already applied to the 5 main-source findings that were fixed rather than suppressed) — then remove the `allErrorsAsWarnings = true` override from `compileTestJava` so test sources are hard-gated the same way `compileJava` is.
