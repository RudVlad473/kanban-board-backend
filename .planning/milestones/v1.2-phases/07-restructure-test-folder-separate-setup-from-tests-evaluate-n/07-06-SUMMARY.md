---
phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n
plan: 06
subsystem: testing
tags: [junit5, mockmvc, spring-boot-test, jackson, tier-downgrade]

# Dependency graph
requires:
  - phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n
    provides: "AbstractAppMockMvcTest + its two signinCookie() overloads (07-01), the shared MockMvc fixture base and real-signin cookie-relay idiom this plan builds directly on"
provides:
  - "ThemePersistenceE2ETest downgraded to the in-process MockMvc tier (D-03 verdict-table row 15), logout round trip and cookie-inequality assertion preserved"
  - "ActivityReadE2ETest downgraded to the in-process MockMvc tier (D-03 verdict-table row 16), direct-repository seeding preserved, still Kafka-free"
affects: [07-07]

# Actuals (#2632)
actuals:
  tokens: 9600
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Jackson JsonNode navigation (readBody/extractEventIds/extractCreatedAts private helpers) as the MockMvc-tier replacement for RestAssured's response.jsonPath() list/scalar extraction on a paginated Page<T> body"

key-files:
  created: []
  modified:
    - src/test/java/com/vrudenko/kanban_board/ThemePersistenceE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/e2e/activity/ActivityReadE2ETest.java

key-decisions:
  - "Used the real-signin cookie relay (signinCookie()/signinCookie(email,password)) throughout ThemePersistenceE2ETest, never .with(user()) -- the logout round trip and per-user isolation case both need genuinely distinct sessions to prove anything (RESEARCH.md Pitfall 2, applied here per the plan's explicit instruction even though Pitfall 2 was written about a different pair of classes)"
  - "Replaced RestAssured's response.jsonPath() extraction in ActivityReadE2ETest with three small private helpers (readBody, extractEventIds, extractCreatedAts) built on Jackson's ObjectMapper/JsonNode rather than pulling in com.jayway.jsonpath directly -- ObjectMapper was already an established dependency on the class via the other converted MockMvc tests, keeping the diff to one JSON library instead of two"

requirements-completed: [TEST-03]

coverage:
  - id: D1
    description: "ThemePersistenceE2ETest converted from RestAssured/RANDOM_PORT to AbstractAppMockMvcTest + MockMvc; logout round trip and cookie-inequality assertion intact; no injected pre-authenticated principal anywhere in the file"
    requirement: "TEST-03"
    verification:
      - kind: e2e
        ref: "./gradlew test --tests '*ThemePersistenceE2ETest' -- 9/9 tests pass (2 GetTheme + 7 UpdateTheme), unchanged from pre-conversion count"
        status: pass
    human_judgment: false
  - id: D2
    description: "ActivityReadE2ETest converted from RestAssured/RANDOM_PORT to AbstractAppMockMvcTest + MockMvc; direct ActivityLogRepository seeding preserved; no Kafka container ancestor or broker configuration introduced"
    requirement: "TEST-03"
    verification:
      - kind: e2e
        ref: "./gradlew test --tests '*ActivityReadE2ETest' --tests '*ActivityLogCleanupIsolationTest' -- 7/7 ActivityReadE2ETest tests pass (unchanged count) and 2/2 ActivityLogCleanupIsolationTest tests still pass"
        status: pass
    human_judgment: false

# Metrics
duration: 50min
completed: 2026-08-09
status: complete
---

# Phase 7 Plan 6: Theme and Activity Tier Downgrade Summary

**Downgraded ThemePersistenceE2ETest and ActivityReadE2ETest from the real-socket RestAssured/RANDOM_PORT tier to the in-process AbstractAppMockMvcTest/MockMvc tier, preserving every assertion one-for-one — including the theme class's logout-then-re-signin round trip and its cookie-inequality check, and the activity class's direct repository seeding with zero Kafka dependency.**

## Performance

- **Duration:** 50 min
- **Started:** 2026-08-09T14:29:00+02:00 (approx, first file reads)
- **Completed:** 2026-08-09T15:19:23+02:00
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- `ThemePersistenceE2ETest` (9 tests across `GetTheme`/`UpdateTheme`) converted to `AbstractAppMockMvcTest` + plain `@SpringBootTest` + `@AutoConfigureMockMvc`; every request now goes through a real `POST /signin` via `signinCookie()`/`signinCookie(email, password)`, never `.with(user())` — the logout round trip (write DARK, `POST /logout`, re-`signinCookie()`, assert the two cookie values differ, read back DARK under the fresh session) and the two-user isolation case both survive intact (TEST-03)
- `ActivityReadE2ETest` (7 tests in `FindAllByBoardIdTest`) converted the same way; direct `ActivityLogRepository` seeding is untouched, and the class still carries no Kafka container ancestor or broker configuration — its own Javadoc's "this suite needs no broker" claim remains true after the conversion
- RestAssured's `response.jsonPath().getList(...)`/`.getLong(...)`/`.getInt(...)` extraction on the `Page<ActivityLogResponseDTO>` response body was replaced with three small Jackson `JsonNode`-based helpers (`readBody`, `extractEventIds`, `extractCreatedAts`), preserving every pagination-boundary, newest-first-ordering, cross-user-scoping, empty-page, page-size-clamp, and caller-supplied-sort-ignored assertion at identical precision
- Zero production-code changes: `git status --porcelain src/main/` empty for both commits

## Task Commits

Each task was committed atomically:

1. **Task 1: Convert ThemePersistenceE2ETest to the in-process tier, preserving the logout round trip** - `795ce65` (refactor)
2. **Task 2: Convert ActivityReadE2ETest to the in-process tier** - `725f88b` (refactor)

**Plan metadata:** (pending — final docs commit follows this SUMMARY, owned by the orchestrator per this plan's parallel-execution contract)

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/ThemePersistenceE2ETest.java` - Superclass switched `AbstractAppE2ETest` → `AbstractAppMockMvcTest`; `@SpringBootTest(RANDOM_PORT)` → `@SpringBootTest` + `@AutoConfigureMockMvc`; every RestAssured `given()/when()/then()` call replaced with `mockMvc.perform(...)`; `Pair<String,String>` cookie handling replaced with `jakarta.servlet.http.Cookie` from `signinCookie()`
- `src/test/java/com/vrudenko/kanban_board/e2e/activity/ActivityReadE2ETest.java` - Same superclass/annotation conversion; added `readBody`/`extractEventIds`/`extractCreatedAts` private helpers to replace `response.jsonPath()`; query-param pagination (`page`, `size`, `sort`) rebuilt on `MockHttpServletRequestBuilder.queryParam(...)`

## Decisions Made

- Kept the real-signin cookie relay for every authenticated call in `ThemePersistenceE2ETest`, including the second, explicitly-password-created user — matching the plan's explicit instruction and RESEARCH.md's Pitfall 2 discipline, since the logout round trip and cross-user isolation case both need genuinely distinct sessions to prove anything real
- Built JSON extraction for `ActivityReadE2ETest` on Jackson `ObjectMapper`/`JsonNode` (already present on the class via the other converted MockMvc tests) rather than introducing `com.jayway.jsonpath.JsonPath` directly, even though that library is present transitively via `spring-boot-starter-test` — one JSON-handling dependency per file is simpler to read than two doing overlapping jobs

## Deviations from Plan

None — plan executed exactly as written. Both classes converted with unchanged `@Test` counts (9/9 and 7/7 respectively), the logout round trip and cookie-inequality assertion both intact, and the activity class remains Kafka-free.

## Issues Encountered

- The pre-commit hook's `fastTest` run (against the real Testcontainers PostgreSQL/Redpanda stack) took several attempts to land cleanly during Task 1's commit: the first attempt exceeded a 2-minute shell timeout mid-hook; the retry hit a stray-daemon file lock on `build/test-results/fastTest/binary/output.bin` (same failure mode `docs/SESSION_LESSONS.md` and the 07-01 SUMMARY both already document), resolved with `./gradlew --stop`; a subsequent attempt was itself killed mid-run by a `--stop` command issued from a sibling parallel-executor worktree's own hook recovery, surfacing as "Gradle build daemon has been stopped: stop command received" with the file left unstaged. Re-staged and retried once more; the hook then completed cleanly (fastTest green, `spotlessCheck` green) and the commit landed. No code change required — a tooling/parallel-execution race, not a defect in this plan's changes. Task 2's commit hit no such contention.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- D-03 verdict-table rows 15 and 16 both executed; `ThemePersistenceE2ETest` and `ActivityReadE2ETest` now run at the cheaper in-process tier alongside `ColumnLockingE2ETest` (07-01) and the sibling plans converting elsewhere in this wave
- Plan 07-07 (or any later `@Nested` merge work depending on these two classes having reached the MockMvc tier) can now treat both as in-process peers for merge-candidate evaluation
- No blockers surfaced; both classes are self-contained and this plan's diff does not touch any file another wave-2 plan (07-02..07-05) also modifies

---
*Phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n*
*Completed: 2026-08-09*

## Self-Check: PASSED

- Both claimed files (`ThemePersistenceE2ETest.java`, `e2e/activity/ActivityReadE2ETest.java`) confirmed present on disk
- Both task commit hashes (`795ce65`, `725f88b`) confirmed present in `git log`
