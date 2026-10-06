---
phase: quick-261006-i3k
plan: 01
subsystem: ci-tooling
tags: [schemathesis, fuzzing, openapi, github-actions, docker-compose, report-only]
requires:
  - phase: quick-261006-dpq
    provides: "all 24 operations fuzzable (path parameters declared)"
  - phase: quick-261006-guz
    provides: "the 500 on a version-only board update fixed"
provides:
  - "scripts/fuzz/run-fuzz.py: one-command, isolated, always-torn-down Schemathesis run"
  - "scripts/fuzz/schemathesis-hooks.py: single-session cookie auth with timed re-sign-in"
  - "scripts/fuzz/run-fuzz-selftest.py: guard and teardown proven in both directions"
  - ".github/workflows/api-fuzz.yml: weekly and manual report-only job"
  - "a measured local baseline by failure class"
affects: [api-documentation, openapi, ci]
tech-stack:
  added: ["schemathesis 4.29.3 (uvx, pinned, dependency freeze 2026-10-06)", "uv 0.12.11 (CI only, pip-pinned)"]
  patterns: ["Strategy-injected steps so teardown paths are testable", "kill-by-port restricted to a java command line"]
key-files:
  created:
    - scripts/fuzz/run-fuzz.py
    - scripts/fuzz/schemathesis-hooks.py
    - scripts/fuzz/run-fuzz-selftest.py
    - .github/workflows/api-fuzz.yml
    - .planning/todos/pending/2026-10-06-openapi-error-instance-declared-format-uri-but-app-returns-a-path.md
    - .planning/todos/pending/2026-10-06-openapi-does-not-list-the-201-created-responses.md
    - .planning/todos/pending/2026-10-06-openapi-request-schemas-omit-the-validation-constraints.md
    - .planning/todos/pending/2026-10-06-openapi-column-color-documented-non-null-but-returned-null.md
    - .planning/todos/pending/2026-10-06-malformed-multipart-content-type-returns-500.md
  modified:
    - docs/learning/09-build-quality-and-ci.md
key-decisions:
  - "Throwaway compose Redpanda plus registerSchemas, not a publishing toggle (none exists)"
  - "stdlib Python for orchestrator, hooks and selftest; the hook must run inside Schemathesis' process anyway"
  - "Compose project kanban-fuzz with down -v, so teardown cannot touch a developer's dev stack"
  - "Re-sign-in carries the current cookie so the server rotates one session and the 2-session ceiling never trips"
status: complete
completed: 2026-10-06
commits: 2
plan_head_before: 260490d684ee0389354ad49c1670aedf908d88e0
plan_head_after: c5079e81cde52e0d000e186eccc1d3ed2aa1b618
actuals:
  tokens: 14600
  tasks: 3
  commits: 2
---

# Phase quick-261006-i3k Plan 01: report-only Schemathesis fuzzing Summary

One command (`python3 scripts/fuzz/run-fuzz.py`) boots an isolated `kanban-fuzz` compose stack, fuzzes
the live OpenAPI document with a pinned Schemathesis, and always tears the stack down and verifies it.

## What was built

| File | Role |
|---|---|
| `scripts/fuzz/run-fuzz.py` | Loopback guard, env sanitizer, compose boot, `registerSchemas`, `bootRun` on a free `127.0.0.1` port, random fuzz user, Schemathesis run, by-class summary, teardown by port and by compose project |
| `scripts/fuzz/schemathesis-hooks.py` | Auth provider: re-signs in every `--refresh-seconds` and on 401, carrying the current cookie; skipped for `/signin` and `/signup` |
| `scripts/fuzz/run-fuzz-selftest.py` | 19 tests: guard (function and process level), every teardown path, env sanitizer, refresh bound, report-dir safety, `ss` parser, summarizer, live kill-by-port |
| `.github/workflows/api-fuzz.yml` | `workflow_dispatch` plus Mondays 07:00 UTC, `contents: read`, no secrets, selftest before the fuzz step, `out/fuzz/` uploaded with `if: always()` |
| `docs/learning/09-build-quality-and-ci.md` | Table row and a "How it works" bullet |

Nothing under `src/`, `build.gradle`, `settings.gradle` or `gradle/` changed
(`git diff --quiet 260490d -- src build.gradle settings.gradle gradle` exits 0).

Data flow: the script builds a sanitized loopback-only environment, boots the isolated stack,
registers the Avro schemas, starts the app, signs up a fuzz user and saves `/api/docs`. Schemathesis
runs against that saved document with the hooks supplying the session cookie. A `finally` block stops
the app by its port and runs `compose down -v --remove-orphans`, then verifies both.

## Verification results

| Check | Result |
|---|---|
| Tracer run, `--max-examples 2` | rc 0, 24 of 24 operations tested, 134 cases, 0 `SerializationException`, no containers left, no `8080` literal in the scripts |
| Comment lint (`scripts/verify-comments.py check`) | pass, 296 files |
| Selftest on the final tree | 19/19 pass |
| Selftest with the guard neutered (early `return` in `assert_local_base_url`) | 17/19: `test_guard_refuses_every_non_loopback_target` and `test_process_refuses_a_remote_target_before_any_tool_starts` FAIL |
| Selftest with the teardown call removed from the driver's `finally` | 11/19: eight teardown tests FAIL (fuzz raises, Schemathesis exit 2, boot fails midway, KeyboardInterrupt, SIGTERM, findings-are-a-completed-run, refused target, failed teardown check) |
| After `git checkout -- scripts/fuzz/run-fuzz.py` | `git diff --quiet` clean, selftest 19/19 |
| Workflow structural check (triggers, permissions, no secrets, selftest before fuzz, upload `if: always()`, no `continue-on-error`, no `${{ }}` in `run:`) | `WORKFLOW_OK` |
| Pre-commit hook (gitleaks, comment lint, spotlessCheck, fastTest) | ran on both commits, not bypassed |
| After all runs | no `kanban-fuzz` container, volume or network; no listener on 5433, 9092, 8081 or any recorded app port |

## Baseline (one sample, not a saturation run)

Command: `python3 scripts/fuzz/run-fuzz.py --report-dir out/fuzz/baseline` with defaults: 25 examples
per operation, seed 24925650107731152066331093038258341477, `--workers 1`, Schemathesis 4.29.3. App
port 58233. Schemathesis exit code 1 (findings), script exit code 0.

| Failure class | Count |
|---|---|
| Response violates schema | 24 (22 `instance` is not a `uri`; 2 `color` null) |
| API rejected schema-compliant request | 9 |
| Undocumented Content-Type | 7 |
| Undocumented HTTP status code | 3 |
| Unsupported methods (TRACE returns 400, expected 405) | 3 |
| Server error on unexpected Content-Type | 2 |
| API accepted schema-violating request | 1 |
| Error groups | none |

| Metric | Baseline | Spike 003 |
|---|---|---|
| Unique failures | 49 (the seven rows above sum to 49) | 45 |
| Operations tested | 24 of 24 | 24 of 24 selected |
| Cases generated | 870 | 639 |
| Schemathesis running time | 56.9 s (116.7 s including boot and teardown) | 51 s |
| Schema errors | 0 | 11 |
| Avro `SerializationException` in `app.log` | 0 | about 60 |
| Re-authentications counted by Schemathesis | 0 (`reauth_broke` false) | n/a |
| Teardown checks in `run.json` | containers, volumes and port all clear | n/a |

The counts are not comparable one for one with the spike: the spike ran 11 operations fewer in effect
(path parameters undeclared), and server-generated ids and session timing move counts between runs.
What the comparison does support: zero schema errors, zero Avro noise, and a finding set that
overlaps the spike's (instance format, rejected schema-compliant bodies, TRACE 400).

## Live re-sign-in proof

| Run | Sign-ins with `status=200` | `reauth_broke` | Schemathesis time |
|---|---|---|---|
| `--refresh-seconds 10 --max-examples 10` | 2 | false | 36.7 s |
| `--refresh-seconds 5 --max-examples 10` | 7 | false | 71.8 s |

The plan's must-have names the 10 second run and expects at least 3. It logged 2, so the plan's own
fallback applies and the 5 second run is the proof: 7 sign-ins, all 200, session never lost. The
sign-ins are not evenly spaced (gaps of 22 s and 17 s appear at 5 s), so the provider is consulted
only at certain points of the run, not on every request. The negative direction (a third session is
refused when the cookie is not carried) is the existing `AuthenticationTest.ConcurrentSessionCeiling`;
it was cited, not re-mutated.

## Findings filed as todos (none fixed)

| Todo | Baseline evidence |
|---|---|
| `2026-10-06-openapi-error-instance-declared-format-uri-but-app-returns-a-path.md` | 22 (12 on 400 responses, 10 on 404) |
| `2026-10-06-openapi-does-not-list-the-201-created-responses.md` | 3 (POST /boards and the stateful column create) |
| `2026-10-06-openapi-request-schemas-omit-the-validation-constraints.md` | 9 |
| `2026-10-06-openapi-column-color-documented-non-null-but-returned-null.md` | 2 |
| `2026-10-06-malformed-multipart-content-type-returns-500.md` | 2 (the only 500 class; GET /boards and GET /users/me/theme) |

Not reproduced, so not filed as confirmed: `/signup` returning an undocumented 201 (noted in the 201
todo as probable); the spike's unidentified run-1 500 (the multipart 500 is a candidate, but nothing
ties it to that run). Seen but not filed, outside the plan's list: undocumented `application/json`
error bodies (7, a request with an undecodable path gets Spring's default 400 body instead of
`application/problem+json`), TRACE returning 400 instead of 405 (3), and one accepted schema-violating
body (1, extra properties accepted).

## Deviations from Plan

**1. [Rule 3 - Blocking] Pre-commit comment lint refused the first commit**
- **Found during:** Task 1 commit
- **Issue:** the `run-fuzz.py` module docstring had 22 prose lines before `Decisions:` (limit 8).
- **Fix:** moved the flag and report-file documentation into argparse `help=` text and kept the
  docstring to a summary, the exit codes, `Decisions:` and `Known holes:`.
- **Files modified:** `scripts/fuzz/run-fuzz.py`
- **Commit:** 31f43ee (the refused attempt created nothing)

**2. [Rule 2 - Missing critical] Port preflight sets `SO_REUSEADDR`**
- **Found during:** reasoning after Task 1 draft, not observed live
- **Issue:** a bare `bind` reports a port busy while old connections sit in TIME_WAIT, which a second
  run right after the first would hit on 8081 or 9092.
- **Fix:** `SO_REUSEADDR` on the probe socket; a live listener still fails the bind.
- **Files modified:** `scripts/fuzz/run-fuzz.py`

**3. [Plan refinement] Process-level refusal test uses `selftest.invalid`, not `example.com`**
- The behavior list names `http://example.com/api`. The function-level guard test still covers it;
  the process-level test uses a host that can never resolve, so a neutered guard cannot make the
  selftest send a real request to a third party while it proves it can fail.

**4. [Plan fallback, as written] Re-sign-in proof taken at 5 seconds**
- See "Live re-sign-in proof" above.

## Auth gates

None.

## Known stubs

None.

## Threat flags

None beyond the plan's `<threat_model>`. The script adds no network listener of its own: the app
binds `127.0.0.1` (T-i3k-05) and compose publishes 5433, 9092 and 8081 as the repo's own compose file
already does, only for the minutes a run lasts.

## Open items

1. **The workflow could not be dispatched before merge.** GitHub dispatches only workflows present on
   the default branch, so `api-fuzz.yml` has never run on a hosted runner. Everything it runs was run
   locally except the runner-specific steps: `pip install uv==0.12.11`, `uvx` fetching Schemathesis
   from PyPI, and Docker on `ubuntu-latest`. After merge: `gh workflow run api-fuzz.yml` followed by
   `gh run watch`. The merge itself waits for the user.
2. **The 401-triggered re-sign-in was not exercised live.** `reauth_count` was 0 in every run, so no
   401 ever occurred on a signed-in request. The timed path is proven; the 401 retry rests on
   Schemathesis' documented default (`retry_on` 401) and was not mutated here.
3. The five todo files and this SUMMARY are uncommitted by instruction; the orchestrator commits them.
4. Containers and volumes: none of this run's remain. Other containers on the machine
   (`freehire-*`, `anythingllm`, and several exited ones) pre-existed and were not touched.
5. `./gradlew --stop` was run at the end. No Gradle run was killed by "stop command received".

## Self-Check: PASSED

- `scripts/fuzz/run-fuzz.py`, `schemathesis-hooks.py`, `run-fuzz-selftest.py` and
  `.github/workflows/api-fuzz.yml`: present.
- Commits 31f43ee and c5079e8: ancestors of HEAD.
- Five todo files: present under `.planning/todos/pending/`.
