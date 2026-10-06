---
phase: quick-261006-dpq
plan: 01
subsystem: api-documentation
tags: [openapi, springdoc, schemathesis, path-parameters, current-user-id]
requires:
  - phase: spike-003
    provides: "Schemathesis finding: 11 of 24 operations unfuzzable, userId leaked on 22"
provides:
  - "OpenAPI document that declares every path-template variable on every operation"
  - "OpenAPI document that no longer publishes the session-derived userId"
  - "OpenApiParameterCompletenessTest, a document-wide sweep guarding both"
affects: [kanban-board-frontend generated types, future Schemathesis CI job]
tech-stack:
  added: []
  patterns:
    - "GlobalOpenApiCustomizer that repairs a document defect at the document layer (D-08)"
    - "SpringDocUtils annotations-to-ignore registration beside the argument-resolver registration"
key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/config/PathTemplateParameterOpenApiCustomizer.java
    - src/test/java/com/vrudenko/kanban_board/config/OpenApiParameterCompletenessTest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/config/CustomArgumentResolverConfig.java
    - docs/ARCHITECTURE.md
    - docs/learning/05-api-layer.md
key-decisions:
  - "D-A: hide @CurrentUserId by registering it on springdoc's ignore list, not by meta-annotating it or deleting parameters by name"
  - "D-B: declare missing path variables in a global customizer, not by unused @PathVariable bindings or class-level @Parameters"
status: complete
commits: 2
plan_head_before: eafe3b6370d0c31bc211b82203663b371b758a33
plan_head_after: bf7323e686e5b0794df5c0fa79b0b399fb14f034
actuals:
  tokens: 5700
  tasks: 3
  commits: 2
---

# Phase quick-261006-dpq Plan 01: Make the OpenAPI document fuzzable by Schemathesis Summary

The generated `/api/docs` document now declares every `{variable}` of every route as a required
string path parameter and no longer publishes `@CurrentUserId` as a `userId` query parameter, with
no controller change, guarded by a test that walks the whole document.

## Commits

| Task | Commit | Files |
|------|--------|-------|
| 1 (tracer) | `6547d2f` fix(quick-261006-dpq): declare every path-template variable and hide @CurrentUserId in the OpenAPI document | `OpenApiParameterCompletenessTest`, `CustomArgumentResolverConfig`, `PathTemplateParameterOpenApiCustomizer` |
| 2 | none (live check, outputs in the gitignored `out/` directory) | |
| 3 | `bf7323e` docs(quick-261006-dpq): record the fuzzable-document decision | `docs/ARCHITECTURE.md`, `docs/learning/05-api-layer.md` |

`git log --stat` for both commits lists nothing under `controller/` or `security/`. No file was deleted.

## Test evidence: both directions were checked

`OpenApiParameterCompletenessTest` was run against the unfixed code, then after the userId fix
alone, then on the full fix. Counts are read from `build/test-results/test/TEST-*OpenApiParameterCompletenessTest*.xml`.

| Run | Sweep A (path template, 11 expected) | Spot-check (subtask PUT) | Sweep B (userId, 22 expected) |
|-----|--------------------------------------|--------------------------|-------------------------------|
| RED, unfixed code | FAILED: 11 operations | FAILED: declared `["subtaskId"]` only | FAILED: 22 operations |
| After D-A only (independence) | FAILED: 11 operations (unchanged) | FAILED: `["subtaskId"]` only | PASSED |
| GREEN, D-A + D-B | PASSED | PASSED | PASSED |

The RED counts matched the plan's expectation exactly, so no expectation was edited. The 11 failing
operations missing path parameters were the PUT/POST/DELETE on a column, PUT/DELETE on a task,
PUT/DELETE on a subtask, GET/POST on subtasks, PATCH reorder and GET tasks. The 22 leaking operations
were every `@CurrentUserId` site. Each sweep also asserts it walked at least 20 operations (the
document has 24).

Other verification, all run on the final tree:

- Scoped run of the four OpenAPI-reading classes (`OpenApiParameterCompletenessTest`,
  `ProblemDetailOpenApiCustomizerTest`, `OpenApiDocsTest`, `ComposedConstraintPropertyCustomizerTest`)
  passed, as did `./gradlew spotlessCheck` and `python3 scripts/verify-comments.py check`
  (292 files, 1117 comment blocks).
- The commit hook (gitleaks, comment policy, spotlessCheck, fastTest) passed for both commits.
- Full `./gradlew test` exited 0 (`BUILD SUCCESSFUL in 5m 22s`, JaCoCo verification ran):
  538 tests, 0 failures, 0 errors, 0 skipped. This run was made before the docs commit, on a tree
  whose only later change was the two markdown files.

## Schemathesis before and after (live, pinned 4.29.3, spike seed, `--max-examples 5`)

| Measure | Before (spike `out/full.txt`) | After (`out/after.txt`) |
|---------|------------------------------|-------------------------|
| Operations selected / tested | 24 selected, 11 errored out | 24 selected / 24 total, 24 tested |
| "Path parameter ... is not defined" | 5 lines, "11 errors" summary | 0 |
| Reproductions containing `userId=` | 13 | 0 (and 0 `userId` query parameters in the saved `openapi-after.json`) |

Task 2's verify command printed `VERIFY_OK`. The stack was a throwaway local compose
stack on port 8089; it was torn down (`docker compose down`, bootRun stopped by port).

One run is not a saturation run: 254 cases were generated at 5 examples per operation, and the
claim covers only what that sample reached.

### Findings now visible that are out of scope (recorded, not fixed)

The run reported 42 unique failures in 24 operations, because the nested routes can now be fuzzed.
By Schemathesis category: response violates schema 23 (22 of those are the 401/404 `instance` is not
a `uri` finding already known from the spike, which the plan excludes), API rejected schema-compliant
request 9, undocumented Content-Type 3, undocumented HTTP status code 2, unsupported methods 2, server
error on unexpected Content-Type 2, and server error 1.

**New and worth a ticket: a real 500.** `PUT /boards/{boardId}` with the body `{"version": 0}`
returns `500 INTERNAL_ERROR` with `null value in column "name" of relation "boards" violates
not-null constraint`. `UpdateBoardRequestDTO.name` is `@OptionalNotBlank`, so a body that omits it
passes validation, and `UpdateBoardRequestDTO` has no `atLeastOneFieldPopulated()` guard
(`docs/CODE_STYLE.md` rule 6 only requires that guard when a DTO has more than one optional field,
so this may be a gap in the rule's scope, not a missed application). Reproduction from `out/after.txt`:
`curl -X PUT -H 'Content-Type: application/json' -d '{"version": 0}' http://localhost:8089/api/boards/<id>`.
This is probably the unconfirmed 500 the spike saw in its run 1. It was not investigated or fixed.

## Contract change and follow-up

On the wire nothing changes: the server never read `?userId`, Spring MVC ignores extra query
parameters, and URLs are unchanged. A client generated from the document does change when it
regenerates.

**Frontend follow-up (owned by `kanban-board-frontend`, not edited here):** its `generated-types.ts`
comes from a pinned snapshot (`docs/api/kanban-board-openapi.json`, 2026-09-09) and is unaffected
until it re-snapshots. On regeneration, `query.userId` disappears from 22 operations, so the 19 call
sites in 19 files that pass `query: { userId: record.id }` become type errors; each fix is a
deletion. The path types gain required ancestor ids, which those call sites already pass.
The count of 19 call sites comes from the plan's planning-time scan; this session did not open the
frontend repository to re-count.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Decision-table ID collision in the learning guide**
- **Found during:** Task 3
- **Issue:** The plan said to add row `API-20` to `docs/learning/05-api-layer.md`, but `API-20`
  already exists (task move and reorder endpoint). Adding a second one would have duplicated an ID,
  and the plan's verify `rg -q 'API-20'` would have passed vacuously on the pre-existing row.
- **Fix:** Used the next free ID, `API-24`, for the table row, a detail paragraph under the OpenAPI
  section, and the cross-reference. The plan's verify was satisfied with `API-24` instead.
- **Files modified:** `docs/learning/05-api-layer.md`
- **Commit:** `bf7323e`

**2. [Rule 2 - Missing critical] Learning-guide test bullet and detail paragraph**
- **Found during:** Task 3
- **Issue:** The plan asked for a table row and one sentence only. The guide's convention gives each
  decision a detail paragraph and lists guarding tests under "How we test it", so a bare row would be
  the only undocumented decision there.
- **Fix:** Added a short API-24 detail paragraph and a test bullet.
- **Files modified:** `docs/learning/05-api-layer.md`
- **Commit:** `bf7323e`

**3. [Rule 1 - Bug] Javadoc line starting with `@` in new files**
- **Found during:** Task 1
- **Issue:** `{@code` wrapped onto a line that begins `@PathVariable`/`@Parameters`, which errorprone's
  `UnrecognisedJavadocTag` can read as a block tag.
- **Fix:** Reflowed the two Javadocs before the commit. No functional change.

### Environment issues (not plan deviations)

- **Gradle daemon killed mid-build.** Three consecutive first attempts failed with
  `Gradle build daemon has been stopped: stop command received`, with no visible process stopping
  it; the shared daemon registry was being stopped from outside this session. Worked around by
  exporting `GRADLE_OPTS=-Dorg.gradle.daemon.registry.base=/tmp/dpq-gradle-registry` for every Gradle
  call (including the git hook, which inherits it). At the end `./gradlew --stop` was run against
  that isolated registry only, so any daemons in the default registry belonging to other sessions
  were left alone.
- **First Schemathesis attempt was invalid and discarded.** The compose volume kept the spike's
  `fuzz@example.com` user, whose signup returned 409 and signin returned 401, so the run had an empty
  cookie ("22 operations returned authentication errors"). It was rerun with a fresh user and a
  fresh cookie; only the rerun is reported above.

### Authentication gates

None.

## Known Stubs

None.

## Threat Flags

None. No endpoint, auth path or schema was added. T-dpq-01 (perceived spoofing via a `userId`
parameter) is mitigated and guarded by `SessionDerivedUserId`. `CurrentUserIdResolver` is
unchanged and still reads only `SecurityContextHolder`.

## Cleanup

- `docker ps -a` after the work: the compose containers and network are removed. The Testcontainers
  ryuk container from the test run disappeared on its own. Three exited Testcontainers-labelled
  containers remain (`ea9697796f2a` postgres:16, `09d0fce6d520` redpanda, `a8e45073579a` postgres:16,
  exited 12 hours, 10 days and 10 days ago). They predate this session and were not started by it, so
  they were left in place.
- The stray `.planning/spikes/003-web-fuzzing-schemathesis/.schemathesis/` cache that the live run
  created was moved to the trash. The saved outputs `out/after.txt` and `out/openapi-after.json`
  remain in the gitignored `out/` directory.
- Left untracked and untouched: `.serena/`, `docs/learning/kafka-architecture-overview.md`, and this
  plan directory (the orchestrator commits it).

## Self-Check: PASSED

- FOUND: `src/main/java/com/vrudenko/kanban_board/config/PathTemplateParameterOpenApiCustomizer.java`
- FOUND: `src/test/java/com/vrudenko/kanban_board/config/OpenApiParameterCompletenessTest.java`
- FOUND: `6547d2f`, `bf7323e` are ancestors of HEAD; `git rev-list --count eafe3b6..HEAD` is 2
