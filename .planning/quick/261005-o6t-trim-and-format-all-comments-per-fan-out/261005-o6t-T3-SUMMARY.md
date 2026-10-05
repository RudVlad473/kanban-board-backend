---
phase: quick-261005-o6t
plan: 01
task: T3 (java-test)
status: complete
commits: 8
plan_head_before: 72bb681ab915405c015612653901a5967af06225
plan_head_after: ee869a7fc851e4d1093d087ccd5c58edfb85962b
actuals:
  tasks: 1
  commits: 8
---

# Quick task 261005-o6t, T3: java-test comment pass

Trimmed comments in all 65 files under `src/test/java` (63 changed) to the comment policy; code is proven unchanged.

## Commits (8, one per package batch, `--no-verify` as authorised)

| Hash | Batch |
|---|---|
| 07251d5 | dto |
| 3bb28ec | root `KanbanBoardApplicationTests`, architecture, handler, event |
| 445a1e1 | config |
| 4537466 | controller, service |
| 0e0351b | e2e |
| 5a7a156 | security |
| 31d12b8 | activitylog |
| ee869a7 | support |

## Before / after (`verify-comments.py stats --family java-test`, `comment-pass-start` vs HEAD)

| | files | lines | comment lines | % | blocks over 8 prose lines | planning-id hits | suspects | AAA markers |
|---|---|---|---|---|---|---|---|---|
| before | 65 | 19975 | 3930 | 19.7 | 61 | 198 | 15 | 1431 |
| after | 65 | 19256 | 3215 | 16.7 | 24 | 0 | 1 | 1431 |

About 1431 of the comment lines are `// arrange/act/assert` markers that stay, so the non-marker comment lines went from about 2499 to about 1784. The one remaining suspect is a false positive ("TaskMove 1" in a route-count list). The 24 remaining long blocks are decision records behind a `Why this is the way it is:`/`Decisions:`/`Known holes:` marker.

## Verification (real output)

- `check src/test/java`: `OK: 65 files, 370 comment blocks`
- `equiv --base comment-pass-start src/test/java`: `OK: equiv vs comment-pass-start: 63 changed in-scope files equivalent, 0 new, 0 allow-listed` (AAA marker count 1431 unchanged; the equiv check enforces it per file)
- `./gradlew spotlessApply` ran twice (second run idempotent), then `./gradlew spotlessCheck compileTestJava`: `BUILD SUCCESSFUL`. Two Error Prone warnings (`UnrecognisedJavadocTag` on `AbstractAppTest` `foreignUserBoard`, `BooleanLiteral` in `CorsConfigTest`); both reproduce identically with the base version of `AbstractAppTest` swapped in, so they are pre-existing.
- `gitleaks dir` over copies of the 63 changed files with `.gitleaks.toml`: `no leaks found`.
- Not run, as instructed: the full test suite.

## Deviations from Plan

- **[Rule 3 - Blocking] spotless reflow of `//` comments.** Google Java Format re-wraps `//` lines over 100 columns and leaves orphan words ("a", "real") on their own lines, which broke `check`. I reflowed the 14 affected paragraphs at 100 columns (script outside the repo) and re-ran spotless until idempotent.
- **TODO form.** The required `TODO: .planning/todos/pending/2026-08-10-...parameterized.md - <what>` cannot fit on one `//` line within 100 columns (the path alone is 93 characters), and spotless wraps it so the lint's `- <what>` match fails. `OwnershipVerifierServiceTest` carries it as a single-line `/* TODO: ... */` block comment, which spotless does not wrap. If the orchestrator prefers another form, the lint regex or a shorter path is needed.
- Replaced `{@code @Foo}` wraps that would start a Javadoc line with `@` (risk of being parsed as a block tag) with rewordings; in a few places the `@` in prose became plain `ReportAsSingleViolation`, `TransactionalEventListener(AFTER_COMMIT)`, `realSocket` tag.

## Comments kept because something points at them

- `OpenApiDocsTest` class Javadoc (docs/learning/05: MockMvc ignores the context path; tier limit).
- `LayeringArchTest`, `TestPlacementArchTest`: "floor, not a ceiling" retained as `Known holes:` (docs/learning/08 quotes the phrase).
- `AbstractKafkaContainerTest`, `AbstractPostgresContainerTest`, `AbstractAppTest` Javadocs (docs/learning/07, 08): static-initializer container start and its observed symptom, 30 s producer bounds and `Expiring 1 record(s)`, `@ServiceConnection` vs `@DynamicPropertySource`, the `recordingActivityEventListener` clear reasoning, the "extend `cleanup()` for a new FK-less table" instruction, rollback rejection on three grounds, `countQueries` vs `getQueryExecutionCount`.
- `ActivityLogAvroDeadLetterE2ETest`, `SchemaRegistryOutageE2ETest` Javadocs (docs/learning/07): dead-letter design decision, the registry-outage finding about synchronous `SerializationException`.
- `HistoricalSchemaRehearsalE2ETest` Javadoc (referenced by build.gradle:661): read-only-against-historical-DB and no-`test`-profile decisions.
- `AuthenticationTest` logout test Javadoc (docs/learning/06) and `ConcurrentSessionCeiling` Javadoc (pins `SecurityConfiguration.sessionAuthenticationStrategy`).
- `BoardServiceTest` statement-count comment (docs/learning/02: 3 statements vs 9 vs 23 observed).
- `SessionCookieAttributesE2ETest` Javadoc (referenced from `application-test.properties`).
- `AbstractAppTest.generateValidEmail()` Javadoc (root-cause writeup that `SignupRequestDTOTest` and `SchemaRegistryOutageE2ETest` point at).

## Deleted comments that carried a TODO, a hedge or content others could point at

- `TaskControllerTest` cascade-deletion TODO ("add tests for cascade deletion, i.e. when deleting task, all its subtasks should be deleted too"): no tracked item, deleted. Capture it if wanted.
- `OwnershipVerifierServiceTest` TODO: rewritten as the tracked-todo form (see Deviations).
- Three identical "Consider adding a test for when the board does not exist, or when a user tries to delete a board they do not own" suggestions in `BoardControllerTest`, `SubtaskControllerTest`, `TaskControllerTest`: untracked suggestions, deleted.
- `ColumnControllerTest` hedge ("depends on `ColumnService.findAllByBoardId` behavior ... if it returns an empty list, this test should be like ...") and the trailing "// Or handle as per actual service behavior" fragments: deleted.
- `TaskControllerTest` "Columns preservation would need to be checked differently or" fragment: deleted.
- `AuthenticationTest` Javadoc "T-hs4-04. ESCAPE HATCH: if this assertion goes red, delete it, file a todo naming the missing header, ... do not expand this task into security-header configuration": task-scoped instruction to a past executor, deleted.
- `ErrorEnvelopeConsistencyTest`: "ColumnController does NOT (yet) carry class-level @Validated ... expected to start RED" (three places) were false (`ColumnController` has `@Validated`; `LayeringArchTest` enforces it); deleted.
- `InjectionAttemptTest`: the claim that `TaskController.addSubtaskByTaskId` "binds its DTO without @RequestBody" is false now (`@Valid @RequestBody` present); rewritten to the still-true point that both routes flow through `SubtaskRepository.save`. The pending todo `2026-08-09-fix-subtask-creation-dto-missing-requestbody-binds-as-mode.md` may be closable.
- Stale statements corrected or dropped: `FlywaySchemaProvenanceTest` "Flyway V1-V4" and "`AbstractAppTest` still wired to H2"; `AbstractPostgresContainerTest` "Phase 5 Neon target" and the wrong FQN `com.vrudenko.kanban_board.FlywaySchemaProvenanceTest` (now `...config.FlywaySchemaProvenanceTest`); `AbstractAppTest` "Flyway V1-V4"; `AbstractKafkaContainerTest` "container per test class" (now one container); these match the stale items listed in docs/learning/01 and 08.

## Rename suggestions (renames are code changes, out of scope)

- `ComposedConstraintPropertyCustomizerTest.shouldAcceptAnAstralHeavyValueThatViolatesTheRealSizeMax_perD3Decision` carries a planning id (`D3`) in its name.

## Follow-ups for the orchestrator

- docs/learning/*.md contains line-anchored links into these test files (for example `BoardServiceTest#L490-L496`, `AbstractAppTest#L36-L48`, `ActivityLogAvroDeadLetterE2ETest#L34-L55`, `AbstractKafkaContainerTest#L22-L33`, `SchemaRegistryOutageE2ETest#L36-L94`). The line numbers have shifted, and several of the "stale Javadoc" rows in docs/learning/01 and 08 are now fixed in the source. Not edited here (out of scope).

## Could not verify

- Whether the 24 remaining long blocks and the retained decision-record content are all still true against current behaviour; I kept observations, versions and dates as written and did not re-measure any of them.
- No full test run (per instructions); the equivalence proof is token-level over Java comments only.

## Self-Check: PASSED

- 8 commits present in `72bb681..HEAD` (`git log` lists 07251d5 through ee869a7).
- `check` and `equiv` re-run on the committed tree, both OK.
