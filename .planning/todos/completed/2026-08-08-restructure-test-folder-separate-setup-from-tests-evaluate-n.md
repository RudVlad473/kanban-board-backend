---
created: 2026-08-08T15:51:43.841Z
resolved: 2026-08-09
resolves_phase: 7
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

## Resolution

Closed by **Phase 7 (all 7 plans, 07-01 through 07-07), completed 2026-08-09**. All three
evaluation items were executed, not merely evaluated (D-04):

1. **Fixture separation** landed as a three-way split — `support/containers/` (pure Testcontainers
   lifecycle: `AbstractPostgresContainerTest`, `AbstractKafkaContainerTest`), `support/fixtures/`
   (app-domain fixture data + HTTP helpers: `AbstractAppTest`, `AbstractAppE2ETest`, and a new
   `AbstractAppMockMvcTest` added specifically to unblock item 3), `support/listeners/`
   (`RecordingActivityEventListener`) — not the flatter single-folder shape this todo's own
   Solution sketched, per D-01's "one location, categorized within it" instruction (07-01).
2. **One `@Nested` merge executed**: the three real-HTTP authentication/session classes
   (`AuthenticationControllerTest`, `SessionPersistenceE2ETest`, `UserPersistenceE2ETest`) merged
   into `security/AuthenticationE2ETest.java`, downgraded to the in-process tier in the same edit,
   and proven by falsification that the concurrent-session-ceiling and session-fixation controls
   still exercise the real authentication call site (07-02). The four conditional per-resource
   merge candidates (Column/Task/Subtask/Board controller-test consolidation) were evaluated and
   **deliberately deferred in full** — each would combine 4 source files into one 600-900-line
   file, judged not to clearly clear D-02's "clear readability win" bar (RESEARCH.md Candidates
   2-4, Pitfall 3).
3. **Tier downgrade**: of 22 concrete `E2ETest`-suffixed classes found on disk at research time,
   13 carried a DOWNGRADE verdict and 9 a KEEP verdict. Of the 13 DOWNGRADE verdicts: 10 became
   standalone in-process conversions across plans 07-01, 07-03, 07-04, 07-05, 07-06; 2
   (`SessionPersistenceE2ETest`, `UserPersistenceE2ETest`) were downgraded directly into the merged
   `security/AuthenticationE2ETest.java` in 07-02 (item 2 above), alongside the pre-existing,
   non-`E2ETest`-suffixed `AuthenticationControllerTest`; and 1 (`e2e/board/BoardE2ETest.java`) was
   deleted outright as trivial rather than converted — see item 4 below. The concrete file count at
   the in-process tier today is therefore 11, not 13: the 2 merged conversions collapsed into the
   1 new `AuthenticationE2ETest.java` file. The 9 KEEP verdicts are 8 Kafka/Schema-Registry-dependent
   classes under `activitylog/`, plus `BoardCreationE2ETest` for its genuinely concurrent
   `ConcurrentCreate` multi-threaded race — no documented MockMvc equivalent for that guarantee.
4. **Two empty test classes not mentioned by this todo** (`e2e/board/BoardE2ETest.java`,
   `service/SubtaskServiceTest.java`, both 0 `@Test` methods) were deleted as a byproduct, found
   during the fixture-relocation grep sweep (TEST-04, 07-01).

**This todo's own premise was inexact, corrected here:** it stated "23 of 45 test files (51%)"
carry the `E2ETest` suffix as of Phase 6. A direct file enumeration at Phase 7 research time
(2026-08-09) found **22 concrete `E2ETest`-suffixed classes and 48 test files** — 7 new test files
landed during Phase 6 after this todo was filed (2026-08-08), which explains most of the file-count
drift; the suffix-count discrepancy (23 vs. 22) was never fully resolved and is noted as an open
question in `07-RESEARCH.md`, but had no bearing on execution since the research delivered a
per-class verdict table grounded in a direct read of every file, not the filename count.

**Verified, not assumed, that nothing shrank:** the full suite was 278 tests before and after this
phase (unchanged — confirmed via 06-07-SUMMARY.md's pre-phase figure and 07-07's own post-phase
run), 0 failures either time; 277 `@Test`-annotated source methods pre-phase and post-phase
(exact match via `git diff` of the two commit trees), consistent with "merges preserve every test
method, deleted classes held none."

**Follow-up filed, not left implicit**: the `E2ETest` suffix vs. `fastTest`'s class-name-pattern
filter coupling this phase surfaced (12 downgraded classes keep the suffix and therefore stay
excluded from the pre-commit gate despite no longer needing a socket) is tracked as
`.planning/todos/pending/2026-08-09-decide-e2etest-suffix-vs-fasttest-filter-coupling.md`, which
also folds in a small stale-comment finding in `UserAuthenticationProvider.java` that this phase
deliberately left unfixed (zero `src/main/java` changes, per every plan's locked scope boundary).

See `.planning/phases/07-restructure-test-folder-separate-setup-from-tests-evaluate-n/` (07-01
through 07-07 `SUMMARY.md` files, `07-RESEARCH.md`) for full evidence.
