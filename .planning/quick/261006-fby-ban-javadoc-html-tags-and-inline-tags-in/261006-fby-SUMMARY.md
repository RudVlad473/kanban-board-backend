---
phase: quick-261006-fby
plan: 01
subsystem: comment-policy
tags: [javadoc, comment-lint, spotless, google-java-format]
requires: []
provides:
  - "javadoc-markup rule in scripts/verify-comments.py (Java only, hook and CI gated)"
  - "google-java-format with Javadoc formatting off"
  - "Java comments swept to plain text (116 files)"
affects: [scripts/verify-comments.py, build.gradle, docs/CODE_STYLE.md]
key-files:
  created:
    - .planning/quick/261006-fby-ban-javadoc-html-tags-and-inline-tags-in/sweep-javadoc-markup.py
  modified:
    - scripts/verify-comments.py
    - scripts/verify-comments-selftest.py
    - build.gradle
    - docs/CODE_STYLE.md
    - .planning/codebase/CONVENTIONS.md
decisions:
  - "Attribute part of the HTML tag regex is strict (name, optional =value), so `a<i && b>c` is not a tag"
  - "Two imports that only a removed link tag referenced are dropped by spotless; proven import-only"
status: incomplete
commits: 2
plan_head_before: 1f348615d27f331c9ac21ec58efc70b3aec1a8eb
plan_head_after: 629a0d60427507bdfef87ea8ed8080f232b9f0f5
actuals:
  tokens: 91578
  tasks: 3
  commits: 2
---

# Phase quick-261006-fby Plan 01: ban Javadoc HTML tags and inline tags in Java comments Summary

Java comments are plain text: a `javadoc-markup` lint rule rejects HTML tags and `{@name` inline tags, google-java-format no longer re-inserts the paragraph tag, and 1,126 violating lines in 116 files were swept by a tokenizer-scoped script with a word-sequence proof.

**Status is `incomplete` for one reason only.** `.claude/CLAUDE.md` lines 173-174 still tell agents to write the banned markup, and the user did not approve editing that file. Every other must-have is met (table below). The open item and its replacement text are in "Open items".

## Commits

| Commit | Hash | Content |
|--------|------|---------|
| 1 | a4a47e1 | `build.gradle` formatter flag, 116 swept Java files, sweep script (118 files) |
| 2 | 629a0d6 | rule, selftest, `docs/CODE_STYLE.md`, `.planning/codebase/CONVENTIONS.md` (4 files) |

Both went through the unmodified pre-commit hook (gitleaks, comment lint, spotlessCheck, fastTest) without `--no-verify`. Not pushed. For each commit, the hook's first Gradle run was killed by an external `./gradlew --stop` (see Environment), and the commit was retried unchanged; the retry ran the full hook and passed. In commit 2, fastTest was UP-TO-DATE because no Java changed since commit 1.

## Measured results

| Check | Result |
|-------|--------|
| Violations before the sweep (HEAD 1f34861, rule in working tree) | 1,126 `javadoc-markup` lines in 116 files (554 java-main, 572 java-test); 0 from any other rule. Matches the plan exactly. |
| Violations after the sweep | `verify-comments.py check`: OK, 294 files, 0 violations |
| Selftest RED (rule absent) | `test_r5_javadoc_markup_fires_on_html_tags` and `test_r5_javadoc_markup_fires_on_inline_tags` FAILED; 42 of 44 passed. The `ignores_lookalikes` and `is_java_only` cases passed vacuously, as the plan predicted. |
| Selftest GREEN | 44 of 44 |
| Quiet-side mutation (`re.I` on the tag regex) | `test_r5_javadoc_markup_ignores_lookalikes` FAILED on `Builder<B>` and `Box<I>`; reverted, 44 of 44. See deviation 1 for why the plan's `Pair<A, B>` example did not bite. |
| `spotlessCheck` with `.formatJavadoc(false)` | passes on the unswept tree and on the swept tree |
| `spotlessCheck` without the flag (negative control) | FAILED on `BoardRepository.java`; the reported diff re-inserted `<p>DISTINCT...`, `<p>Decisions:`, `<p>The fix:` and so on. Flag restored, passes again. |
| Sweep word proof | `sweep-javadoc-markup.py verify --base 1f34861`: 116 files, 0 CHANGED, 15 ADDED words |
| `equiv --base 1f34861` | 114 of 116 files OK; 2 files differ by one import each (deviation 2), proven import-only |
| Diff lines outside comments | 0 apart from those two deleted import lines |
| Idempotence | a second `apply` over both trees: 0 files changed |
| Full suite | `./gradlew test` BUILD SUCCESSFUL (6m 28s) on the swept tree, including `jacocoTestCoverageVerification` |
| ErrorProne histogram | before: `BooleanLiteral` 1 (CorsConfigTest:47), `UnrecognisedJavadocTag` 1 (AbstractAppTest:89), main sources 0. After: `BooleanLiteral` 1. Nothing new, nothing rose, so no `disable('UnescapedEntity')` was needed. The `UnrecognisedJavadocTag` finding went away because the malformed inline tag it flagged is now plain text. |
| Memory-pressure gate | not tripped; `free -h` showed 129Mi free but about 3.5Gi available at the heaviest point, and the hook JVMs completed |

The 15 ADDED words are exactly the 8 hand-fixed paragraph-start lines: "The" and "annotation" at KafkaConsumerConfig:148, BoardService:180, ColumnService:85, SubtaskService:44, TaskService:51, ComposedConstraintPropertyCustomizerTest:697 and SigninTimingEqualizationTest:101, and "The" alone at TaskService:289 (`The @Version bypass, by design: ...`). Nothing else was reworded.

## Deviations from Plan

**1. [Rule 1 - Bug] The plan's quiet-side mutation example does not bite with a strict attribute regex**
- **Found during:** Task 1, mutation proof.
- **Issue:** the plan says adding `re.I` must make the lookalike case fail on `Pair<A, B>`. My tag regex requires attributes to look like `name` or `name=value`, so `<A, B>` never matched even case-insensitively, and the mutation passed.
- **Fix:** kept the stricter regex (it also keeps `if (a<i && b>c)` quiet, which a looser one would flag) and added `Builder<B>` and `Box<I>` to the lookalike case. With `re.I` the case now fails; reverted, it passes.
- **Files modified:** `scripts/verify-comments.py`, `scripts/verify-comments-selftest.py`
- **Commit:** 629a0d6

**2. [Rule 3 - Blocking] Two imports became unused after the sweep, so `equiv` fails on two files**
- **Found during:** Task 2, `spotlessCheck` after the sweep.
- **Issue:** `SchemaRegistryOutageE2ETest.java` imported `KafkaEventPublisher` and `AbstractAppTest.java` imported `Password`, each referenced only from a `{@link ...}` that is now plain text. Spotless removes unused imports, so `spotlessCheck` failed until `spotlessApply` deleted them. That is a non-comment change, so `equiv` reports "code differs" for those two files, against the plan's "OK for every changed file".
- **Fix:** ran `./gradlew spotlessApply`. Proof that the difference is import-only: take the base text, delete the one import line, and `equiv_problems` against the working file returns `[]` for both files; neither name remains as a code token. The sweep script's docstring now says to run `spotlessApply` after `apply`.
- **Files modified:** the two files above (one deleted import line each)
- **Commit:** a4a47e1

**3. [Rule 1 - Bug] Sweep script missed an indented line-leading `@`**
- **Found during:** Task 2, diff review (not caught by `check`).
- **Issue:** `ConcurrentSigninCeilingE2ETest.java:46` ended up as `*   @Tag("realSocket") keeps ...`, an `@` after list-continuation indentation. Javadoc ignores that indentation and would read a block tag. The repair tested `line.startswith("@")`, not the stripped line.
- **Fix:** the repair now looks at the stripped line and keeps the indentation when it pulls the previous word down. The file was restored from git and re-swept; the line now reads `one. @Tag("realSocket") keeps ...`. A diff scan then found 0 new `@`-leading comment lines other than the 8 hand fixes.
- **Commit:** a4a47e1

**4. [Rule 1 - Bug] An existing selftest case contradicted the new rule**
- **Found during:** Task 1, GREEN run.
- **Issue:** `test_r3_narration_needs_a_marker_after_eight_prose_lines` uses `<p>Decisions:` as a marker and expected no violation; the new rule now flags that tag. The plan assumed no existing case would change.
- **Fix:** for that one marker the case expects `["javadoc-markup"]` (the marker still ends the narration count, the tag itself is rejected); all other markers still expect `[]`. `MARKER_RE` and `is_prose` are unchanged, as the plan required.
- **Commit:** 629a0d6

**5. [Rule 1 - Doc correctness] build.gradle sentence about EscapedEntity was already false**
- **Found during:** Task 2, ErrorProne histogram.
- **Issue:** the comment above `compileTestJava` claimed an unfixed `EscapedEntity` warning at `UserMapper.java:23`. The pre-sweep baseline compile showed zero main-source warnings, so the sentence was stale before this task.
- **Fix:** rewrote it to say a full compile on 2026-10-06 reported zero main-source warnings and the asymmetry is a policy choice.
- **Commit:** a4a47e1

**6. [Measurement difference] Non-HTML angle tokens: 7, not 5**
The linter docstring's falsifiable Decision cites a count. Counting every `<...>` token in Java comments that the rule does not match, at 1f34861, gives 7: `<name>`, `<T>`, `<SubtaskEntity>`, `<Outcome>`, `<Condition>`, `<String, String>`, `<String, Object>`. The plan's 5 left out the two generic argument lists. The docstring says 7, with 0 uppercase HTML tags (same on the final tree).

## Environment note: external Gradle daemon kills

Every Gradle run here was intermittently killed with `Gradle build daemon has been stopped: stop command received`, the signature in `docs/SESSION_LESSONS.md` lesson 9. Memory was not the cause this time. `.claude/settings.json` has a `SubagentStop` hook that runs `./gradlew --stop`, and another Claude session in this checkout stops subagents while this ran. I used a retry wrapper that re-runs a Gradle command only when the log contains `stop command received`, for builds, the full test run and the two commits' hooks. No check was skipped, `--no-verify` was never used, and no result above comes from a killed run.

## Open items

1. **`.claude/CLAUDE.md` lines 173-174 (user did not approve editing).** They are the generated copy of the CONVENTIONS.md lines I replaced, and they still say:
   - `- Link references used: {@link ClassName#methodName} to cross-reference related methods`
   - `- HTML tags used in JavaDoc: <p> for paragraphs, {@code variableName} for inline code`

   Replacement for the two lines (identical to what went into `.planning/codebase/CONVENTIONS.md`):

   `- Comments are plain text, with no HTML tags and no {@...} inline tags (docs/CODE_STYLE.md rule 14, enforced by scripts/verify-comments.py); identifiers are written by bare name, a blank comment line separates paragraphs, and - starts a list item`

   Until this is applied, a future agent reading CLAUDE.md is told to write markup the hook will now reject.
2. **Fan-out-review skill** (out of scope per the plan): not touched.
3. **Comment width is no longer machine-normalised.** With Javadoc formatting off, lines shortened by the sweep stay ragged (for example `chain (` ending a line before a pulled-down identifier). Accepted in the plan's trade-offs.

## Known stubs

None.

## Threat flags

None. No new endpoint, auth path, file access or schema change.

## Review aid

`git show --stat HEAD~1 | tail -1`: 118 files changed, 1671 insertions(+), 1312 deletions(-). `git show --word-diff=plain HEAD~1 -- src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java` shows the tracer file: the ordered list became `1.` and `2.`, `Decisions:` survives as a marker line, links read `BoardEntity.getColumn()` and `com.vrudenko.kanban_board.entity.ColumnEntity.getTask()`, and the arrow chain reads `board->column->task->subtasks`.

Swept files (116):

- src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java
- src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogRecorder.java
- src/main/java/com/vrudenko/kanban_board/config/AsyncConfig.java
- src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java
- src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java
- src/main/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizer.java
- src/main/java/com/vrudenko/kanban_board/config/CorsConfig.java
- src/main/java/com/vrudenko/kanban_board/config/EventIdGenerator.java
- src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java
- src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java
- src/main/java/com/vrudenko/kanban_board/config/PathTemplateParameterOpenApiCustomizer.java
- src/main/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizer.java
- src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
- src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java
- src/main/java/com/vrudenko/kanban_board/constant/ErrorCode.java
- src/main/java/com/vrudenko/kanban_board/controller/BoardController.java
- src/main/java/com/vrudenko/kanban_board/controller/ResetController.java
- src/main/java/com/vrudenko/kanban_board/controller/TaskMoveController.java
- src/main/java/com/vrudenko/kanban_board/controller/UserController.java
- src/main/java/com/vrudenko/kanban_board/dto/activity_dto/ActivityLogResponseDTO.java
- src/main/java/com/vrudenko/kanban_board/dto/annotation/BmpOnly.java
- src/main/java/com/vrudenko/kanban_board/dto/annotation/BoardId.java
- src/main/java/com/vrudenko/kanban_board/dto/annotation/ColumnColor.java
- src/main/java/com/vrudenko/kanban_board/dto/annotation/OptionalNotBlank.java
- src/main/java/com/vrudenko/kanban_board/dto/board_dto/BoardFullResponseDTO.java
- src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java
- src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnFullResponseDTO.java
- src/main/java/com/vrudenko/kanban_board/dto/column_dto/ReorderColumnRequestDTO.java
- src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java
- src/main/java/com/vrudenko/kanban_board/dto/reset_dto/ResetUsersRequestDTO.java
- src/main/java/com/vrudenko/kanban_board/dto/task_dto/TaskFullResponseDTO.java
- src/main/java/com/vrudenko/kanban_board/dto/user_dto/UpdateThemeRequestDTO.java
- src/main/java/com/vrudenko/kanban_board/entity/ActivityAction.java
- src/main/java/com/vrudenko/kanban_board/entity/ActivityLogEntity.java
- src/main/java/com/vrudenko/kanban_board/entity/ThemePreference.java
- src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java
- src/main/java/com/vrudenko/kanban_board/event/BoardDeletedEvent.java
- src/main/java/com/vrudenko/kanban_board/event/ColumnDeletedEvent.java
- src/main/java/com/vrudenko/kanban_board/event/ColumnReorderedEvent.java
- src/main/java/com/vrudenko/kanban_board/event/ColumnUpdatedEvent.java
- src/main/java/com/vrudenko/kanban_board/event/SubtaskUpdatedEvent.java
- src/main/java/com/vrudenko/kanban_board/event/TaskDeletedEvent.java
- src/main/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapper.java
- src/main/java/com/vrudenko/kanban_board/exception/AppDuplicateResourceException.java
- src/main/java/com/vrudenko/kanban_board/mapper/ActivityLogMapper.java
- src/main/java/com/vrudenko/kanban_board/mapper/BoardFullMapper.java
- src/main/java/com/vrudenko/kanban_board/mapper/ColumnFullMapper.java
- src/main/java/com/vrudenko/kanban_board/mapper/TaskFullMapper.java
- src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java
- src/main/java/com/vrudenko/kanban_board/repository/ActivityLogRepository.java
- src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java
- src/main/java/com/vrudenko/kanban_board/repository/ColumnRepository.java
- src/main/java/com/vrudenko/kanban_board/repository/TaskRepository.java
- src/main/java/com/vrudenko/kanban_board/security/NonprodResetSecurityConfiguration.java
- src/main/java/com/vrudenko/kanban_board/security/ProblemDetailAuthenticationEntryPoint.java
- src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
- src/main/java/com/vrudenko/kanban_board/service/ActivityLogService.java
- src/main/java/com/vrudenko/kanban_board/service/BoardService.java
- src/main/java/com/vrudenko/kanban_board/service/ColumnService.java
- src/main/java/com/vrudenko/kanban_board/service/ResetService.java
- src/main/java/com/vrudenko/kanban_board/service/ResetTruncateService.java
- src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java
- src/main/java/com/vrudenko/kanban_board/service/TaskService.java
- src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogAvroDeadLetterE2ETest.java
- src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogAvroRoundTripE2ETest.java
- src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogCleanupIsolationTest.java
- src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java
- src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogDeadLetterE2ETest.java
- src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogIdempotencyE2ETest.java
- src/test/java/com/vrudenko/kanban_board/activitylog/HistoricalActivityEventReconstructor.java
- src/test/java/com/vrudenko/kanban_board/activitylog/HistoricalActivityEventReconstructorTest.java
- src/test/java/com/vrudenko/kanban_board/activitylog/HistoricalSchemaRehearsalE2ETest.java
- src/test/java/com/vrudenko/kanban_board/activitylog/SchemaCompatibilityE2ETest.java
- src/test/java/com/vrudenko/kanban_board/activitylog/SchemaRegistryOutageE2ETest.java
- src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java
- src/test/java/com/vrudenko/kanban_board/architecture/TestPlacementArchTest.java
- src/test/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizerTest.java
- src/test/java/com/vrudenko/kanban_board/config/CorsConfigTest.java
- src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java
- src/test/java/com/vrudenko/kanban_board/config/FlywaySchemaProvenanceTest.java
- src/test/java/com/vrudenko/kanban_board/config/OpenApiDocsTest.java
- src/test/java/com/vrudenko/kanban_board/config/OpenApiParameterCompletenessTest.java
- src/test/java/com/vrudenko/kanban_board/config/PasswordEncoderStrengthTest.java
- src/test/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizerTest.java
- src/test/java/com/vrudenko/kanban_board/config/RandFlakeGeneratorTest.java
- src/test/java/com/vrudenko/kanban_board/controller/BoardFullReadTest.java
- src/test/java/com/vrudenko/kanban_board/controller/ThemePersistenceTest.java
- src/test/java/com/vrudenko/kanban_board/dto/BoardIdTest.java
- src/test/java/com/vrudenko/kanban_board/dto/ColumnColorTest.java
- src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java
- src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java
- src/test/java/com/vrudenko/kanban_board/e2e/activity/ActivityReadTest.java
- src/test/java/com/vrudenko/kanban_board/e2e/board/BoardCreationE2ETest.java
- src/test/java/com/vrudenko/kanban_board/e2e/board/BoardLockingTest.java
- src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetControllerE2ETest.java
- src/test/java/com/vrudenko/kanban_board/e2e/reset/ResetServiceE2ETest.java
- src/test/java/com/vrudenko/kanban_board/e2e/subtask/SubtaskLockingTest.java
- src/test/java/com/vrudenko/kanban_board/e2e/task/TaskMoveTest.java
- src/test/java/com/vrudenko/kanban_board/event/ActivityEventPublicationTest.java
- src/test/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapperTest.java
- src/test/java/com/vrudenko/kanban_board/handler/ErrorEnvelopeConsistencyTest.java
- src/test/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandlerTest.java
- src/test/java/com/vrudenko/kanban_board/security/ActuatorHealthE2ETest.java
- src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java
- src/test/java/com/vrudenko/kanban_board/security/AuthorizationGatingTest.java
- src/test/java/com/vrudenko/kanban_board/security/ConcurrentSigninCeilingE2ETest.java
- src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java
- src/test/java/com/vrudenko/kanban_board/security/ResetEndpointProfileGatingTest.java
- src/test/java/com/vrudenko/kanban_board/security/SessionCookieAttributesE2ETest.java
- src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java
- src/test/java/com/vrudenko/kanban_board/service/ColumnServiceTest.java
- src/test/java/com/vrudenko/kanban_board/support/containers/AbstractKafkaContainerTest.java
- src/test/java/com/vrudenko/kanban_board/support/containers/AbstractPostgresContainerTest.java
- src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppMockMvcTest.java
- src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppTest.java
- src/test/java/com/vrudenko/kanban_board/support/listeners/RecordingActivityEventListener.java

## Cleanup

`docker ps -a` before and after: no container was created by this run (the 12 pre-existing containers are unchanged; none are Testcontainers leftovers from my runs). `./gradlew --stop` run at the end: no daemons remain. Scratch files are under `/tmp` only.

## Self-Check: PASSED

- sweep script, linter, selftest, build.gradle, docs: present on HEAD.
- Commits a4a47e1 and 629a0d6 are ancestors of HEAD (`git log` shows both on top of 1f34861).
- `commits: 2` measured with `git rev-list --count 1f34861..HEAD`.
- Self-check covers files and commits, not the open item above, which is why the status stays `incomplete`.
