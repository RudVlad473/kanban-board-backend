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

**Correction (filed at closure, quick task 260803-v23):** the Solution above is wrong on one point. Removing `allErrorsAsWarnings = true` alone is a measured no-op against the 27 findings — every one of them is WARNING severity by default, and the demotion flag only downgrades ERROR-severity checks, so today it downgrades nothing. Confirmed by forcing the flag off via a throwaway Gradle init script during planning: the build stayed green with all 27 warnings still present. Removing the demotion still buys something real (a *future* ERROR-severity finding in test code will no longer be silently downgraded), but by itself it does not "hard-gate" today's findings the way this todo's title claims. Closing this properly required an additional step this todo's Solution never named: promoting the five triaged checks (`FutureReturnValueIgnored`, `StringCaseLocaleUsage`, `MissingOverride`, `NotJavadoc`, `DefaultCharset`) to ERROR severity on `compileTestJava` via `options.errorprone.error(...)`, scoped to those five names so an `error_prone_core` version bump cannot red the build on its own.

## Resolution

Closed by quick task 260803-v23. All 27 findings were triaged to zero: `MissingOverride` (3), `StringCaseLocaleUsage` (4), `NotJavadoc` (1), and the `DefaultCharset` finding were fixed in source; 16 `FutureReturnValueIgnored` Kafka-send findings were fixed via a new ack-checked `sendAndAwaitAck` helper on `AbstractKafkaContainerTest`; 2 `FutureReturnValueIgnored` `executor.submit` findings in `ActivityLogIdempotencyE2ETest`'s concurrency race test were deliberately left dropped (blocking on them would serialize the race the test exists to prove) and documented with a written reason at the call site. `allErrorsAsWarnings = true` was removed from `compileTestJava`, and the five triaged checks were promoted to ERROR severity there — narrower and stricter than `compileJava`, which carries no such promotion and still has one unfixed warning-severity `EscapedEntity` finding at `UserMapper.java:23` (out of this task's test-source scope). See `.planning/quick/260803-v23-hard-gate-compiletestjava-error-prone-fi/260803-v23-SUMMARY.md` for full findings disposition and the teeth-check proving the promotion has real effect.
