---
created: 2026-08-26T08:49:35.268Z
title: Add mutation testing with PITest
area: tooling
severity: minor
files:
---

## Problem

The project has no mutation testing in place. Line/branch coverage alone doesn't
verify that the test suite actually catches behavioral regressions — tests can hit
a line without asserting anything meaningful about it. PITest (pitest) is the
standard JVM mutation testing tool and would surface tests that pass without
really exercising the code's logic.

## Solution

TBD — likely: add the `info.solidsoft.pitest` Gradle plugin to `build.gradle`,
scope initial mutation targets (avoid running against the full codebase at first
given build time cost), pick a mutation-score threshold, and decide whether/how
to wire it into CI (likely a separate slower job rather than blocking every push).
