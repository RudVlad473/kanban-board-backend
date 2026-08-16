---
phase: quick-260816-sv1
plan: 01
subsystem: infra
tags: [github-actions, ci-cd, deploy.yml, deprecation, checkout, setup-java, temurin]

requires: []
provides:
  - "deploy.yml's run-tests, build-and-push-docker-image, and flyway-verify jobs on actions/checkout@v5 (was @v3)"
  - "deploy.yml's run-tests job on actions/setup-java@v5 with distribution 'temurin' (was @v4 / 'adopt')"
  - "Verified-live precedent: a real master push + gh run watch/rerun cycle used to prove a CI-hygiene fix rather than local inspection"
affects: [infra, ci-cd]

actuals:
  tokens: 3232
  tasks: 3
  commits: 2

tech-stack:
  added: []
  patterns:
    - "CI Action version bumps verified via a real production push + live log diff, since deploy.yml has no branch/PR/workflow_dispatch trigger to test against safely"

key-files:
  created:
    - .planning/todos/pending/2026-08-16-digest-pin-github-actions-mutable-tags-are-currently-trusted-by-tag-only.md
    - .planning/todos/pending/2026-08-16-security-scan-yml-stale-comment-and-stale-actions-after-260816-sv1.md
    - .planning/todos/pending/2026-08-16-add-gradle-cache-to-deploy-yml-run-tests-job.md
  modified:
    - .github/workflows/deploy.yml
    - .planning/todos/pending/2026-08-01-bump-java-version-from-21-to-25-current-lts.md

key-decisions:
  - "Task 2 checkpoint resolved 'all-recommended' (A1+B1+C1): actions/checkout bumped to @v5 (not the brief's literal @v4, which would still run on the now-also-deprecated Node 20 and fail to clear the warning), applied to all three @v3 occurrences in deploy.yml (widened beyond run-tests alone), security-scan.yml left untouched with a todo filed for its now-stale comment."
  - "Test-count equivalence assessed via 'BUILD SUCCESSFUL + no FAILED line + identical actionable-task count' rather than a literal per-test tally -- Gradle's default (non --info) console reporter prints no per-test count in either the before or after CI log, a genuine gap between the plan's stated evidence and what the tool actually emits."
  - "A mid-verification build-and-push-docker-image failure (java.net.SocketException: Connection reset fetching gradle-8.11.1-bin.zip inside the Dockerfile's own RUN ./gradlew bootJar step) was diagnosed as a transient network blip unrelated to this task's diff (Dockerfile, docker/build-push-action, docker/setup-buildx-action all untouched) and resolved via gh run rerun --failed rather than a code change."

requirements-completed: [TODO-260802-rq5-UNIT-B-CI]

coverage:
  - id: D1
    description: "run-tests job's live CI log carries zero GitHub-Actions-emitted deprecation warnings (Node-20 runtime for checkout/setup-java, setup-java v4 deprecation, 'adopt' distribution alias) -- reduced from 15 in the baseline to 0"
    requirement: "TODO-260802-rq5-UNIT-B-CI"
    verification:
      - kind: other
        ref: "gh run view 31966148764 --log --job 95212493796 | grep -i 'run-tests' | grep -iE 'node 20|checkout@v3|setup-java v4|adopt|punycode' (0 matches)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Full downstream pipeline (build-and-push-docker-image -> flyway-verify -> deploy-to-netcup) still succeeds after the bump, proving run-tests still gates it correctly"
    requirement: "TODO-260802-rq5-UNIT-B-CI"
    verification:
      - kind: other
        ref: "gh run view 31966148764 --json conclusion,status (conclusion=success, status=completed, after one gh run rerun --failed for an unrelated transient network blip)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Java stays on 21 throughout; build.gradle and Dockerfile byte-identical to pre-task state"
    verification:
      - kind: other
        ref: "git diff --stat db30752..HEAD -- build.gradle Dockerfile (empty output)"
        status: pass
    human_judgment: false
  - id: D4
    description: "run-tests job's test outcome is genuinely unchanged (same toolchain, same result), not merely still-green"
    verification:
      - kind: other
        ref: "Baseline (31964944867) vs after (31966148764) Run tests step: both 'BUILD SUCCESSFUL' + '7 actionable tasks: 7 executed', no FAILED line in either"
        status: pass
    human_judgment: false
  - id: D5
    description: "Source todo stays in pending/ with Unit B annotated CI-half-complete only; Dockerfile half and Units A/C remain open"
    verification:
      - kind: other
        ref: ".planning/todos/pending/2026-08-01-bump-java-version-from-21-to-25-current-lts.md (file still in pending/, 2026-08-16 update section appended, Dockerfile/Units A/C explicitly called out as still open)"
        status: pass
    human_judgment: false

duration: 45min
completed: 2026-08-16
status: complete
---

# Quick Task 260816-sv1: Fix GitHub Actions Deprecation Warnings Summary

**Closed the CI half of todo 260802-rq5 Unit B: `deploy.yml`'s `run-tests` job moved off the dead `adopt` JDK alias and deprecated `setup-java@v4`/`checkout@v3`, verified clean (0 deprecation lines, was 15) on a real green master run that also confirmed the full downstream deploy pipeline still succeeds.**

## Performance

- **Duration:** ~45 min
- **Started:** 2026-08-16T18:50:00Z (approx, first tool call)
- **Completed:** 2026-08-16T19:15:00Z
- **Tasks:** 3 (Task 1 read-only evidence capture, Task 2 blocking decision checkpoint, Task 3 edit + push + live verify + annotate)
- **Files modified:** 2 (`deploy.yml`, the source todo) + 3 new todo files filed

## Accomplishments

- `run-tests`, `build-and-push-docker-image`, and `flyway-verify` all bumped from `actions/checkout@v3` to `@v5` (not the brief's literal `@v4`, which was verified live to still carry a Node-20 deprecation warning and would not have met the stated acceptance criterion)
- `run-tests`'s `Set up Java` step moved from `actions/setup-java@v4` / `distribution: 'adopt'` to `@v5` / `'temurin'`, `java-version` unchanged at `'21'`
- Verified live against two real master CI runs: baseline `31964944867` (20 total `deprecat`-matching lines, 15 of them GitHub-Actions-version-caused) versus after-fix `31966148764` (5 lines remaining, all pre-existing/unrelated `javac -Xlint:deprecation` notes about `AvroSchemaRegistrar.java`'s own use of a deprecated Java API — byte-identical to the same 5 lines already present in the baseline)
- Full downstream pipeline (`build-and-push-docker-image` → `flyway-verify` → `deploy-to-netcup`) confirmed `success` end to end on the verifying run
- Diagnosed and recovered from a genuine mid-run transient network failure (`java.net.SocketException: Connection reset` fetching the Gradle distribution inside the Dockerfile's own `RUN ./gradlew bootJar` step) via `gh run rerun --failed`, correctly distinguishing it from a regression caused by this task's diff
- Source todo annotated with the real verifying run id and evidence; Unit B recorded as CI-half-complete, Dockerfile half and Units A/C explicitly still open
- Filed 3 follow-up todos for items surfaced but deliberately not fixed (mutable-tag vs. digest-pin inconsistency; `security-scan.yml`'s now-stale comment and its own stale `checkout@v3`/`setup-java@v4`; the absent `cache: 'gradle'` in `run-tests`)

## Task Commits

1. **Task 1: Capture the verbatim baseline warnings from the last green master run** — read-only, no commit (evidence recorded in this SUMMARY and the checkpoint transcript)
2. **Task 2: Blocking decision checkpoint** — no code change; orchestrator resolved `all-recommended` (A1+B1+C1)
3. **Task 3: Apply the edit, push to master, and prove the warnings are gone in a live run** — `d0206ed` (fix)

**Todo annotation + follow-up todos:** `c49fa45` (docs) — committed and pushed separately per the plan's instruction, distinct from the code-change commit.

_Note: this quick task's `<constraints>` explicitly route the SUMMARY.md/STATE.md docs commit to the orchestrator, not this executor — the `c49fa45` commit above is the plan's own required todo-annotation commit (an explicit Task 3 action item, not the orchestrator's closing docs commit)._

## Files Created/Modified

- `.github/workflows/deploy.yml` — `checkout@v3`→`@v5` (3 jobs), `setup-java@v4`→`@v5` + `'adopt'`→`'temurin'` (`run-tests` only)
- `.planning/todos/pending/2026-08-01-bump-java-version-from-21-to-25-current-lts.md` — appended a dated update recording Unit B's CI half as done, with live evidence
- `.planning/todos/pending/2026-08-16-digest-pin-github-actions-mutable-tags-are-currently-trusted-by-tag-only.md` — new, filed
- `.planning/todos/pending/2026-08-16-security-scan-yml-stale-comment-and-stale-actions-after-260816-sv1.md` — new, filed
- `.planning/todos/pending/2026-08-16-add-gradle-cache-to-deploy-yml-run-tests-job.md` — new, filed

## Decisions Made

- **Task 2 checkpoint — `all-recommended` (A1+B1+C1):** `checkout@v5` (not the brief's `v4`, verified to still leave a Node-20 warning); all three `@v3` occurrences in `deploy.yml` fixed in one push (not just `run-tests`); `security-scan.yml` left untouched with a todo filed for its now-stale comment. Rationale from the coordinator: `literal-brief` would let Task 3 claim "done" while the log still showed a Node-20 warning — the same false-verification-claim pattern this project already caught and corrected once this session (the `cleanup-old-images` todo).
- **Test-outcome equivalence measured as "BUILD SUCCESSFUL + no FAILED line + identical `actionable tasks` count"**, not a literal per-test tally — Gradle's default console reporter (no `--info`) prints no per-test count in either the before or after log. Flagged as a stated discrepancy between the plan's phrasing ("test count") and what the tool actually emits, per the coordinator's explicit direction to use this evidence and note the adjustment.
- **Transient `build-and-push-docker-image` failure treated as a retry, not a code fix.** The Dockerfile's own `RUN ./gradlew bootJar` step hit a one-off `Connection reset` fetching `gradle-8.11.1-bin.zip` from `services.gradle.org` — nothing in this task's diff touches the Dockerfile, `docker/build-push-action`, or `docker/setup-buildx-action`, so this was correctly out of scope to "fix" and was recovered via `gh run rerun --failed`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Recovered from a transient CI network failure via job rerun, not a code change**
- **Found during:** Task 3 (live verification of the pushed commit `d0206ed`)
- **Issue:** `build-and-push-docker-image` job failed: `ERROR: failed to build: failed to solve: process "/bin/sh -c ./gradlew bootJar" did not complete successfully: exit code: 1`, root cause `java.net.SocketException: Connection reset` fetching the Gradle wrapper distribution zip inside the Dockerfile's own build stage. This blocked Task 3's `<done>` criterion requiring the full downstream pipeline to conclude `success`.
- **Fix:** Confirmed the failure was unrelated to this task's diff (Dockerfile, `docker/build-push-action@v6`, `docker/setup-buildx-action@v3` all untouched by this commit) — a pure network blip, not a regression. Ran `gh run rerun 31966148764 --failed`, which re-ran only the failed job and its downstream dependents; the rerun succeeded cleanly with no further intervention.
- **Files modified:** none (operational retry, no source change)
- **Verification:** Second `gh run view 31966148764 --json conclusion,status` returned `conclusion=success`, `status=completed`, across all jobs including `build-and-push-docker-image`, `flyway-verify`, and `deploy-to-netcup`.
- **Committed in:** n/a (no code change needed)

---

**Total deviations:** 1 auto-fixed (1 blocking, resolved via job rerun rather than a code edit)
**Impact on plan:** No scope creep — the fix was an operational retry of a transient failure, verified to be unrelated to this task's diff before retrying (not blindly re-run hoping it resolves).

## Issues Encountered

- **Literal deprecation-line count did not reach exactly 0** as the plan's automated verify gate (`grep -ci deprecat`) literally counts: the after-fix run-tests log shows 5 matches, all `javac -Xlint:deprecation` notes about `AvroSchemaRegistrar.java`'s own use of a deprecated Java API, byte-identical text and line-shape to the same 5 lines present in the baseline before this task. These are unrelated to the three GitHub-Actions-version causes this task's scope actually targets (checkout Node runtime, setup-java Node runtime, `adopt` distribution alias) — Task 1's own instructions anticipated exactly this kind of scope mismatch ("Expect three distinct causes in this job... If the actual warning set differs from what the task brief predicted, report the discrepancy explicitly"). Resolved by treating the log as authoritative: the actual GitHub-Actions-caused warning count went from 15 (baseline) to 0 (after), which is the acceptance criterion's real intent; the literal `grep -ci deprecat` gate is over-broad because it also matches unrelated compiler notes about the project's own source code.
- No Gradle per-test count exists in either CI log (Gradle's default console reporter doesn't print one without `--info`) — the plan's stated "test count" evidence was substituted with "BUILD SUCCESSFUL + no FAILED line + identical actionable-task count" per the coordinator's explicit direction after the Task 2 checkpoint.

## Next Phase Readiness

- Unit B's CI half is closed; the Dockerfile half (`gradle:8.7-jdk21` → `eclipse-temurin:21-jdk-noble`, `21-jre-jammy` → `21-jre-noble`) remains a separate, still-open quick task, tracked in the same source todo.
- `security-scan.yml`'s own `checkout@v3`/`setup-java@v4` staleness and now-inaccurate comment are tracked in a new dedicated todo, not touched by this task.
- No blockers for Phase 05-06 (secret revocation) or any other in-flight work — this task was fully independent of the active phase.

---
*Phase: quick-260816-sv1*
*Completed: 2026-08-16*

## Self-Check: PASSED

All claimed files verified present on disk; both commit hashes (`d0206ed`, `c49fa45`) verified present in `git log --oneline --all`.
