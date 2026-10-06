---
phase: quick-261006-kpj
plan: 01
subsystem: docs, architecture-tests
tags: [archunit, code-style, review-rubric, docs]
status: complete
requires: []
provides:
  - MainCodeStyleArchTest (CODE_STYLE rules 6, 7, 12)
  - TestCodeStyleArchTest (CODE_STYLE rules 3, 4, 5)
  - docs/CODE_REVIEW_RUBRIC.md
  - docs/CODE_STYLE.md as a numbered index
affects: [docs/ARCHITECTURE.md, .claude/CLAUDE.md, README.md, docs/learning]
key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java
    - src/test/java/com/vrudenko/kanban_board/architecture/TestCodeStyleArchTest.java
    - docs/CODE_REVIEW_RUBRIC.md
  modified:
    - docs/CODE_STYLE.md
    - docs/ARCHITECTURE.md
    - build.gradle
    - .claude/CLAUDE.md
    - README.md
    - docs/SESSION_LESSONS.md
    - docs/learning/00-README.md
    - docs/learning/01-domain-model-and-schema.md
    - docs/learning/05-api-layer.md
    - docs/learning/08-testing-strategy.md
    - docs/learning/09-build-quality-and-ci.md
decisions:
  - "Rules with no tool-checkable shape stay in the rubric, moved verbatim, each with a deciding test"
  - "Allow-lists fail closed: UpdateThemeRequestDTO and UpdateColumnRequestDTO.name are exempt by exact name"
metrics:
  duration: 27 min
  completed: 2026-10-06
actuals:
  tokens: 15200
  tasks: 3
  commits: 3
plan_head_before: 2fb57109167b46b747c30d9c6c4ea796f0d9be12
plan_head_after: fc86152b9705a9507761269a0ee7a8df5a54c322
---

# Phase quick-261006-kpj Plan 01: Split CODE_STYLE.md and move enforcement out of prose Summary

docs/CODE_STYLE.md is now a 9,345-byte numbered index that points each of its 14 rules at an ArchUnit test, a linter or a rubric entry, and nine new ArchUnit rules fail the build on rules 3, 4, 5, 6, 7 and 12.

## Size report

| | Bytes | Token estimate (bytes / 4) |
|---|---|---|
| docs/CODE_STYLE.md before (2fb5710) | 43,974 | about 10,994 |
| docs/CODE_STYLE.md after | 9,345 | about 2,336 |
| docs/CODE_REVIEW_RUBRIC.md (new) | 12,888 | about 3,222 |

The token estimate is characters divided by four, the same scale `estimateTokens` uses. It is not a harness token count. The `actuals.tokens` figure (15,200) is the same method over the 60,704 characters of added lines in `git diff 2fb5710 HEAD`.

The file is byte-identical to the plan's Target content (checked by `cmp`). All 14 `### N.` headings and `## Adding a rule` are identical to 2fb5710.

## Commits

| Task | Commit | What |
|---|---|---|
| 1 (tracer) | 13a79b1 | MainCodeStyleArchTest, rubric opening plus entries 2 and 12, CODE_STYLE sections 2, 6, 7, 12, the two check scripts |
| 2 | 99c3a1b | TestCodeStyleArchTest, rubric entries 1, 4, 5, 8, 9, 13, 14, ARCHITECTURE section and edits A1-A5, build.gradle phrase, full CODE_STYLE rewrite |
| 3 | fc86152 | Citation edits C1-C8, rubric pointer in .claude/CLAUDE.md |

All three went through the unmodified pre-commit hook (gitleaks, spotlessCheck, fastTest). No `--no-verify`. `commits: 3` is measured from the ledger base (`git rev-list --count 2fb5710..HEAD`). Nothing was merged to main.

## ArchUnit rules: RED and GREEN directions actually run

RED means a scratch fixture with a deliberate violation was in the tree and the architecture run was executed. GREEN means the fixture was deleted with `trash-put` and the same run was repeated on the real tree. `arch_results.py` read Gradle's fastTest XML for both directions. In both RED runs the only violations reported were in Scratch classes, and the other test class's rules stayed green because it imports a different class set.

| Rule (class) | CODE_STYLE rule | RED: first violation line | GREEN |
|---|---|---|---|
| optionals_must_be_unwrapped_with_an_isEmpty_guard_not_orElseThrow (Main) | 7 | `Method <...dto.ScratchStyleViolations.unwrap()> calls method <java.util.Optional.orElseThrow()> in (ScratchStyleViolations.java:21)` | pass |
| update_request_dtos_must_carry_the_partial_update_shape (Main) | 6 | `Class <...ScratchStyleViolations$UpdateScratchRequestDTO> has 2 optional fields but no @AssertTrue method named atLeastOneFieldPopulated()` (the same class also reported the missing version and missing JsonInclude) | pass |
| save_and_response_dtos_must_not_carry_json_include (Main) | 6 | `Class <...ScratchStyleViolations$SaveScratchRequestDTO> is annotated with @JsonInclude` | pass |
| update_request_dto_fields_must_not_carry_not_blank (Main) | 12 | `Field <...ScratchStyleViolations$UpdateScratchRequestDTO.third> is annotated with @NotBlank` | pass |
| tests_must_not_use_mockito_or_mock_beans (Test) | 4 | `Method <...ScratchTestStyleViolations.mockUser()> calls method <org.mockito.Mockito.mock(java.lang.Class)> in (ScratchTestStyleViolations.java:21)` | pass |
| tests_must_not_use_spring_boot_test_slices (Test) | 4 | `Class <...ScratchTestStyleViolations$ScratchSlice> is meta-annotated with @OverrideAutoConfiguration` | pass |
| tests_must_assert_with_assertj_and_capture_exceptions_first (Test) | 3 | `Method <...junitAssertUser()> calls method <org.junit.jupiter.api.Assertions.assertThrows(...)> in (ScratchTestStyleViolations.java:26)`, and a second line for `assertThatThrownBy` at :34 | pass |
| assertj_assertions_must_not_be_statically_imported (Test, source scan) | 3 | `Expecting empty but was: ["src/test/java/.../ScratchTestStyleViolations.java:3"]` | pass |
| tests_must_not_use_display_name (Test) | 5 | `Method <...displayNameUser()> is annotated with <org.junit.jupiter.api.DisplayName>` | pass |

GREEN also held for `domain_services_must_load_through_ownership_verified_findById` and `test_classes_must_not_reside_directly_in_the_root_package` (11 rules green in the final architecture run). The real tree needed no fix: no violation turned up that the plan had not anticipated, and no rule was weakened.

## Ledger

`ledger_check.py all post`, run on the final tree: 97 rows, 94 probes hit, 3 rows with no destination (K18, K54, K79), 0 misses. Each task ran its `pre` check before cutting: Task 1 `2,6,7,12 pre` (20 hit, no misses), Task 2 `all pre` (81 hit, no misses).

## Citation check

Planning-time script from the plan's verify block, run on the final tree: 117 rule-number citations, 0 bad numbers, 0 unresolved anchors, 0 `#L` line anchors into CODE_STYLE.md. Before (same regex over 2fb5710): 102 rule-number citations and 13 `#L` anchors. The plan's figure of 91 counted lines, not citations, so the numbers differ by method, not by loss. The rise to 117 comes from the new `docs/CODE_STYLE.md rule N` text in the two ArchUnit classes' because() messages and the rubric.

## Final gates (on the committed tree, before the last commit's hook)

- `./gradlew test spotlessCheck`: BUILD SUCCESSFUL. `test` ran 551 test cases in 220 classes, 0 failures, 0 errors, 0 skipped. `jacocoTestCoverageVerification` passed. fastTest during the commits: 480 tests in 178 classes, 0 failures.
- `python3 scripts/verify-comments.py check`: OK, 302 files, 1189 comment blocks.
- `python3 scripts/verify-instruction-budget.py`: OK, .claude/CLAUDE.md is 4,656 bytes against the 5,100 ceiling.

## Placement decisions the plan overturned (carried through as written)

| Rule | First cut | What happened |
|---|---|---|
| 2 findById | new ArchUnit rule | Already enforced by LayeringArchTest; the doc had filed that note under rule 7. Stub now names it; downstream-id and findByX judgement went to the rubric |
| 7 orElseThrow | already enforced | Not enforced; new rule in MainCodeStyleArchTest |
| 8 automated setup | runbook | Review criterion, so rubric rule 8 with a pointer to LOCAL_DEV.md |
| "Running the Kafka Testcontainers tests on Windows" | move to runbook | Not a section; an anti-example inside a fence, nothing moved |

## Deviations from Plan

None to production code or to the specified rules. Minor execution notes:

1. **[Fixture only] ErrorProne rejected the scratch fixtures.** `Optional.of("x").orElseThrow()` tripped OptionalOfRedundantMethod, and `assertThat(1).isEqualTo(1)` tripped SelfAssertion. I changed the fixtures to `Optional.ofNullable` and `isPositive()` and re-ran. Neither fixture was committed.
2. **A3 became its own paragraph** after the "says so in its own Javadoc." paragraph, because the plan says "after the paragraph".
3. **ARCHITECTURE footer** was rewrapped to keep lines under about 100 characters after the A5 text change. The words follow A5.
4. **Rubric rule 14 list** uses `- ` bullets with the original `1.`, `3.`, `6.` numerals removed, since the plan asked for a "three-item list" and the old numbers would read as a gap.
5. **A background-commit wrapper** reported "completed (exit code 0)" while the hook was still running. I waited on the log's own `commit exit` line before touching the tree each time.
6. **One blocked command:** `rm -f` on a temp log was refused by the machine's hook. Nothing ran; I used a fresh log name instead.

## Found but not fixed

- ARCHITECTURE.md says "382 test methods"; the plan measured 488 test-method annotations, and the full `./gradlew test` run reported 551 test cases (which includes parameterized and ArchUnit cases). The count is stale by any measure.
- `LogoutHandler` uses `HttpServletResponse.SC_OK`, a named constant, so it is not a rule 1 violation.

## Left stale or out of scope

- docs/wiki/** and docs/raw/** copies still link to the old CODE_STYLE layout (out of scope per the plan).
- `.claude/worktrees/**` (two other checkouts exist there) were not touched.
- Rules 1, 2 (downstream half), 4 (fakes), 5 (naming), 8, 9, 12 (service half), 13 (right subpackage) and 14 (whether a comment should exist) are review-only by design. They are rubric-held, not tool-held.
- The rule 5 naming gate was deliberately not built: 87 of 488 existing test methods in 23 classes use other name shapes.

## Follow-up for the user

Point the global fan-out-review brief (`~/.claude/REVIEW_BRIEF.md`, outside this task's scope) at `docs/CODE_REVIEW_RUBRIC.md`, so a three-way review of a Java diff grades against the deciding tests. Until then, the pointer in `.claude/CLAUDE.md` line 18 covers only agents that read the project instructions.

## Environment notes

- Leftover containers: no container from this run remains. `docker ps -a --filter label=org.testcontainers` shows three exited Testcontainers-labelled containers that predate this run (a postgres:16 exited 17 hours ago, a redpanda exited 10 days ago, a postgres:16 exited 10 days ago). I did not start them and did not remove them.
- Gradle daemon stopped at the end (`./gradlew --stop`: "No Gradle daemons are running").
- No Gradle run was killed with "stop command received".
- Untracked `.serena/` and `docs/learning/kafka-architecture-overview.md` were left alone. PLAN.md, this SUMMARY and STATE.md are uncommitted for the orchestrator.

## Known Stubs

None.

## Threat Flags

None. No new endpoint, auth path or schema change; the only runtime-adjacent change is a comment in build.gradle.

## Self-Check: PASSED

- Files exist: MainCodeStyleArchTest.java, TestCodeStyleArchTest.java, docs/CODE_REVIEW_RUBRIC.md, docs/CODE_STYLE.md (checked by `cmp` against the target).
- Commits 13a79b1, 99c3a1b, fc86152 are ancestors of HEAD.
- Scratch fixtures `ScratchStyleViolations.java` and `ScratchTestStyleViolations.java` are absent.
