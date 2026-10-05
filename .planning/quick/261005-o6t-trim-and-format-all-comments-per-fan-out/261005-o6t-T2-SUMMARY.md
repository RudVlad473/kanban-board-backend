---
phase: quick-261005-o6t
plan: 01
task: T2
status: complete
base: 72bb681
comment_pass_start: tag comment-pass-start
head: bdcb432
---

# Quick 261005-o6t, T2 (java-main comment pass)

Scope: `src/main/java/**/*.java` except `UserService.java` (T1). Comment-only edits in 89 files, 7 commits, nothing pushed.

## Commits (all `--no-verify`, as authorised for this run)

| Hash | Package batch |
|---|---|
| 0932cd6 | config |
| 22bf25f | service |
| 461a674 | security |
| 0c6342c | handler, controller, activitylog |
| 0b5265d | entity, repository, mapper |
| de20128 | event, dto |
| bdcb432 | constant, base, exception |

## Before / after (`verify-comments.py stats --family java-main`)

| Ref | files | lines | comment lines | comment % | blocks > 8 prose lines | gated id hits | suspects |
|---|---|---|---|---|---|---|---|
| comment-pass-start | 129 | 7633 | 2080 | 27.3 | 61 | 161 | 8 |
| 72bb681 (after T1, before T2) | 129 | 7630 | 2077 | 27.2 | 61 | 154 | 8 |
| bdcb432 (after T2) | 129 | 7285 | 1732 | 23.8 | 45 | 0 | 0 |

The 45 remaining long blocks all sit behind a `Decisions:` marker (the lint's R3 passes). No length target was used.

## Verification (real output, final state bdcb432)

- `python3 scripts/verify-comments.py check src/main/java` -> `OK: 129 files, 226 comment blocks`
- `python3 scripts/verify-comments.py equiv --base comment-pass-start src/main/java` -> `OK: equiv vs comment-pass-start: 90 changed in-scope files equivalent, 0 new, 0 allow-listed` (89 T2 files plus T1's `UserService.java`)
- `./gradlew spotlessCheck compileJava compileTestJava` -> `BUILD SUCCESSFUL in 1m 6s`; no new compiler warnings from main sources.
- `gitleaks dir src/main/java --config .gitleaks.toml` -> `no leaks found`.
- Not run: the full test suite (orchestrator runs it once), `gitleaks git --staged` (the worktree guard refuses `gitleaks git`, so `dir` was used), per-commit spotless/compile (run once on the final state; formatting was applied before staging).

Gradle note: another executor's `./gradlew --stop` repeatedly killed my builds ("Gradle build daemon has been stopped: stop command received"), even with `--no-daemon`. Passing `-Dorg.gradle.daemon.registry.base=/tmp/gd-t2` isolated my daemon from it. The orchestrator may want the same flag for T3/T4.

## Comments kept because something points at them

- `SecurityConfiguration.sessionAuthenticationStrategy` Javadoc: TOCTOU window (F6, 2026-08-10), advisory-lock measurement (2026-08-11), 401-no-oracle rule, plus the two-enforcers record moved in from the `sessionManagement` block comment. Pointed at by docs diagrams, `.claude/CLAUDE.md`, `ConcurrentSigninCeilingE2ETest`.
- `RandFlakeGenerator`: layout and uniqueness-scope decision now sit in the class Javadoc, which is what `.claude/CLAUDE.md` already says it contains. Epoch decision, `allowAssignedIdentifiers` two-override record and the CAS-loop record kept with their dates and versions.
- `ColumnService.deleteAllByBoardId`, `TaskService.deleteAllByColumn`, `TaskService.deleteById`, `TaskService.moveToColumn`, `TaskService.save`, `BoardService.findFullById`: cited by `docs/ARCHITECTURE.md` and `docs/learning/*`.
- `ActivityController` `Page` vs `PagedModel` record, `ActivityLogEntity`, `ActivityEvent`, `AvroSchemaRegistrar`, `ActivityEventAvroMapper`, `KafkaConsumerConfig` bean records, `KafkaEventPublisher`, `BoardRepository` fetch-join record, `ErrorCode` contract, `UserController` IDOR note, `UpdateThemeRequestDTO` (UserService.updateTheme relies on its last-write-wins rationale), `ComposedConstraintPropertyCustomizer` Observation 1 and 2 (labels preserved; `Accumulator` Javadocs refer to "Observation 2").
- `ResetController`: the `// planner-discipline-allow: app.reset.token` line is untouched and still sits directly above the `@Value`.

## TODOs and tracked items

- Deleted (no tracked item, for the orchestrator to capture): `TaskService.findById`: `// TODO: make a service interface`.
- `AuthenticationController` signup `Location` note: could not be written as `TODO: <path> - <text>` (see Findings, item 1). It now reads "The URI names no resource yet: GET /users/me has no handler. Tracked in .planning/todos/pending/2026-08-12-signup-location-header-points-at-a-uri-with-no-get-handler.md".
- `ActivityLogService`: dropped the reference `PAGE-V2-01` (keyset pagination). The text now says keyset pagination "is the fix and is not shipped here".

## Findings for the orchestrator

1. **R4 vs google-java-format.** gjf re-wraps a `// TODO: <long repo path> - <text>` comment so the `- <text>` part moves to the next line, which fails the lint's one-line `TODO: <path> - <what>` shape. A `.planning/todos/...` path is about 100 characters on its own, so every TODO that links a todo file hits this. T3 has the same issue (`OwnershipVerifierServiceTest` TODO). Options: accept a TODO whose link is the last token on its line with the text on the next, or shorten todo paths. Not changed by me.
2. **`planner-discipline-allow:`** is read by planner tooling (`ResetController`, `application.properties`) but is not in the script's FUNCTIONAL line patterns. A 4-line block ending with that directive trips R2; I kept the block at 3 lines. Consider adding it to FUNCTIONAL_RES.
3. **Summary-first applies to any block of 4+ prose lines, including `//` blocks inside methods**, so many in-method comments were restructured as summary / blank `//` / detail. gjf re-wraps `//` lines over 100 columns, which can silently change line counts after `spotlessApply`; re-run `check` after it.
4. **Stale claims corrected or removed** (not verifiable replacements): `ActivityLogService` said ULIDs "sort lexicographically by generation time" (ids are base36 RandFlake; the repo's own `BoardService.save` record says base36 string order is not numeric order), so the tiebreak claim is gone. `BoardFullMapper`/`ColumnFullMapper` called the fields `List`-typed; they are `Set`. `TaskEntity`/`ValidationConstants` said "ULIDs". `TaskRepository` pointed at a `TaskService#moveToColumn` Javadoc paragraph that does not exist (per `docs/learning/04`); the pointer is removed.
5. **Code observation (not a comment, not touched):** `ValidationConstants.TASK_DESCRIPTION_LENGTH_VALIDATION_MESSAGE` interpolates the *title* min/max constants, not the description ones.
6. **Pointers into main-source comments from test code (T3 scope):** `InjectionAttemptTest.java:61` cites `SecurityConfiguration`'s "corrected sessionManagement comment", which is now a one-line pointer to the `sessionAuthenticationStrategy` Javadoc; `ComposedConstraintPropertyCustomizerTest.java:1113` mentions the customizer's Observation 2 (label kept, still valid).
7. **`.claude/CLAUDE.md`** line 353 says `RandFlakeGenerator`'s Javadoc holds the layout and uniqueness-scope rationale. Before this pass it was in `//` comments; it is now true.

## Rename suggestions (not done, out of scope)

- `BoardEntity.column` / `ColumnEntity.task` are singular names on `Set` fields; the mapper Javadocs now say so explicitly. A rename touches `mappedBy` and JPQL.
- `ActivityAction` lives in the `entity` package (existing pending todo).

## Decisions kept as records, with their falsifiers

swagger-core 2.2.30 / Hibernate 6.6.53 / gradle-avro-plugin 1.9.1 / node v24.19.0 observations and dates (2026-08-10, 08-11, 09-04, 09-05, 09-08) are unchanged. No date was added that the original lacked; the quick-task id 260813-m9x lost its (derivable but unstated) date, so that record now says "measured, not assumed" only.

## Exemptions added

None. `scripts/verify-comments.py` and the selftest are untouched.

## Not verified

- Javadoc HTML rendering (only `compileJava`/Error Prone and spotless were run).
- The full test suite.
- Windows hook behaviour (not in T2 scope).
