---
phase: 11-migrate-database-from-neon-to-self-hosted-postgres
plan: 04
subsystem: database
tags: [hikaricp, jdbc, postgresql, spring-boot, connection-pooling]

# Dependency graph
requires:
  - phase: 11-01
    provides: self-hosted postgres:16 container with max_connections=25, the ceiling this plan's pool sizing is checked against
  - phase: 11-02
    provides: the live VM cutover to the self-hosted engine that makes the Neon-era pool reasoning obsolete
provides:
  - Re-derived HikariCP pool (maximum-pool-size=5, minimum-idle=2, connection-timeout=10000,
    idle-timeout=600000, max-lifetime=1800000, keepalive-time=120000), each value justified
    against a same-host container instead of Neon's cold-start/autosuspend/pooler behavior
  - DB_JDBC_PARAMS query-string override removed entirely from spring.datasource.url
  - A dated decision record superseding the 2026-08-26 Neon-era record in place, plus live
    evidence that removing the prepared-statement threshold override is safe
affects: [deploy, docker-compose.prod.yml, any future phase touching the datasource configuration]

actuals:
  tokens: 4477
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Postgres query-log-based cross-session verification (log_statement=all + docker compose logs) in place of pg_prepared_statements, which is scoped to the querying session and cannot observe another session's prepared statements"

key-files:
  created: []
  modified:
    - src/main/resources/application.properties

key-decisions:
  - "minimum-idle raised 0 -> 2: a same-host container has no meter to spare and no wake-the-compute side effect to avoid, so the Neon-era floor of zero now only costs a TCP connect + SCRAM handshake on the first request after idle"
  - "connection-timeout lowered 30000 -> 10000: a same-host outage should surface as a fast error, not a 30s hang, while still leaving room for a cold VM boot"
  - "idle-timeout raised 300000 -> 600000, max-lifetime raised 600000 -> 1800000: both become HikariCP's own library defaults now that there is no idle-connection reaper to out-schedule"
  - "keepalive-time raised 0 -> 120000: the same ping that caused the 2026-08-26 Neon outage is now a purely useful dropped-backend probe, since there is no metered compute for it to hold awake"
  - "DB_JDBC_PARAMS query-string override dropped from spring.datasource.url entirely -- no TLS listener and no transaction-mode pooler exists in front of the self-hosted engine"
  - "prepareThreshold=0 removal smoke-tested rather than assumed: 1028 server-side named-statement executions observed, one statement reused 376 times on a single connection, workload survived a mid-session engine restart, zero prepared-statement exceptions"

patterns-established:
  - "When a plan's own verify script proposes reading pg_prepared_statements from a separate admin session to prove another session's server-side prepared-statement reuse, that check is unusable as written -- the view is session-scoped by Postgres design. Verify via the engine's own statement log instead."

requirements-completed: [D-09]

coverage:
  - id: D1
    description: "HikariCP pool and JDBC URL re-derived for a same-host PostgreSQL container: all six pool values re-justified, DB_JDBC_PARAMS query string removed, pool fits under max_connections=25 with headroom, both silently-wrong-if-deleted lines (minimum-idle, keepalive-time) stay explicit, prior decision record superseded in place"
    requirement: D-09
    verification:
      - kind: other
        ref: "11-04-PLAN.md Task 1 <verify> automated block (grep-based property/value assertions + `./gradlew spotlessCheck` + `./gradlew test`)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Prepared-statement threshold override removal proven safe against a real same-host PostgreSQL 16 instance: 25-iteration signup/board create-read-delete workload plus 10 more cycles after a mid-session engine restart, with server-side reuse positively confirmed and no prepared-statement exception"
    requirement: D-09
    verification:
      - kind: integration
        ref: "11-04-task2-smoke.sh (scratchpad), run against docker-compose.yml's local postgres+redpanda+bootRun stack -- output: 1028 named-statement executions, top single-statement reuse 376, TASK 2 PASS"
        status: pass
    human_judgment: false

duration: 30min
completed: 2026-08-26
status: complete
---

# Phase 11 Plan 04: HikariCP/JDBC Same-Host Retuning Summary

**Re-derived every HikariCP value and dropped the JDBC query-string override for a same-host Postgres engine, then proved the one unverified change (removing `prepareThreshold=0`) safe with a real 1028-execution prepared-statement reuse workload against a live instance, surviving a mid-session engine restart.**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-08-26T15:03:32Z
- **Completed:** 2026-08-26T15:31:25Z
- **Tasks:** 2
- **Files modified:** 1

## Accomplishments
- Re-derived all six HikariCP values (`maximum-pool-size`, `minimum-idle`, `connection-timeout`, `idle-timeout`, `max-lifetime`, `keepalive-time`) against a same-host PostgreSQL container instead of Neon's cold-start/autosuspend/pooler behavior, each with its own one-line same-host reason
- Dropped the `DB_JDBC_PARAMS` query-string override from `spring.datasource.url` entirely (no TLS listener, no transaction-mode pooler in front of the self-hosted engine any more)
- Superseded the 2026-08-26 Neon-era decision record in place with a new dated record explaining the same-host re-derivation, keeping the two silently-wrong-if-deleted lines (`minimum-idle`, `keepalive-time`) explicit
- Proved the prepared-statement threshold override's removal safe with a real 25-iteration signup/board create-read(nested-full)/delete workload plus 10 more cycles after restarting the Postgres engine mid-session — 1028 server-side named-statement executions observed, one statement reused 376 times on a single connection, zero prepared-statement exceptions

## Task Commits

Each task was committed atomically:

1. **Task 1: Re-derive the datasource URL and HikariCP block for a same-host engine** - `004dd50` (feat)
2. **Task 2: Smoke-test the prepared-statement change against a real Postgres** - `3c93d99` (docs — recorded the smoke test's observed evidence in the decision record Task 1 wrote; the smoke test itself ran against a throwaway local stack and produced no code change)

**Plan metadata:** (this commit)

## Files Created/Modified
- `src/main/resources/application.properties` - Datasource URL template (query string removed) and full HikariCP pool block re-derived for a same-host engine; decision record superseded in place with observed smoke-test evidence

## Decisions Made
- Each Hikari value re-derived individually against this repo's own topology (max_connections=25 shared by two app containers, no cold start, no autosuspend, no reaper) rather than copied from generic tuning advice (11-RESEARCH.md Assumption A1)
- The prior 2026-08-26 Neon decision record was kept in the file (superseded, not deleted) so a future reader can still see why the old values existed
- `pg_prepared_statements` was tried as the positive-confirmation mechanism (per the plan's literal verify script) and rejected once empirically confirmed useless from a separate admin session; replaced with Postgres's own cross-session-visible statement log

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The plan's own Task 2 verify script's `pg_prepared_statements` check cannot work as written**
- **Found during:** Task 2 (running the smoke test the first time)
- **Issue:** `pg_prepared_statements` is scoped to the querying session itself (Postgres session-local by design, like a temp table). Reading it via a fresh `docker exec psql` session — exactly as the plan's literal `<verify>` block does — can never see the app's own backend connections' prepared statements, regardless of whether reuse engaged. Confirmed empirically: identical workload, `pg_prepared_statements` via a fresh session read 0 every time.
- **Fix:** Enabled Postgres's own statement logging (`log_statement=all`, `log_min_duration_statement=0`, applied via `ALTER SYSTEM` + `pg_reload_conf()` on the throwaway local stack only — no committed config change) and checked `docker compose logs postgres` for repeated `execute S_<n>` lines instead. This is cross-session-visible and directly proves server-side reuse.
- **Files modified:** None (the fix is in the throwaway verification script, not the plan's target file). The decision record in `application.properties` documents the corrected method and the observed result.
- **Verification:** Re-ran the corrected script: 1028 total named-statement executions, statement `S_1` reused 376 times on one connection, workload survived a mid-session Postgres restart, zero `PSQLException` about a prepared statement already existing or not existing.
- **Committed in:** `3c93d99` (Task 2 commit, which records the corrected method and its result in the decision record)

**2. [Rule 1 - Bug] The plan's literal smoke-test script's request bodies fail this codebase's own validation**
- **Found during:** Task 2 (first run of the smoke test, before the fix above)
- **Issue:** The plan's own read_first section warned this could happen and instructed verifying DTO field constraints first. Two fields in the plan's literal script fail real validation: board names containing a hyphen (`"p11-board-1"`) violate `@BoardName`'s pattern `^[a-zA-Z0-9 ]*$` (no hyphens), and a numeric `displayName` (`"p11"`) violates `@DisplayName`'s pattern `^[a-zA-Z ]*$` (letters and spaces only, no digits) — a 400 from either would abort the script and look like a failure of the change under test rather than a script bug.
- **Fix:** Used space-separated board names (`"p11 board $i"`) and omitted the optional `displayName` field from the signup body entirely (valid — it's annotated `@OptionalNotBlank`, which treats an omitted/null field as valid).
- **Files modified:** None (script-only fix).
- **Verification:** Signup and all 35 board create/read/delete requests across both workload phases returned success (`curl -f` would have aborted the script otherwise).
- **Committed in:** N/A (script-only; no target-file change from this specific deviation)

---

**Total deviations:** 2 auto-fixed (2 Rule 1 — both bugs in the plan's own literal verify script, not in the target file's content)
**Impact on plan:** Neither deviation touched `application.properties` beyond what Task 1/Task 2 already specified — both were corrections to how the evidence was gathered, not to what was being verified. The actual prepared-statement-reuse claim is now backed by stronger, cross-session-visible evidence than the plan's original script would have produced (which would have failed to prove anything, reading 0 unconditionally).

## Issues Encountered
None beyond the two deviations above (both resolved inline, no open follow-up).

## User Setup Required
None - no external service configuration required. This plan is fully autonomous and only touched `src/main/resources/application.properties`; the smoke test ran against the local dev docker-compose stack (torn down cleanly at the end, no leftover container, volume, or background process).

## Next Phase Readiness
- `src/main/resources/application.properties`'s datasource/Hikari block is now fully re-derived for the self-hosted topology; no further Neon-era assumptions remain in this file
- Plans 11-05/11-06 (if any remaining datasource-adjacent work) can build on this without re-deriving pool sizing
- No blockers or concerns carried forward

---
*Phase: 11-migrate-database-from-neon-to-self-hosted-postgres*
*Completed: 2026-08-26*
