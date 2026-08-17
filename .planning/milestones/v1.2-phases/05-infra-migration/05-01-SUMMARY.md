---
phase: 05-infra-migration
plan: 01
subsystem: infra
tags: [spring-boot-actuator, hikaricp, neon, postgres, flyway, docker-compose, healthcheck]

# Dependency graph
requires:
  - phase: 04.1-flyway-cutover
    provides: "Flyway migrations V1-V7 (src/main/resources/db/migration/), ddl-auto=validate"
  - phase: 04.2-testcontainers-postgres
    provides: "Postgres-only test suite, com.h2database:h2 fully removed"
provides:
  - "GET /api/actuator/health -- unauthenticated, allowlisted, DataSource-aware health signal for the production Docker healthcheck (INFRA-01)"
  - "Neon-pooled-endpoint-safe datasource config: DB_JDBC_PARAMS placeholder (prepareThreshold=0 local-safe default), explicitly sized HikariCP pool (INFRA-02)"
  - "Verification evidence that Flyway's checked-in V1-V7 migrations build the full application schema from a genuinely empty database and are idempotent (the actual mechanism now backing INFRA-06, corrected from this plan's original bridge-script design)"
affects: [05-02-production-compose-manifest, 05-04-manual-deploy-tracer, 05-05-cicd-automation]

# Actuals (#2632)
actuals:
  tokens: 5493
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: [spring-boot-starter-actuator]
  patterns:
    - "Actuator exposure allowlist (management.endpoints.web.exposure.include=health only, show-details=never) permitted unauthenticated in SecurityConfiguration via ApiPaths.ACTUATOR_HEALTH, matching the ApiPaths.SIGNIN/SIGNUP context-path-relative matcher convention"
    - "Env-var-overridable JDBC URL query-string placeholder (DB_JDBC_PARAMS) with a local-safe default, production values supplied entirely by the deploying compose file's environment rather than hardcoded"
    - "Explicitly-rationale-commented HikariCP pool sizing tied to a named upstream constraint (Neon cold-start/pooler behavior), not left at framework defaults"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/security/ActuatorHealthE2ETest.java
    - .planning/phases/05-infra-migration/scripts/verify-baseline-schema.sh
  modified:
    - build.gradle
    - src/main/resources/application.properties
    - src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java
    - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java

key-decisions:
  - "Task 3's original design (Hibernate-generated docs/plans/backend-modernization/01-baseline-schema.sql run alongside the legacy DDL bridge scripts) was NOT implemented -- verified live that Phase 04.1/04.2 already replaced that entire mechanism with Flyway migrations (V1-V7) and ddl-auto=validate, both landing after this plan's research/context but before its execution. Implementing the original design would have created a second, competing schema-creation path duplicating what Flyway's migration-history table already guarantees."
  - "DB_JDBC_PARAMS placeholder defaults to prepareThreshold=0 only (local-safe), not the full production TLS string -- keeps Task 2's diff inside application.properties alone and does not require touching docker-compose.yml, at the cost of two acceptance-criteria greps (sslmode=require, channel_binding=require) not being literally satisfiable from this file alone. Production values are supplied entirely by the deploying compose file's environment (05-02/05-03)."
  - "Added spring.datasource.hikari.idle-timeout=300000, not in the plan's original five Hikari properties -- Hikari itself warned and silently disabled idle-timeout when it was left at its 10-minute default equal to the newly-set 10-minute max-lifetime; observed live against docker compose up, fixed in the same task."

patterns-established:
  - "Framework-adjacent utility paths (Actuator health, matching the existing SWAGGER_UI precedent) get a named ApiPaths constant rather than an inline literal in SecurityConfiguration."
  - "A plan whose premise a later, already-shipped phase has invalidated gets its affected task's goal re-verified against the CURRENT mechanism and documented as a deviation, rather than either blindly implemented (would actively conflict with shipped architecture) or silently skipped (would leave INFRA-06 unverified)."

requirements-completed: []  # INFRA-01/02/06 are each covered by 05-01 PLUS later plans (05-02/05-03/05-04/05-05 per COVERAGE.md) -- none is fully satisfied by this plan alone, so none is marked complete here.

coverage:
  - id: D1
    description: "Unauthenticated GET /api/actuator/health returns 200 with aggregate status UP and no per-component detail; the Actuator env endpoint stays non-2xx"
    requirement: "INFRA-01"
    verification:
      - kind: e2e
        ref: "src/test/java/com/vrudenko/kanban_board/security/ActuatorHealthE2ETest.java (4 tests, all passing) + manual docker compose up curl (200 {\"status\":\"UP\"})"
        status: pass
    human_judgment: false
  - id: D2
    description: "Neon-pooled-endpoint-safe datasource: JDBC URL query-string placeholder + explicitly sized HikariCP pool, zero JPA/Hibernate code changes, local dev unbroken"
    requirement: "INFRA-02"
    verification:
      - kind: e2e
        ref: "./gradlew test (439/439 passing, 0 failures) + manual docker compose up --build (JDBC URL resolved to jdbc:postgresql://postgres:5432/<db>?prepareThreshold=0, HikariPool starts with zero warnings after idle-timeout fix)"
        status: pass
    human_judgment: false
  - id: D3
    description: "A genuinely empty PostgreSQL database, given only the checked-in Flyway migrations, reaches the full application schema, and re-applying is safe"
    requirement: "INFRA-06"
    verification:
      - kind: other
        ref: ".planning/phases/05-infra-migration/scripts/verify-baseline-schema.sh (run twice this session, both PASSED)"
        status: pass
    human_judgment: true
    rationale: "INFRA-06 as a whole (a pre-merge CI DDL-verification job against Neon's direct connection string) is only fully delivered across 05-01/05-04/05-05 per COVERAGE.md; this plan only establishes and proves the underlying schema-creation mechanism a human should confirm before 05-05 builds the CI job on top of it."

duration: 79min
completed: 2026-08-12
status: complete
---

# Phase 5 Plan 01: Actuator Health, Neon Datasource, and a Corrected Schema-Baseline Verification Summary

**Allowlisted Actuator health endpoint wired into Spring Security, a Neon-pooled-endpoint-safe HikariCP datasource config, and empirical proof (not a new artifact) that the already-shipped Flyway migrations satisfy INFRA-06's empty-database schema requirement.**

## Performance

- **Duration:** 79 min
- **Started:** 2026-08-12T13:45:00Z (approx., first context read)
- **Completed:** 2026-08-12T13:04:00Z+02:00 (last commit, fe00b20)
- **Tasks:** 3 completed
- **Files modified:** 6 (4 modified, 2 created)

## Accomplishments
- `GET /api/actuator/health` is publicly reachable, unauthenticated, returns `{"status":"UP"}` with no per-component detail, while `/api/actuator/env` stays non-2xx -- proven by both a new automated test class and a live `docker compose up` run.
- The production datasource resolves to a Neon-pooled-endpoint-safe JDBC URL (`prepareThreshold=0` baked in, TLS/channel-binding parameters supplied externally by the deploying compose file) with an explicitly sized, fully-rationale-commented HikariCP pool -- zero JPA/Hibernate code changed, local dev unaffected.
- Discovered and corrected a stale plan premise: Task 3's original design (a hand-generated baseline DDL script) was superseded by Phase 04.1/04.2's Flyway cutover before this plan executed. Produced a real, twice-run verification script proving the *actual* current mechanism (Flyway V1-V7 + `ddl-auto=validate`) already satisfies the underlying need, instead of building a second, conflicting schema-creation path.

## Task Commits

Each task was committed atomically:

1. **Task 1: Expose a real Actuator health endpoint and prove it is publicly reachable** - `7865aa0` (feat)
2. **Task 2: Configure the datasource for Neon's pooled endpoint and cold-start behavior** - `7b292d0` (feat)
3. **Task 3: Verify Flyway migrations satisfy INFRA-06's empty-database baseline (corrected scope)** - `fe00b20` (docs)

**Plan metadata:** SUMMARY commit follows this file.

## Files Created/Modified
- `build.gradle` - Added `spring-boot-starter-actuator` dependency
- `src/main/resources/application.properties` - Actuator exposure allowlist section; datasource `DB_JDBC_PARAMS` placeholder; new HikariCP pool section (6 properties)
- `src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java` - Added `ACTUATOR_HEALTH` constant
- `src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java` - Permitted the health path unauthenticated
- `src/test/java/com/vrudenko/kanban_board/security/ActuatorHealthE2ETest.java` - New, 4 tests (real-socket tier)
- `.planning/phases/05-infra-migration/scripts/verify-baseline-schema.sh` - New, corrected Task 3 verification script

## Decisions Made

1. **Task 3's scope was corrected, not implemented as written.** The plan's own `<action>` text was written from `RESEARCH.md`/`05-SOURCE-AUDIT.md` dated 2026-08-04, which explicitly assumed no migration framework existed ("Flyway / Liquibase / any migration framework | Explicitly out of scope"). Live inspection of this repo at execution time (2026-08-12) showed that assumption is false: Phase 04.1 ("Flyway-managed domain schema: V1-V4... `ddl-auto=validate` outside the test profile", done 2026-08-05) and Phase 04.2 (Testcontainers Postgres cutover, done 2026-08-06) both landed *after* this phase's research but *before* this plan's execution, and are recorded as completed, validated requirements in `PROJECT.md`. `src/main/resources/db/migration/` now holds `V1__init.sql` through `V7__*.sql`, and `application.properties` sets `spring.jpa.hibernate.ddl-auto=validate` unconditionally (verified by reading the file directly, not inferred) -- directly contradicting the plan's stated premise ("Production Hibernate ddl-auto remains unset") and one of its own acceptance criteria (`grep -c "ddl-auto"` expected `0`, actually `1`).
2. **Alternatives considered for Task 3, per CLAUDE.md's trade-off-matrix directive:**

   | Approach | Pros / Cons | Why picked / rejected |
   |---|---|---|
   | **Implement literally as written** (generate `01-baseline-schema.sql` via a new Hibernate schema-export Gradle task, run it + the 3 legacy bridge scripts) | + Matches the plan's checked-in artifact list exactly. − Creates a second, hand-generated schema-creation path that duplicates what Flyway's `flyway_schema_history` table already guarantees. − The new script and Flyway's `V1__init.sql` would immediately diverge the moment an entity changes, since only one of them would ever be regenerated. − Actively contradicts this codebase's own established convention (`application.properties`' "Division of labor" comment: "files under `db/migration/` are the only thing allowed to create or alter schema from here on"). | **Rejected.** Would ship a real, load-bearing correctness regression disguised as "following the plan." |
   | **Skip Task 3 silently** (do nothing, since Flyway already handles it) | + Zero extra work. − Leaves INFRA-06's empty-database claim unverified in this session -- the plan's own must-have ("Running the checked-in DDL scripts in filename order against a genuinely empty database produces the full application schema") would go unproven, and the plan's `<verify>` block (a named script that must exist and pass) would be left broken. | **Rejected.** Silently dropping a stated must-have without evidence is worse than a documented scope correction. |
   | **Re-verify the actual (Flyway) mechanism and document the correction** (what was done) | + Produces the same class of evidence the original Task 3 wanted (empty DB -> full schema, idempotent, app boots, health UP) against the mechanism that is actually authoritative today. + Does not introduce a competing schema-creation path. + Leaves a clear, reviewable trail for 05-04/05-05, which read from this plan and share the same stale assumption. − More investigation work than either extreme. | **Picked.** Satisfies the requirement's actual intent without creating a new correctness hazard. |

   **Reversibility:** low-cost/reversible -- no production artifact was created either way; if a future reviewer disagrees, the corrected verification script can be deleted and Task 3 re-attempted with no sunk cost beyond this session's investigation.
3. **`DB_JDBC_PARAMS`'s default is `prepareThreshold=0` only, not the full three-parameter production string.** The plan's own `<action>` text for Task 2 explicitly required both "a production-safe default" and "a placeholder whose default is local-safe" -- an internal contradiction, since the local `docker-compose.yml` Postgres has no TLS listener (confirmed: plain `postgres:16` image, no `ssl=on`), so a `sslmode=require` default would break `docker compose up` and the existing `rehearseHistoricalSchemas` Gradle task. The task's own diff-scope constraint ("this task's diff must stay inside application.properties") additionally rules out fixing this by adding an override to `docker-compose.yml`. Resolved in favor of the more specific, correctness-critical instructions (local-safe default, single-file diff) over the two acceptance-criteria greps for `sslmode=require`/`channel_binding=require`, which cannot be satisfied without violating one of those two explicit constraints. `prepareThreshold=0` alone is safe everywhere (client-side only, no server requirement) and is hardcoded as the default; production's full `sslmode=require&channel_binding=require&prepareThreshold=0` string is supplied via `DB_JDBC_PARAMS` in the deploying compose file's environment (05-02/05-03).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] HikariCP idle-timeout warning fixed with an added property**
- **Found during:** Task 2, live verification via `docker compose up`
- **Issue:** `com.zaxxer.hikari.HikariConfig` logged `HikariPool-1 - idleTimeout is close to or more than maxLifetime, disabling it` -- Hikari's own 10-minute default `idle-timeout` was no longer strictly less than the newly-set 10-minute `max-lifetime`, so idle-timeout was silently inert.
- **Fix:** Added `spring.datasource.hikari.idle-timeout=300000` (5 min), comfortably below `max-lifetime`, with a written rationale explaining why it was added beyond the plan's original five properties.
- **Files modified:** `src/main/resources/application.properties`
- **Verification:** Re-ran `docker compose up --build` after the fix -- warning gone, `HikariPool-1 - Start completed` with no preceding WARN line.
- **Committed in:** `7b292d0` (Task 2 commit)

### Architectural Correction (Rule 4-class, resolved via investigation rather than a blocking checkpoint)

**2. [Task 3] Baseline schema artifact not created -- Flyway migrations (already shipped) are the actual mechanism**
- **Found during:** Task 3, before any file was written -- reading `application.properties`, `application-test.properties`, and `src/main/resources/db/migration/` directly, per this task's own `<read_first>` instruction to read the application-properties naming-strategy block.
- **Issue:** see "Decisions Made" #1/#2 above for the full analysis and alternatives matrix.
- **Fix:** No `docs/plans/backend-modernization/01-baseline-schema.sql` was created; no Hibernate schema-export Gradle task was added. Instead, `.planning/phases/05-infra-migration/scripts/verify-baseline-schema.sh` was written and run twice, proving Flyway's `V1__init.sql`..`V7__*.sql` build the full schema (`users`, `boards`, `columns`, `tasks`, `subtasks`, `activity_log`) from a genuinely empty Postgres, that `tasks.version` and `users.password_hash NOT NULL` land correctly (the two specific shape facts the original acceptance criteria named), that the app reaches `GET /api/actuator/health` 200 UP, and that a second boot against the already-migrated database is safe (no `flyway_schema_history` table recreation, still boots clean).
- **Files affected:** `.planning/phases/05-infra-migration/scripts/verify-baseline-schema.sh` (new). No `src/main` or `src/test` files touched by this task.
- **Verification:** Script run twice this session (once before, once after a fix for a false-positive timestamp-based idempotency check -- `docker logs --since` proved unreliable against this Docker Desktop host's clock and was replaced with a line-count diff). Both final runs PASSED.
- **Committed in:** `fe00b20` (Task 3 commit)
- **Downstream implication (not fixed here, out of this plan's scope):** `05-04-PLAN.md` (Task 1, `read_first`) and `05-05-PLAN.md` both reference the same stale "no Flyway, run the three legacy bridge scripts + `01-baseline-schema.sql`" mechanism this plan corrected. `05-04`'s manual-deploy runbook step ("apply the DDL scripts... using the direct (non-pooled) connection string... before the app is started, because production leaves Hibernate ddl-auto unset") is now unnecessary and stale -- production's `ddl-auto=validate` plus Flyway means the app creates its own schema on first boot automatically, with no separate human pre-step required. `05-05`'s planned `ddl-verify` CI job similarly assumed `psql -f <script>.sql` against the legacy bridge scripts; it should instead verify Flyway's migrations apply cleanly (e.g. `flyway migrate` or booting the app) against Neon's direct connection string. Neither plan was edited by this executor -- this is a heads-up for whichever agent executes those plans next, not a claim that they were fixed.

---

**Total deviations:** 2 (1 Rule 3 auto-fix, 1 architectural correction resolved via investigation and documented rather than escalated as a blocking checkpoint, per the plan's `autonomous: true` frontmatter and this session's `mode: yolo` config)
**Impact on plan:** Task 1 and Task 2 executed materially as planned (with the one Rule 3 fix). Task 3's deliverable changed in kind (verification of an existing mechanism, not a new artifact) but not in intent -- INFRA-06's underlying empty-database schema requirement is proven satisfied by evidence, not by inspection or assumption.

## Issues Encountered

- **`docker logs --since <timestamp>` returned pre-restart log lines despite the timestamp being strictly after them.** Observed live while building the Task 3 verification script's idempotency check, on this session's Docker Desktop (Windows) host. Root-caused as likely host/container clock handling rather than a script bug (both the restart marker and the container log timestamps used UTC, and the ordering was unambiguous), and worked around by switching to a line-count diff (`docker logs | wc -l` before, `tail -n +N` after) instead of depending on `--since` at all. Documented inline in the script.
- **Gradle daemon killed mid-run by a too-short Bash tool timeout** on the first `git commit` attempt (the pre-commit hook's `fastTest` run exceeded the default 2-minute tool timeout and the harness's termination signal reached the Gradle daemon). Not a real test failure -- re-ran with a longer timeout and the commit succeeded. No code or config change was needed; noted here only because the transient `EventIdGeneratorTest` failure it produced could otherwise be mistaken for a real regression by a future reader of the raw tool output.

## User Setup Required

None - no external service configuration required. (Neon/Oracle provisioning is scoped to later plans in this phase, per `05-CONTEXT.md`'s D-02/D-03.)

## Next Phase Readiness

- **Ready:** The health endpoint, datasource config, and schema-creation mechanism this plan's sibling plans (05-02 production compose, 05-03 VM provisioning, 05-04 manual deploy tracer) depend on are all in place and independently verified against a live `docker compose up` run, not just unit tests.
- **Blocker/concern for 05-04 and 05-05:** both plans' `<read_first>`/`<action>` text reference the pre-Flyway bridge-script mechanism this plan's Task 3 found superseded. Whoever plans or executes those next should re-read this SUMMARY's "Downstream implication" note before trusting their own `read_first` lists at face value -- the same 2026-08-04-vs-2026-08-12 staleness gap that affected this plan's Task 3 affects their DDL-related tasks too.
- **Not a blocker for this plan's own success criteria:** all three of 05-01-PLAN.md's `<success_criteria>` items are met -- INFRA-01's health signal is proven reachable by an automated test; INFRA-02's datasource carries TLS/channel-binding/prepared-statement mitigation (via the externally-supplied production override) with an explicitly sized pool and zero JPA/Hibernate changes; INFRA-06's DDL-to-empty-database gap is proven closed, just by Flyway rather than by a new baseline script.

## Self-Check: PASSED

- FOUND: `src/test/java/com/vrudenko/kanban_board/security/ActuatorHealthE2ETest.java`
- FOUND: `.planning/phases/05-infra-migration/scripts/verify-baseline-schema.sh`
- FOUND commit `7865aa0` in `git log --oneline`
- FOUND commit `7b292d0` in `git log --oneline`
- FOUND commit `fe00b20` in `git log --oneline`

---
*Phase: 05-infra-migration*
*Completed: 2026-08-12*
