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

TBD — three things to evaluate, not necessarily all:

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
3. **Evaluate which `E2ETest`-suffixed classes could drop to the cheaper in-process tier.** Since
   H2 was dropped in Phase 04.2, every test — `E2ETest`-suffixed or not — already hits real
   Testcontainers PostgreSQL; the `E2ETest` suffix no longer tracks "real vs. fake DB." What it
   actually tracks today is a narrower, more expensive layer: a real HTTP socket (REST Assured
   over `RANDOM_PORT`) and, for the activity-log classes, real Kafka + Schema Registry containers.
   As of Phase 6, 23 of 45 test files (51%) carry the suffix. Review those 23 and check whether
   any assert only a status code plus a DB row with no dependency on real-socket or real-Kafka
   behavior specifically — those could run at the same in-process `@SpringBootTest` tier as the
   other 22 non-E2E classes without losing real coverage. Keep the real-socket/real-Kafka tier for
   genuinely cross-cutting concerns (security/ownership boundary checks, Kafka
   publish→consume→dedupe, Avro schema evolution) where it is actually buying something a
   Postgres-real, in-process test can't. This does not reintroduce mocking — the project's
   no-mocks rule (`docs/CODE_STYLE.md`) and its real-DB-everywhere stance stay intact either way;
   this only asks which tests also pay for a real socket/Kafka on top of that.

Scope this as an evaluation first (which files, if any, actually benefit) rather than a
blanket reorganization — the test suite has grown to ~25+ classes across several phases
(per STATE.md phase history), so a mechanical mass-move without judgment risks more churn
than value.
