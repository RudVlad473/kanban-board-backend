---
created: 2026-08-08T15:51:43.841Z
title: Restructure test folder — separate setup from tests, evaluate @Nested merges
area: testing
severity: minor
files:
  - src/test/java/com/vrudenko/kanban_board/
---

## Problem

The test folder (`src/test/java/com/vrudenko/kanban_board/`) has grown organically across
Phases 1-6 — E2E tests, service unit tests, and shared fixture/setup infrastructure
(`AbstractAppTest`, `AbstractAppE2ETest`, `AbstractKafkaContainerTest`, `AbstractPostgresContainerTest`,
`RecordingActivityEventListener`, fixture helper methods) currently live alongside the actual
test classes rather than in a clearly separated location. It's not clear today, at a glance,
which files are "infrastructure you extend/use" versus "tests you run," and some closely
related test classes may be split across multiple files where a single file with `@Nested`
inner classes could read more clearly and reduce file-count sprawl.

## Solution

TBD — two things to evaluate, not necessarily both:

1. **Separate setup/fixture classes from actual test classes.** Consider whether abstract
   base classes and shared fixtures (e.g. `AbstractAppTest`, `AbstractKafkaContainerTest`,
   `AbstractPostgresContainerTest`, `RecordingActivityEventListener`) belong in a distinct
   package (e.g. `support/` — some already partially live there) versus being interspersed
   with concrete `*Test`/`*E2ETest` classes at the top level.
2. **Evaluate merging some test files using `@Nested`.** Look for test classes that are
   closely related (e.g. multiple narrow test classes covering the same service/entity from
   different angles) and assess whether consolidating them into one file with `@Nested`
   inner classes would improve readability over the current one-class-per-file split, without
   sacrificing the ability to run/target individual test groups.

Scope this as an evaluation first (which files, if any, actually benefit) rather than a
blanket reorganization — the test suite has grown to ~25+ classes across several phases
(per STATE.md phase history), so a mechanical mass-move without judgment risks more churn
than value.
