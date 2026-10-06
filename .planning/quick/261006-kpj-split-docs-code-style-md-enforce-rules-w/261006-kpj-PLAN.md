---
phase: quick-261006-kpj
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java
  - src/test/java/com/vrudenko/kanban_board/architecture/TestCodeStyleArchTest.java
  - docs/CODE_STYLE.md
  - docs/CODE_REVIEW_RUBRIC.md
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
  - .planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w/ledger_check.py
  - .planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w/arch_results.py
autonomous: true
requirements: [261006-kpj]

estimate:
  tokens: 205000
  raw_tokens: 205000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "docs/CODE_STYLE.md drops from 43,974 bytes to the Target content below (9,3xx bytes), byte-identical to it, with all 14 `### N.` headings and `## Adding a rule` byte-identical to commit 2fb5710. The SUMMARY reports both sizes and bytes/4 token estimates (about 10,994 before, about 2,340 after)"
    - "Every removed fact is a ledger row. The final `all post` ledger check reports 97 rows, 94 probes hit, 3 rows with no destination, zero misses. Each task ran its `pre` check before cutting"
    - "Each new ArchUnit rule (4 in MainCodeStyleArchTest, 5 in TestCodeStyleArchTest) was seen FAILING on a scratch fixture (RED) and PASSING on the real tree with the fixture deleted (GREEN). The SUMMARY lists both directions per rule, with the first violation line of each RED failure"
    - "Both new classes carry no tag, reuse LayeringArchTest's and TestPlacementArchTest's exact @AnalyzeClasses keys, and run in fastTest"
    - "Every `CODE_STYLE rule N` citation in src, scripts, docs (wiki and raw excluded), .githooks, .github, .claude/CLAUDE.md, build.gradle and README resolves to an existing heading. Every `CODE_STYLE.md#anchor` resolves to a heading slug, and no `#L` line anchor into CODE_STYLE.md remains"
    - "docs/CODE_REVIEW_RUBRIC.md holds rules 1, 2, 4, 5, 8, 9, 12, 13 and 14, each with a Deciding test. Reviewers reach it from .claude/CLAUDE.md (still within the 5,100-byte budget), the CODE_STYLE.md header, README and the ARCHITECTURE footer"
    - "On the final tree `./gradlew test` and `./gradlew spotlessCheck` pass, and so do the comment lint and the instruction-budget gate"
  artifacts:
    - path: src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java
      provides: "ArchUnit rules for CODE_STYLE rules 6 (two rules), 7 and 12 over src/main"
    - path: src/test/java/com/vrudenko/kanban_board/architecture/TestCodeStyleArchTest.java
      provides: "ArchUnit rules for CODE_STYLE rules 3 (bytecode half plus a test-source scan), 4 (two rules) and 5"
    - path: docs/CODE_STYLE.md
      provides: "Short numbered index: one statement plus Enforced/Reviewed pointer per rule"
    - path: docs/CODE_REVIEW_RUBRIC.md
      provides: "The judgement rules, moved verbatim, each with a Deciding test"
    - path: docs/ARCHITECTURE.md
      provides: "The `### Writing a new test: package, tier and base class` section, rule 4's test-architecture paragraphs moved verbatim"
  key_links:
    - from: docs/CODE_STYLE.md
      to: src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java
      via: "rules 6, 7, 12 name the class; each @ArchTest because() cites `docs/CODE_STYLE.md rule N`"
    - from: docs/CODE_STYLE.md
      to: docs/CODE_REVIEW_RUBRIC.md
      via: "the header link plus `Reviewed: rubric rule N` lines under identical headings"
    - from: .claude/CLAUDE.md
      to: docs/CODE_REVIEW_RUBRIC.md
      via: "the pointer bullet, line 18"
    - from: scripts/verify-comments.py
      to: docs/CODE_STYLE.md
      via: "POLICY_REF still names rule 14, whose heading is unchanged"
---

# Quick task 261006-kpj: split docs/CODE_STYLE.md and move enforcement out of prose

<objective>
Turn docs/CODE_STYLE.md (43,974 bytes, 14 rules) into a short numbered index. Each tool-checkable
rule becomes an ArchUnit test or points at the linter that already holds it. Each judgement rule
moves, word for word, into a new review rubric with a deciding test. Material that is not style
moves to docs/ARCHITECTURE.md.

Purpose: prose drifts and checks do not. Three of the file's own examples have already gone stale
(rules 6, 8 and 9), and one enforcement note sits under the wrong rule (rule 7 describes rule 2's
check). The always-loaded pointer in .claude/CLAUDE.md sends every Java change through this file,
so its size is paid on every such change.

Output, in three commits on the current branch (quick/split-code-style-doc):
- `MainCodeStyleArchTest` and `TestCodeStyleArchTest`, each proven both ways.
- `docs/CODE_REVIEW_RUBRIC.md`.
- A `### Writing a new test` section in docs/ARCHITECTURE.md.
- The rewritten docs/CODE_STYLE.md.
- Repointed citations.
- A probe-checked ledger of every removed fact.

Constraints honored throughout:
- Every rule keeps its number and its heading byte-for-byte, so every anchor still resolves.
- New Java comments are plain text (rule 14): no HTML tags, no inline Javadoc tags, no planning
  ids.
- Never commit with `--no-verify`.
- Stage by explicit path only. Leave the user's untracked `.serena/` and
  `docs/learning/kafka-architecture-overview.md` alone.

Out of scope:
- Production code.
- The user's global ~/.claude files, including the fan-out-review REVIEW_BRIEF. The SUMMARY
  suggests pointing that brief at the rubric.
- docs/wiki/** and docs/raw/**. The wiki copies link to their own copy of the file, and CLAUDE.md
  says to edit the originals.
- `.claude/worktrees/**`, which is another checkout.
- `.planning/**` beyond this task's own artifacts.
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.claude/CLAUDE.md
@src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java
@src/test/java/com/vrudenko/kanban_board/architecture/TestPlacementArchTest.java

Read on demand, not whole:
- `git show 2fb5710:docs/CODE_STYLE.md`: the source for every verbatim move. Line numbers below
  refer to this commit, so they stay valid after Task 1 edits the working file.
- docs/ARCHITECTURE.md: `## Testing` (lines 289-328) and the footer (line 357).
- docs/SESSION_LESSONS.md: read before starting; it holds the git-hygiene lessons for this repo.
- scripts/verify-comments.py: the docstring only, for the comment rules the new Java must pass.

Executor interface notes:
- LayeringArchTest is the idiom source: `callMethodWhere(target(...).and(target(owner(...))))`, a
  custom `ArchCondition` with `ConditionEvents` plus `SimpleConditionEvent.violated`, and
  `DescribedPredicate.describe`.
- compileTestJava promotes five ErrorProne checks to ERROR: FutureReturnValueIgnored,
  StringCaseLocaleUsage, MissingOverride, NotJavadoc and DefaultCharset. So give each `check`
  override `@Override`, and read files with an explicit UTF-8 charset.
- ErrorProne's CheckReturnValue is an error by default, so chain every AssertJ call, fixtures
  included.
</context>

## Measurements and placement decisions (planning time, HEAD 2fb5710)

I read every rule body and checked the orchestrator's first cut against the tree. **Overturned**
marks a placement I changed.

| Rule | First cut | Verdict, with the evidence |
|---|---|---|
| 1 enums | judgement | Confirmed. An ArchUnit floor would flag correct code: it sees the `int` overload a call picks, and `GlobalExceptionHandler` passes the enum-derived `problem.getStatus()` to `ResponseEntity.status(int)` 14 times. Rubric. |
| 2 findById | new ArchUnit | **Overturned: already enforced** by `LayeringArchTest.domain_services_must_load_through_ownership_verified_findById`. The doc never said so under rule 2: that note sits at the end of rule 7. The rest (downstream id, `findByX`) is judgement and goes to the rubric. |
| 3 AssertJ | new ArchUnit | Confirmed, split in two. The bytecode half covers JUnit `Assertions`, `assertThatThrownBy` and `assertThatExceptionOfType`. Bytecode cannot see a static import (it compiles the same as a qualified call), so that half is an `@ArchTest` method that scans the test sources. Today: 0 violations, 58 `catchException`; `catchThrowable` (1) and `assertThatCode` (2) are capture-first or no-throw, so they stay allowed. |
| 4 no mocks | new ArchUnit | Confirmed for the ban: 0 Mockito, mock-bean or slice uses. About 9 KB of the rule is test-architecture reference (purpose package, DTO tier, base class, the `.with(user())` ceiling, tag gating), not style, so it moves to ARCHITECTURE. The Why sentence and the countQueries rule stay, because docs/learning/04 and 08 quote them. |
| 5 tests | maybe ArchUnit | Partial. The `@DisplayName` ban goes to ArchUnit (0 uses). The naming rule stays prose: 87 of 488 test methods in 23 classes use other shapes (`whenX_thenY`, `should_x_when_y`, `<action>_with<Context>_<outcome>`), so a gate needs a 23-class allow-list or an 87-method rename, neither trivial. `@Nested` grouping and AAA comments are judgement, and comments are absent from bytecode. Rubric. |
| 6 Update DTO | new ArchUnit | Confirmed. One deviation, `UpdateThemeRequestDTO`, is documented in its Javadoc Decisions and becomes a named exemption. The sentence "@JsonInclude presence is exactly what marks a partial-update DTO" is wrong: `MoveTaskRequestDTO` and `ReorderColumnRequestDTO` carry it too. Dropped. |
| 7 orElseThrow | already enforced | **Overturned: not enforced.** The paragraph under rule 7 describes rule 2's check. 0 uses in src/main, so a new ArchUnit rule. |
| 8 automated setup | runbook | **Partly overturned.** The rule is a review criterion (does this change need a manual host step?), so it goes to the rubric. Its runbook counterpart already exists in docs/LOCAL_DEV.md "Testcontainers-based tests on Windows". Its Java example is stale: the pin moved to `AbstractPostgresContainerTest`. |
| "Running the Kafka Testcontainers tests on Windows" | move to runbook | **Overturned: not a section.** It is rule 8's Discouraged example inside a markdown fence, and the heading grep matched it falsely. It shows the forbidden form, so there is nothing to move. |
| 9 var | judgement | Confirmed. `var` does not survive into bytecode. 127 of 154 `var` declarations in src/main have a method-call right side, so review grades changed lines only. The example uses `ulid-creator`, which is unused. |
| 10 imports | spotless | Confirmed. build.gradle's comment already has the order, the `'\\#'` escaping and the javax note; one phrase adds "first-party deliberately third". The rule says the hook *runs* spotlessApply. **Wrong**: it runs spotlessCheck, and ARCHITECTURE repeats the claim. |
| 11 @Validated | LayeringArchTest | Confirmed. Its because() carries the whole Why. |
| 12 OptionalNotBlank | new ArchUnit | Confirmed for the negative half: no `@NotBlank` on an `Update*RequestDTO` field, with `UpdateColumnRequestDTO.name` exempt per its Javadoc. The positive half is unenforceable, because `UpdateTaskRequestDTO.description` is optional and blank is legitimate there. The service-guard paragraph goes to the rubric. The site list is a cache. |
| 13 test placement | TestPlacementArchTest | Confirmed for the root package. The right subpackage and `e2e/<entity>/` are rubric. The subpackage list is a stale cache: it omits `event/` and `e2e/reset/`. |
| 14 comments | verify-comments.py | Confirmed as split. Mechanical items stay in the stub, because the linter's POLICY_REF prints "docs/CODE_STYLE.md rule 14". Items 1, 3 and 6 go to the rubric. |

## Approaches considered (CLAUDE.md directive)

| Approach | Pros / Cons | Why picked |
|---|---|---|
| **Chosen: tool-first split behind a numbered index.** ArchUnit plus one source scan for what a tool can see, the rubric for judgement, ARCHITECTURE for test architecture. CODE_STYLE.md stays as stubs with byte-identical headings. | + 91 rule-number citations and 9 anchored links keep resolving. + Each rule has one source of truth: the because() text or the rubric. + No new tool. − Two files to read for a judgement rule. − The rule-3 source scan depends on the working directory. | Meets every constraint with the dependencies the build already has (ArchUnit 1.4.2). |
| Alt A: add Checkstyle or PMD for source-level rules (static imports, naming regex, var) | + Sees source text that bytecode cannot. − A new plugin, config, pinned version and `gradle/verification-metadata.xml` entries. − Naming still needs a 23-class suppression list. − "Obvious type" for `var` cannot be expressed. Net gain over ArchUnit plus the scan: one rule. | Rejected: the cost outweighs one rule's worth of coverage. |
| Alt B: retire CODE_STYLE.md and put each rule's text in its enforcer or the rubric | + Nothing left to drift. − Breaks 91 citations, 9 anchors and verify-comments.py's POLICY_REF. − Loses the one page a writer reads before coding. | Rejected: it violates the stable-number constraint. |

Non-obvious trade-offs:
- **Import cost.** ArchUnit caches the imported class graph per `@AnalyzeClasses` key (packages
  plus import options). The new classes copy the two existing keys exactly, so fastTest pays
  only rule evaluation, which takes milliseconds. A different key adds a full re-import to every
  pre-commit run.
- **Self-reference trap.** TestCodeStyleArchTest imports the test classes, itself included. A
  banned type written as a class literal, such as `belongToAnyOf(SomeBanned.class)`, is itself a
  dependency, so the rule would flag its own class. Banned types are therefore named by string.
- **Allow-lists fail closed.** Renaming `UpdateThemeRequestDTO` or `UpdateColumnRequestDTO.name`
  drops the exemption and fails the build loudly instead of silently widening it.
- **Optional-field count.** It reads direct `@NotNull`, `@NotBlank` and `@NotEmpty` only. A
  composed annotation that meta-carries one of them would be counted as optional, so the rule
  would demand a cross-check. That fails loudly, not silently.
- **Working directory.** The source scan resolves `src/test/java` against the working directory,
  which Gradle sets to the project directory. From any other directory the zero-files guard fails
  instead of passing.
- **Knowledge loss.** Rewriting a 44 KB document can drop a fact silently. The 97-row ledger runs
  before every cut.

Core data flow, in three sentences: ArchUnit imports the compiled class graph once per
`@AnalyzeClasses` key and evaluates each `@ArchTest` against it, so a missing DTO annotation, an
`Optional.orElseThrow` call or a Mockito dependency in bytecode becomes a failing JUnit test in
fastTest (pre-commit) and test (CI). The one rule-3 fact that bytecode cannot carry, a static
AssertJ import, is read from the test sources by an `@ArchTest` method in the same class.
CODE_STYLE.md keeps each rule's number and points at the test (Enforced) or the rubric
(Reviewed), so a failure message, a citation and a reviewer all land on the same number.

## Citation inventory (constraint 1)

Planning-time grep over src, scripts, docs, .githooks, .github, .claude and build.gradle:
180 lines cite CODE_STYLE, and 91 of them carry a rule number. All 14 numbers and all heading
slugs survive, so only the citations whose *claim* becomes false need an edit.

| Citing location | Rules cited | Disposition |
|---|---|---|
| src/main (service ×5, dto ×6, entity ×2) | 1, 2, 4, 6, 12 | Preserved. BoardId/ColumnColor cite rule 4's one-violation convention, which the rule 4 stub's ARCHITECTURE pointer names. |
| src/test (20 files, LayeringArchTest and TestPlacementArchTest included) | 2, 4, 8, 13 | Preserved |
| scripts/verify-comments.py (POLICY_REF), verify-comments-selftest.py (path as fixture) | 14 | Preserved |
| .githooks/pre-commit, build.gradle | 8, 14 | Preserved |
| .claude/CLAUDE.md lines 18, 33 | file, 14 | Line 18 gains the rubric pointer (C1); line 33 is preserved |
| README.md lines 295-296 | file | Updated (C2) |
| docs/ARCHITECTURE.md line 357 | file | Updated (A5) |
| docs/SESSION_LESSONS.md lines 3, 115 | file, rule shape | Line 3 is preserved; line 115 is updated (C3) |
| docs/LOCAL_DEV.md (6, three as `#8-...` anchors) | 8 | Preserved: heading byte-identical |
| docs/learning/02, 03, 04 | 2, 4, 6 | Preserved. Quoted Why text stays in the stub or the same-numbered rubric entry. |
| docs/learning/00, 01, 05, 08, 09 | 1-14 | Updated where a claim becomes false (C4-C8). This includes 13 `#L` line anchors in 09, which were already stale. |
| docs/plans/backend-modernization/STATUS.md, docs/raw/** | 8 | Preserved |
| docs/wiki/**, .claude/worktrees/**, .planning/** | various | Out of scope (see objective) |
| .github/** | none | Nothing to do |

Heading slugs (for C4, A-edits and the anchor check):
`1-prefer-enums-over-magic-intstring-constants`,
`2-load-entities-through-the-ownership-verified-loader-never-repositoryfindbyid-directly`,
`3-use-assertj-fully-qualified-capture-exceptions-with-catchexception`,
`4-no-mocks--test-against-real-spring-wiring`,
`5-group-by-method-under-test-with-nested-name-shouldoutcome_whencondition-mark-sections-with-aaa-comments`,
`6-updaterequestdto-carries-a-fixed-shape`, `7-unwrap-optional-with-an-isempty-guard-not-orelsethrow`,
`8-test-setup-must-be-fully-automated--never-a-manual-step-for-the-developer`,
`9-use-var-only-when-the-rhs-already-makes-the-type-obvious`,
`10-import-blocks-are-grouped-java--javax--comvrudenko--third-party--static-one-blank-line-between-groups`,
`11-every-restcontroller-carries-class-level-validated`,
`12-an-optional-string-field-that-rejects-blank-carries-optionalnotblank-not-notblank`,
`13-a-new-test-class-belongs-in-a-named-subpackage-of-comvrudenkokanban_board-never-directly-in-the-root-package`,
`14-treat-code-comments-as-a-reviewable-contract-not-a-transcript`, `adding-a-rule`.

## ArchUnit rule specifications

**MainCodeStyleArchTest** (package `com.vrudenko.kanban_board.architecture`, no tag).
- Uses `@AnalyzeClasses(packages = "com.vrudenko.kanban_board", importOptions = ImportOption.DoNotIncludeTests.class)`,
  identical to LayeringArchTest.
- UPDATE_REQUEST_DTOS is a predicate: residing in `com.vrudenko.kanban_board.dto..`, a simple name
  starting with `Update` and ending with `RequestDTO`.

| Field | Rule | Check | Exemption | because() must contain |
|---|---|---|---|---|
| `optionals_must_be_unwrapped_with_an_isEmpty_guard_not_orElseThrow` | 7 | `noClasses()` call a method named `orElseThrow` whose owner is exactly `java.util.Optional` (both overloads) | none | `docs/CODE_STYLE.md rule 7`; `deliberate consistency choice` (orElseThrow is shorter and more idiomatic, but every site uses the guard); the guard is a statement, so a second check `slots in right beside it` as a peer |
| `update_request_dtos_must_carry_the_partial_update_shape` | 6 | `classes()` that UPDATE_REQUEST_DTOS, minus the exemption, should satisfy one custom condition that reports each miss as its own event: (a) class-level `@JsonInclude` whose value is `NON_NULL`; (b) a non-static field `version` of raw type `java.lang.Long` carrying jakarta `@NotNull`; (c) count the optional fields, meaning non-static fields other than `version` that carry none of `@NotNull`, `@NotBlank` or `@NotEmpty` directly. With two or more, it declares a no-arg method `atLeastOneFieldPopulated` carrying `@AssertTrue`; (d) every `@AssertTrue` method is named `atLeastOneFieldPopulated` | `UpdateThemeRequestDTO`, by exact simple name: a whole-value PUT, and UserEntity has no @Version (its Javadoc Decisions) | `docs/CODE_STYLE.md rule 6`; omitting the version `silently disables optimistic locking`; a cross-check named differently per DTO is `unfindable`; `two or more optional fields`; the exemption's reason |
| `save_and_response_dtos_must_not_carry_json_include` | 6 | `noClasses()` in `..dto..` whose simple name is `Save*RequestDTO` or `*ResponseDTO` should be annotated with `JsonInclude` | none | `docs/CODE_STYLE.md rule 6` |
| `update_request_dto_fields_must_not_carry_not_blank` | 12 | `noFields()` declared in UPDATE_REQUEST_DTOS should be annotated with jakarta `NotBlank` | full name `com.vrudenko.kanban_board.dto.column_dto.UpdateColumnRequestDTO.name`: name is that DTO's only mutable property, so it is mandatory by design (its Javadoc) | `docs/CODE_STYLE.md rule 12`; `@NotBlank` also rejects null, so it silently makes an optional field required, an easy mistake with `no compiler signal` |

Javadoc Known holes for MainCodeStyleArchTest:
- The optional-field count reads direct annotations only.
- Rule 12 covers `Update*RequestDTO` fields only. `SignupRequestDTO.displayName` and the
  service-side null guard are review-only (rubric rule 12).
- Rule 7 covers `java.util.Optional`, not `OptionalInt` or `OptionalLong`.

**TestCodeStyleArchTest** (same package, no tag).
- Uses `@AnalyzeClasses(packages = "com.vrudenko.kanban_board")`, identical to
  TestPlacementArchTest, because it must see test classes.
- Name every banned type by fully qualified **string**, never by class literal (see the
  self-reference trap above).

| Field or method | Rule | Check | because() or message must contain |
|---|---|---|---|
| `tests_must_not_use_mockito_or_mock_beans` | 4 | `noClasses()` should depend on classes residing in any of `org.mockito..`, `org.springframework.boot.test.mock.mockito..` or `org.springframework.test.context.bean.override.mockito..`. Annotation use counts as a dependency in ArchUnit 1.x. | `docs/CODE_STYLE.md rule 4`; mocking bypasses the ownership chain and JPA behaviour |
| `tests_must_not_use_spring_boot_test_slices` | 4 | `noClasses()` should be meta-annotated with `org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration`. Verified in the 3.5.16 jars: `@WebMvcTest` and `@DataJpaTest` carry it, while `@SpringBootTest` and `@AutoConfigureMockMvc` do not. | `docs/CODE_STYLE.md rule 4` |
| `tests_must_assert_with_assertj_and_capture_exceptions_first` | 3 | `noClasses()` should depend on `org.junit.jupiter.api.Assertions`, **or** should call a method named `assertThatThrownBy` or `assertThatExceptionOfType` whose owner resides in `org.assertj..` | `docs/CODE_STYLE.md rule 3`; capturing the exception first lets one test `keep asserting follow-up state` |
| `assertj_assertions_must_not_be_statically_imported` (an `@ArchTest static void` method taking `JavaClasses`, which ArchUnit's signature requires and the method does not use) | 3 | Walk `src/test/java` under the working directory with an explicit UTF-8 charset. Collect `path:line` for every line starting with `import static org.assertj.core.api.Assertions.`. Assert the scan saw at least one `.java` file, because an empty scan and a clean scan look the same. Then assert, with qualified AssertJ, that the list is empty. | `docs/CODE_STYLE.md rule 3`; bytecode cannot tell a static import from a qualified call |
| `tests_must_not_use_display_name` | 5 | `noClasses()` should depend on the class with the fully qualified name `org.junit.jupiter.api.DisplayName`. The project's own validation annotation `dto.annotation.DisplayName` must stay allowed. | `docs/CODE_STYLE.md rule 5`; the method name is the display name |

Javadoc Known holes for TestCodeStyleArchTest:
- A hand-written fake is invisible to these rules (rubric rule 4).
- Test naming, `@Nested` grouping and AAA comments are review-only (rubric rule 5).
- The scan depends on the working directory.

## Rubric assembly (docs/CODE_REVIEW_RUBRIC.md)

Open the file with this text, verbatim:

> `# Code review rubric`
>
> Reviewer instructions: when a diff adds or changes Java, tests included, grade the changed lines
> against the rules below. Report each violation as a finding that names the rule number, the file
> and line, and the deciding test it fails. Do not report a passing line, or anything a build gate
> already enforces: [CODE_STYLE.md](CODE_STYLE.md) marks those rules Enforced. Grade only lines
> the diff touches. Much existing code predates these rules (measured counts sit under the rules
> they affect), and rewriting untouched code belongs in a change of its own.
>
> Numbers and headings match CODE_STYLE.md. Rules 3, 6, 7, 10 and 11 are fully enforced by tools
> and have no entry here.

Each entry has the following parts, in numeric order:
- The rule's `### N.` heading, byte-identical to CODE_STYLE.md.
- The listed lines of `git show 2fb5710:docs/CODE_STYLE.md`, copied with their line breaks.
- A paragraph starting `**Deciding test:**` with the text below.
- Where given, a `**Reference:**` line.

| Rule | Copy from 2fb5710 | Deciding test (verbatim) | Reference |
|---|---|---|---|
| 1 | 9, 11, 38 | Is the value drawn from a closed set known at compile time (a status, role, state or direction)? A bare `int` or `String` literal for it is a finding. An enum constant passes, and so does an `int` read from one, such as `problem.getStatus()`. | — |
| 2 | 42, 44, 73 | After the ownership check, does a later repository call take the raw path-variable id instead of the verified entity's id? Does the method load an entity through a repository query other than `findById` (a hand-written `findByX`) without the ownership chain? Either is a finding; LayeringArchTest sees neither. | — |
| 4 | none | Does the test swap a real collaborator for a hand-written fake, stub or subclass that skips the real wiring? That is a finding. A wrapper that forwards every call to the real bean and only observes passes; `CountingPasswordEncoder` in `SigninTimingEqualizationTest` is an example. Does the test class build users, boards, columns, tasks or subtasks that `AbstractAppTest`'s `@BeforeEach` already provides? That is a finding. | Package, tier and base class: ARCHITECTURE.md, "Writing a new test" |
| 5 | 251, 253, 288 | For each new test method, check three things. Is it inside a `@Nested` class named after the method under test? Is it named `should<Outcome>_when<Condition>`, with the `testWithAuthenticatedUser_` prefix in MockMvc controller tests? Is its body split by `// arrange`, `// act`, `// assert`? Each miss is a finding. Measured on 2026-10-06: 87 of 488 existing test methods, in 23 classes, use other name shapes. Grade new methods only, and leave renaming to a change of its own. | — |
| 8 | 375, 377 | Does the change ask a person to do anything by hand before `./gradlew test` works? Flipping a GUI setting, editing an untracked file, exporting a variable nothing in the repository sets, or running a one-off command all count. Any of them is a finding. | docs/LOCAL_DEV.md, "Testcontainers-based tests on Windows" |
| 9 | 409, 411 | For each new or changed `var` declaration: could a reader name the type from the right-hand side alone, without opening another file? If not, that is a finding. `var user = new UserEntity();` passes; `var tasks = taskRepository.findAllByColumnId(columnId);` does not. Measured on 2026-10-06: 127 of 154 `var` declarations in src/main have a method-call right side, so grade changed lines only. | — |
| 12 | 562-565 | Is the field optional (null means "leave unchanged") and blank meaningless for it? Then `@NotBlank` on it is a finding, and it needs `@OptionalNotBlank` beside its composed annotation. A field where blank is legitimate, such as `UpdateTaskRequestDTO.description`, needs neither. Does the consuming service treat a null field as unchanged? If not, that is a finding. | — |
| 13 | 603 through "below the root can still reach it." on 609; then 622-624 | Does the new test's subpackage match the purpose test in ARCHITECTURE.md, "Writing a new test"? A flow test left loose under `e2e/` is a finding, and so is a column test under `e2e/task/`. | — |
| 14 | 660, 662 and 665 as a three-item list; 670 through "review-only judgement." on 672; 715 from "The functional marker" through 716 | Decide in one pass; the first yes wins. Does it restate the code? Delete it. Is it a task? Make it a `TODO:` with a link, or a test. Is it a settled decision a reader would otherwise undo? Keep it as a decision record. Does it name another identifier only to say "same as that one"? Cut the name. Otherwise it is one line, at a different abstraction level than the code. | — |

Task 1 writes the opening plus entries 2 and 12. Task 2 adds the others in order.

## ARCHITECTURE.md edits (Task 2)

- **A1.** Insert a new section after the last bullet of `## Testing`, the one ending "because they
  no longer described a real problem.", and before `## Build quality gates`:
  - The heading `### Writing a new test: package, tier and base class`.
  - The line: "Where a new test file goes. [CODE_STYLE.md](CODE_STYLE.md) rule 4 holds the no-mocks
    rule and points here."
  - Lines 118-224 of 2fb5710 verbatim, with three in-line edits:
    - Line 120: delete the clause ", and a handful still at the root package".
    - Line 135: "(rule below)" becomes "(the base-class paragraph below)".
    - Line 195: the words `see "State Management" above` become `see [AUTH_FLOWS.md](AUTH_FLOWS.md)`.
- **A2.** In the Testing table's Architecture row, "Turns two review-only conventions into build
  failures" becomes "Turns review-only conventions into build failures".
- **A3.** After the paragraph ending "says so in its own Javadoc.", add the sentence:
  "`MainCodeStyleArchTest`, `TestCodeStyleArchTest` and `TestPlacementArchTest` hold the other
  [CODE_STYLE.md](CODE_STYLE.md) rules a tool can check; that file names the enforcer of every rule."
- **A4.** In the Spotless bullet, the claim that the pre-commit hook applies the formatting is
  wrong. It becomes: "enforced by `./gradlew spotlessCheck` in CI and in the pre-commit hook;
  `./gradlew spotlessApply` fixes a failure."
- **A5.** In the footer, "Judgement-level rules a formatter can't check live in
  [CODE_STYLE.md](CODE_STYLE.md);" becomes "Code rules, each naming the test or linter that holds
  it, live in [CODE_STYLE.md](CODE_STYLE.md), and the judgement calls a reviewer applies in
  [CODE_REVIEW_RUBRIC.md](CODE_REVIEW_RUBRIC.md);".

## Citation edits (Task 3)

- **C1 .claude/CLAUDE.md line 18.** Append " Reviewing a Java diff: docs/CODE_REVIEW_RUBRIC.md."
  The result: "- Writing or changing Java code, tests included: docs/CODE_STYLE.md. Reviewing a
  Java diff: docs/CODE_REVIEW_RUBRIC.md."
- **C2 README.md, the documentation table.**
  - The CODE_STYLE row's text becomes "Numbered code rules, each naming the test or linter that
    holds it".
  - Add a row after it: `[docs/CODE_REVIEW_RUBRIC.md](docs/CODE_REVIEW_RUBRIC.md)` | "Judgement
    rules no tool can check, each with the deciding test a reviewer applies".
  - Pad the cells to match the neighbouring rows.
- **C3 docs/SESSION_LESSONS.md line 115.** Replace the two sentences from "This differs from
  `CODE_STYLE.md`'s rule shape" to the end of the paragraph with: "This differs from
  `CODE_STYLE.md`'s rule shape, which points each rule at the test or linter that holds it, or at
  its entry in `CODE_REVIEW_RUBRIC.md`; these lessons describe process, not code, so neither
  applies here."
- **C4 docs/learning/09-build-quality-and-ci.md.**
  - (a) Line 19: "(judgement-level rules)" becomes "(numbered code rules and what holds each)".
    Add the bullet "- [`docs/CODE_REVIEW_RUBRIC.md`](../CODE_REVIEW_RUBRIC.md) (judgement rules a
    reviewer applies)" after it.
  - (b) Line 35, the CI-11 row: "Judgement rules live in `docs/CODE_STYLE.md`; the rules that can
    be checked go to ArchUnit" becomes "Judgement rules live in `docs/CODE_REVIEW_RUBRIC.md`; the
    rules that can be checked go to ArchUnit; `docs/CODE_STYLE.md` indexes both".
  - (c) Line 315: the CI-08 sentence becomes "The reason for the import-group order is recorded in
    the comment above `importOrder` in [`build.gradle`](../../build.gradle), which
    [`docs/CODE_STYLE.md` rule 10](../CODE_STYLE.md#<rule 10 slug>) points to." Also, "The rule
    text exists so that" becomes "The comment exists so that".
  - (d) Lines 373-376: "records 13 rules ... fails `./gradlew test`." becomes "records 14 numbered
    rules. Each rule names the test or linter that enforces it, or its entry in
    [`CODE_REVIEW_RUBRIC.md`](../CODE_REVIEW_RUBRIC.md) when only a reviewer can judge it
    ([`CODE_STYLE.md`, "Adding a rule"](../CODE_STYLE.md#adding-a-rule))."
  - (e) Lines 384-392, the rules table:
    - Every `#Lnn-Lnn` anchor becomes the rule's slug.
    - Set "Enforced by" for row 4 to `TestCodeStyleArchTest`; row 5 to "`TestCodeStyleArchTest`
      (no `@DisplayName`); review for the rest"; row 6 to `MainCodeStyleArchTest`; row 7 to
      `MainCodeStyleArchTest`; row 8 to "Review (rubric rule 8); cited by the hook, gitleaks and
      hooks-path decisions".
    - In row 2, append "(a floor); review for the rest".
    - Add row 3 ("AssertJ fully qualified; capture exceptions with `catchException`" | "A
      captured exception lets one test keep asserting" | `TestCodeStyleArchTest`) and row 12
      ("`@OptionalNotBlank`, not `@NotBlank`, on an optional String" | "`@NotBlank` also rejects
      null, silently making the field required" | "`MainCodeStyleArchTest` for Update DTOs;
      review elsewhere") in numeric position.
  - (f) Line 397: "([rule 13](../CODE_STYLE.md#L607-L616))" becomes "([`CODE_STYLE.md`, "Adding a
    rule"](../CODE_STYLE.md#adding-a-rule))", and "the prose and its example hold it" becomes "the
    rubric's deciding test holds it".
  - (g) Line 404: "([rule 7](../CODE_STYLE.md#L371))" becomes "([rule 2](../CODE_STYLE.md#<rule 2
    slug>))".
- **C5 docs/learning/00-README.md line 347.** Make the same CI-11 row edit as C4(b).
- **C6 docs/learning/08-testing-strategy.md, the item 10 ending "The paragraph is historical."**
  Append: "The 2026-10-06 split of `CODE_STYLE.md` removed that list; `TestPlacementArchTest`
  keeps the count."
- **C7 docs/learning/05-api-layer.md, the bullet "`CODE_STYLE.md` rule 6 example."** Append: "The
  2026-10-06 split removed the example; `MainCodeStyleArchTest` now checks the real classes."
- **C8 docs/learning/01-domain-model-and-schema.md line 1048.** The first cell becomes
  "`docs/CODE_STYLE.md` `var` example (removed 2026-10-06)".

## Target content: docs/CODE_STYLE.md

Task 2 writes this byte-for-byte, with a trailing newline. Task 1 replaces only sections 2, 6, 7
and 12 with their text from here. Rule 7's section ends where rule 8's heading begins, so the
misfiled enforcement paragraph goes with it.

```markdown
# Code Style Guide

Rules for writing Java in this repository, tests included. Code comments, tests and docs cite these
rules as "CODE_STYLE rule N", so every rule keeps its number and heading for good: a retired rule
keeps its heading with a redirect, and its number is never reused. Formatting is not here:
`./gradlew spotlessApply` writes it, and `./gradlew spotlessCheck` (pre-commit hook and CI) rejects
anything else.

Each rule names what holds it:

- **Enforced:** a test or linter fails the build. Its failure message carries the reason, so this
  file keeps one line and the pointer.
- **Reviewed:** a judgement no tool can make. The full rule, its reason and the deciding test a
  reviewer applies are in [CODE_REVIEW_RUBRIC.md](CODE_REVIEW_RUBRIC.md), under the same number
  and heading.

Several rules are both: the tool holds a floor and review holds the rest. The ArchUnit classes live
in `src/test/java/com/vrudenko/kanban_board/architecture/`.

## Rules

### 1. Prefer enums over magic int/String constants

Model a value from a fixed, compile-time-known set as an enum, such as
`org.springframework.http.HttpStatus` for an HTTP status. Reviewed: rubric rule 1. Not enforced,
because ArchUnit sees only the overload a call picks, not whether its `int` came from an enum, and
`GlobalExceptionHandler` correctly passes `problem.getStatus()` to `ResponseEntity.status(int)`.

### 2. Load entities through the ownership-verified loader, never `repository.findById` directly

A domain service resolves every entity through its own `findById(userId, id)`, which delegates to
`ownershipVerifierService.verifyOwnershipOf...`, and builds any later repository call from the
verified entity's id. Why: this is the entire access-control model of the application, and nothing
in the type system enforces it.

- Enforced, as a floor, not a ceiling: `LayeringArchTest.domain_services_must_load_through_ownership_verified_findById`
  fails on a direct `repository.findById` from any service except `OwnershipVerifierService` and
  `UserService`.
- Reviewed: rubric rule 2, for the downstream id and hand-written `findByX` loaders the ArchUnit rule
  cannot see.

### 3. Use AssertJ fully qualified; capture exceptions with `catchException`

Write `Assertions.assertThat(...)` against `import org.assertj.core.api.Assertions;`, and capture an
expected exception with `Assertions.catchException(...)` before asserting on it. Enforced:
`TestCodeStyleArchTest` rejects JUnit's `Assertions`, AssertJ's `assertThatThrownBy` and
`assertThatExceptionOfType`, and any static import from AssertJ's `Assertions` (read from the test
sources, since bytecode cannot tell a static import from a qualified call).

### 4. No mocks — test against real Spring wiring

A test that needs a Spring context is a `@SpringBootTest` extending a `support/fixtures/` base,
against the real context and Testcontainers PostgreSQL.
Why: mocking a repository bypasses exactly the ownership chain and JPA behaviour these tests exist
to catch regressions in, so a fully green, fully mocked test can sit directly on top of a broken
access-control path or a reintroduced N+1 query.

- Enforced: `TestCodeStyleArchTest` rejects Mockito, `@MockBean`, `@MockitoBean`, `@SpyBean` and
  every Spring Boot slice annotation (`@WebMvcTest`, `@DataJpaTest` and the rest).
- Shared fixtures (users, boards, columns, tasks, subtasks) belong in `AbstractAppTest`'s single
  `@BeforeEach`, not re-created inside a test class. `AbstractAppTest.countQueries(Runnable)` is
  the only sanctioned way to assert on query counts.
- Which package, tier and base class a new test takes, the DTO validation tier and its
  one-violation-per-input assertions, the `.with(user())` session ceiling in MockMvc tests, and
  fastTest membership by `@Tag`: [ARCHITECTURE.md, "Writing a new test"](ARCHITECTURE.md#writing-a-new-test-package-tier-and-base-class).
- Reviewed: rubric rule 4, for hand-written fakes.

### 5. Group by method under test with `@Nested`; name `should<Outcome>_when<Condition>`; mark sections with AAA comments

Enforced: `TestCodeStyleArchTest` rejects `@DisplayName`, since the method name is the display
name. Reviewed: rubric rule 5, for the `@Nested` grouping, the two naming dialects and the
`// arrange`, `// act`, `// assert` sections. Naming is not a gate because many existing tests use
other name shapes (counted in the rubric).

### 6. `Update*RequestDTO` carries a fixed shape

An `Update*RequestDTO` carries class-level `@JsonInclude(JsonInclude.Include.NON_NULL)`, a
`@NotNull private Long version`, and, with two or more optional fields, a private `@AssertTrue`
method named `atLeastOneFieldPopulated()`. `Save*RequestDTO` and `*ResponseDTO` classes carry no
`@JsonInclude`. Why: omitting `@NotNull Long version`
silently disables optimistic locking for that entity. Enforced: `MainCodeStyleArchTest`; its one
exemption, `UpdateThemeRequestDTO`, is explained in that class's Javadoc.

### 7. Unwrap `Optional` with an `isEmpty()` guard, not `orElseThrow`

Unwrap a repository `Optional` with an `isEmpty()` guard that throws the matching `App...Exception`,
then a plain `.get()`, as `OwnershipVerifierService.verifyOwnershipOfBoard` does. Enforced across
`src/main` by `MainCodeStyleArchTest`, whose failure message gives the reason.

### 8. Test setup must be fully automated — never a manual step for the developer

`./gradlew test` on a clean checkout is the whole setup. Encode an environment workaround in the
codebase (a system property in test code, a version-controlled config file, a Gradle task), not in
instructions for a person. Reviewed: rubric rule 8; no tool can see a manual step. Worked example:
[LOCAL_DEV.md, "Testcontainers-based tests on Windows"](LOCAL_DEV.md#testcontainers-based-tests-on-windows).

### 9. Use `var` only when the RHS already makes the type obvious

Use `var` for a local only when the right-hand side spells out the type (a constructor, a builder, a
well-known factory); otherwise declare the type. Reviewed: rubric rule 9. Not enforced, because
`var` is gone from the bytecode ArchUnit reads.

### 10. Import blocks are grouped java / javax / com.vrudenko / third-party / static, one blank line between groups

Enforced: `spotlessCheck`, through `importOrder(...)` in build.gradle, whose comment records the
order and why first-party sits third. `./gradlew spotlessApply` rewrites every import block, so
never hand-order imports.

### 11. Every `@RestController` carries class-level `@Validated`

Enforced: `LayeringArchTest.rest_controllers_must_carry_class_level_validated`. Its failure message
gives the reason: the annotation decides whether a body-validation failure returns
`VALIDATION_FAILED` with a per-field `errors` map or `CONSTRAINT_VIOLATION` without one.

### 12. An optional String field that rejects blank carries `@OptionalNotBlank`, not `@NotBlank`

Stack `@OptionalNotBlank` beside the field's composed annotation, and keep `@NotBlank` for mandatory
fields: it also rejects `null`, so on an optional field it silently makes the field required.
Enforced for `Update*RequestDTO` fields by `MainCodeStyleArchTest`, with `UpdateColumnRequestDTO.name`
exempt as its Javadoc explains. Reviewed: rubric rule 12, for optional fields elsewhere and for the
service side of a partial update.

### 13. A new test class belongs in a named subpackage of `com.vrudenko.kanban_board`, never directly in the root package

Enforced: `TestPlacementArchTest`, for the root package; `KanbanBoardApplicationTests` is its one
exemption. Reviewed: rubric rule 13, for whether the subpackage is the right one. The subpackages
are the directories under `src/test/java/com/vrudenko/kanban_board/`.

### 14. Treat code comments as a reviewable contract, not a transcript

Keep a comment only when it records intent, a boundary condition, an external constraint, or a
durable decision that code cannot express. Enforced, for the mechanical half, by
`scripts/verify-comments.py`, whose docstring lists every check:

- Write comments as plain text: a blank comment line is the paragraph break, `- ` starts a list
  item, and an identifier is written by its bare name. Only a block tag such as `@param` starts a
  line with `@`. Javadoc is never rendered here, so markup only adds noise to the source, and
  Spotless keeps Javadoc formatting off so the formatter does not add it back.
- Open a block of four or more prose lines with a one- or two-line summary, then a blank line.
- Put a long decision record behind `Decisions:`, `Known holes:`, or `Why this is the way it is:`.
- Write deferred work as `TODO: <URL | #N | existing repo path> - <what>`.

Reviewed: rubric rule 14, for whether a comment should exist at all.

## Adding a rule

Append a `###` section under `## Rules` with the next integer. First try to enforce it: an ArchUnit
rule in the `architecture` test package, a linter check or a Spotless step, seen to fail on a
violation and pass on the tree, with the reason in its failure message. A rule held only by prose
silently reopens every few sessions: 11 test files drifted into the root package before rule 13 had
a test. Here, write one statement line and "Enforced:". A rule no tool can check gets a rubric entry
(the rule, its reason, the deciding test) and "Reviewed:" here, with the reason it is not enforced.
```

## Removed-fact ledger

Every fact the rewrite removes from docs/CODE_STYLE.md. **Kind** says how the probe was
established:
- **V**: moved verbatim. The probe exists in the 2fb5710 file.
- **E**: already lives in the environment. Verified at planning time.
- **N**: new wording this task writes. The probe is pinned in the specs above.
- **—**: needs no destination, for the reason given.

The rule number in parentheses selects rows for each task's `pre` check. At planning time all 43 V
probes hit the 2fb5710 file and all 30 E probes hit their destinations.

| ID | Removed fact (rule) | Where it lives now, or why it is gone | Kind | Probe |
|---|---|---|---|---|
| K01 | Additive file: rules appended, never rewritten wholesale (header) | Superseded by the stable-number guarantee in the new header | N | `its number is never reused` in `docs/CODE_STYLE.md` |
| K02 | Complements Spotless; formatting is spotlessCheck's job (header) | New header | N | `./gradlew spotlessCheck` in `docs/CODE_STYLE.md` |
| K03 | Enum for a closed compile-time set; HttpStatus is the canonical case (1) | Rubric rule 1, verbatim | V | `HTTP status codes are the canonical case: use` in `docs/CODE_REVIEW_RUBRIC.md` |
| K04 | Why: compiler-checked set, exhaustiveness, one definition (1) | Rubric rule 1, verbatim | V | `switch statements can be checked for exhaustiveness` in `docs/CODE_REVIEW_RUBRIC.md` |
| K05 | Example: HttpStatusCode.valueOf(404) vs HttpStatus.NOT_FOUND (1) | Dropped illustration; the live reference is GlobalExceptionHandler | E | `HttpStatus.NOT_FOUND` in `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java` |
| K06 | GlobalExceptionHandler is the reference; roles, states, sort directions too (1) | Rubric rule 1, verbatim | V | `any closed value set (roles, states, sort directions) should be an enum` in `docs/CODE_REVIEW_RUBRIC.md` |
| K07 | Domain services load via own findById; downstream id from the verified entity (2) | Rubric rule 2, verbatim | V | `must be built from the verified entity's own id` in `docs/CODE_REVIEW_RUBRIC.md` |
| K08 | OwnershipVerifierService and UserService are the only sanctioned direct callers (2) | LayeringArchTest because() | E | `UserService (the identity root, with no owner above it)` in `src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java` |
| K09 | Why: the entire access-control model, unenforced by types (2) | Rubric rule 2, verbatim (the stub keeps the first clause) | V | `this is the entire access-control model of the application` in `docs/CODE_REVIEW_RUBRIC.md` |
| K10 | Example; TaskService.findById/findAllByColumnId are the references (2) | Rubric rule 2 keeps the reference sentence | V | `are the reference implementations of this pattern` in `docs/CODE_REVIEW_RUBRIC.md` |
| K11 | LayeringArchTest enforces the findById ban, a floor not a ceiling (2, misfiled under 7) | Rule 2 stub; LayeringArchTest Javadoc | N | `domain_services_must_load_through_ownership_verified_findById` in `docs/CODE_STYLE.md` |
| K12 | Assertions qualified; catchException; no assertThrows (3) | Rule 3 stub; TestCodeStyleArchTest | N | `docs/CODE_STYLE.md rule 3` in `src/test/java/com/vrudenko/kanban_board/architecture/TestCodeStyleArchTest.java` |
| K13 | Why: a captured exception lets the test keep asserting (3) | TestCodeStyleArchTest because() | N | `keep asserting follow-up state` in `src/test/java/com/vrudenko/kanban_board/architecture/TestCodeStyleArchTest.java` |
| K14 | Example; TaskServiceTest is the reference (3) | Dropped illustration; live usage | E | `Assertions.catchException(` in `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java` |
| K15 | Purpose test: service vs controller vs E2E tests answer different questions (4) | ARCHITECTURE "Writing a new test", verbatim | V | `questions about the same behavior, not three copies of the same test` in `docs/ARCHITECTURE.md` |
| K16 | Worked examples: TaskServiceTest ownership denial, TaskControllerTest 409 (4) | ARCHITECTURE, verbatim | V | `TaskServiceTest.UpdateByIdTest.shouldThrow_whenUserDoesntOwnTheTask()` in `docs/ARCHITECTURE.md` |
| K17 | E2E worked examples: BoardCreationE2ETest.ConcurrentCreate, ActivityLogIdempotencyE2ETest (4) | ARCHITECTURE, verbatim | V | `BoardCreationE2ETest.ConcurrentCreate` in `docs/ARCHITECTURE.md` |
| K18 | "a handful still at the root package" (4) | Stale: TestPlacementArchTest forbids root-package tests | — | — |
| K19 | DTO tier: plain Validator, no Spring, full boundary matrix there (4) | ARCHITECTURE, verbatim | V | `Validation.buildDefaultValidatorFactory()` in `docs/ARCHITECTURE.md` |
| K20 | DTO-tier worked example and the single controller-tier representative (4) | ARCHITECTURE, verbatim | V | `testWithAuthenticatedUser_shouldReturnBadRequest_whenJsonBodyIsEmpty` in `docs/ARCHITECTURE.md` |
| K21 | @ReportAsSingleViolation message trap; assert on constraint types (4) | ARCHITECTURE, verbatim | V | `so its rendered message is byte-identical` in `docs/ARCHITECTURE.md` |
| K22 | Three bases; AbstractPostgresContainerTest ancestor; bare @SpringBootTest gets no datasource (4) | ARCHITECTURE, verbatim | V | `extending none of these three will` in `docs/ARCHITECTURE.md` |
| K23 | AbstractAppMockMvcTest ignores the context-path (4) | ARCHITECTURE, verbatim | V | `a real embedded servlet container does, so tests extending it build routes from the bare` in `docs/ARCHITECTURE.md` |
| K24 | Mockito, @Mock, @MockBean, slice annotations banned (4) | TestCodeStyleArchTest; ARCHITECTURE verbatim | N | `docs/CODE_STYLE.md rule 4` in `src/test/java/com/vrudenko/kanban_board/architecture/TestCodeStyleArchTest.java` |
| K25 | Shared fixtures in AbstractAppTest's single @BeforeEach (4) | Rule 4 stub; ARCHITECTURE verbatim | N | `Shared fixtures (users, boards, columns, tasks, subtasks) belong in` in `docs/CODE_STYLE.md` |
| K26 | countQueries is the only sanctioned query-count assertion, and why (4) | Rule 4 stub; AbstractAppTest Javadoc | N | `the only sanctioned way to assert on query counts` in `docs/CODE_STYLE.md` |
| K27 | .with(user()) authenticates at most two requests per principal per method (4) | ARCHITECTURE, verbatim | V | `may authenticate at most two requests for the same principal per test` in `docs/ARCHITECTURE.md` |
| K28 | Mechanism: filter-composed strategy on an in-memory SessionRegistryImpl (4) | ARCHITECTURE, verbatim | V | `backed by an in-memory` in `docs/ARCHITECTURE.md` |
| K29 | The refusal is a bare sendError, not the ProblemDetail envelope (4) | ARCHITECTURE, verbatim | V | `own failure-handler fingerprint, not this application's RFC` in `docs/ARCHITECTURE.md` |
| K30 | Measured: four calls returned 200, 200, 401, 401 (4) | ARCHITECTURE, verbatim | V | `calls in one test method returned` in `docs/ARCHITECTURE.md` |
| K31 | Per test method, since @BeforeEach mints a fresh user (4) | ARCHITECTURE, verbatim | V | `per invocation never trips it, however many invocations it has` in `docs/ARCHITECTURE.md` |
| K32 | InjectionAttemptTest cookie replay; AuthorizationGatingTest counterpart (4) | ARCHITECTURE, verbatim | V | `is the reference for the cookie-replay` in `docs/ARCHITECTURE.md` |
| K33 | Unreachable in production: signin pre-establishes the session (4) | ARCHITECTURE, verbatim | V | `This is unreachable in production:` in `docs/ARCHITECTURE.md` |
| K34 | "see State Management above" (4) | Dangling since the CLAUDE.md trim; repointed at AUTH_FLOWS (A1) | E | `sessionAuthenticationStrategy` in `docs/AUTH_FLOWS.md` |
| K35 | fastTest membership is by @Tag, not class name (4) | ARCHITECTURE, verbatim | V | `(the pre-commit hook's gate) excludes tests by JUnit 5` in `docs/ARCHITECTURE.md` |
| K36 | Why: mocking bypasses the ownership chain and JPA behaviour (4) | Rule 4 stub, verbatim (quoted by docs/learning/04 and 08) | V | `mocking a repository bypasses exactly the ownership chain` in `docs/CODE_STYLE.md` |
| K37 | Example: MockitoExtension vs AbstractAppTest (4) | Dropped illustration; live base class | E | `protected long countQueries(Runnable action)` in `src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppTest.java` |
| K38 | @Nested per method; should_when names; AAA sections; two dialects (5) | Rubric rule 5, verbatim | V | `Two naming dialects both exist and neither should be normalised into the other` in `docs/CODE_REVIEW_RUBRIC.md` |
| K39 | @DisplayName is not used (5) | TestCodeStyleArchTest; rule 5 stub | N | `docs/CODE_STYLE.md rule 5` in `src/test/java/com/vrudenko/kanban_board/architecture/TestCodeStyleArchTest.java` |
| K40 | Why: the method is the unit of navigation; no drifting display string (5) | Rubric rule 5, verbatim | V | `the method itself the unit of navigation` in `docs/CODE_REVIEW_RUBRIC.md` |
| K41 | Example; TaskServiceTest and BoardControllerTest are the dialect references (5) | Rubric rule 5, verbatim | V | `is the reference for the service dialect` in `docs/CODE_REVIEW_RUBRIC.md` |
| K42 | JsonInclude NON_NULL, @NotNull Long version, atLeastOneFieldPopulated (6) | MainCodeStyleArchTest; rule 6 stub | N | `atLeastOneFieldPopulated` in `src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java` |
| K43 | "@JsonInclude presence is exactly what marks a partial-update DTO" (6) | Wrong: MoveTaskRequestDTO and ReorderColumnRequestDTO carry it too | E | `@JsonInclude(JsonInclude.Include.NON_NULL)` in `src/main/java/com/vrudenko/kanban_board/dto/task_dto/MoveTaskRequestDTO.java` |
| K44 | Why: omitting version silently disables optimistic locking (6) | Rule 6 stub, verbatim (quoted by docs/learning/03 and 05) | V | `silently disables optimistic locking for that entity` in `docs/CODE_STYLE.md` |
| K45 | Why: a differently named cross-check is unfindable (6) | MainCodeStyleArchTest because() | N | `unfindable` in `src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java` |
| K46 | Example UpdateWidgetRequestDTO / UpdateTaskRequestDTO (6) | Dropped: stale (omits @OptionalNotBlank, per docs/learning/05); live class | E | `private boolean atLeastOneFieldPopulated()` in `src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java` |
| K47 | Single-field update DTOs omit the cross-check (6) | MainCodeStyleArchTest's optional-field count | N | `two or more optional fields` in `src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java` |
| K48 | isEmpty() guard then .get(); orElseThrow absent from src/main (7) | MainCodeStyleArchTest; rule 7 stub | N | `docs/CODE_STYLE.md rule 7` in `src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java` |
| K49 | A consistency choice; orElseThrow is shorter and more idiomatic (7) | MainCodeStyleArchTest because() | N | `deliberate consistency choice` in `src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java` |
| K50 | Why: the guard is a statement, so a second check sits beside it (7) | MainCodeStyleArchTest because() | N | `slots in right beside it` in `src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java` |
| K51 | Example; verifyOwnershipOfBoard is the reference (7) | Dropped illustration; live code | E | `if (board.isEmpty()) {` in `src/main/java/com/vrudenko/kanban_board/service/OwnershipVerifierService.java` |
| K52 | Setup is fully automated, configured from the codebase (8) | Rubric rule 8, verbatim | V | `that behavior must be configured from within the codebase itself` in `docs/CODE_REVIEW_RUBRIC.md` |
| K53 | Why: every new environment forgets a manual step (8) | Rubric rule 8, verbatim | V | `a manual setup step is a step every new environment forgets` in `docs/CODE_REVIEW_RUBRIC.md` |
| K54 | Discouraged runbook (expose the Docker daemon on tcp 2375) (8) | An anti-example inside a markdown fence, not a section; nothing to move | — | — |
| K55 | Preferred: api.version 1.44 pin in AbstractKafkaContainerTest (8) | Stale: the pin moved to AbstractPostgresContainerTest | E | `System.setProperty("api.version", "1.44")` in `src/test/java/com/vrudenko/kanban_board/support/containers/AbstractPostgresContainerTest.java` |
| K56 | The fix lives in test code, not a runbook (8) | docs/LOCAL_DEV.md Windows section | E | `this lives in test code specifically so no developer has to discover or repeat a manual workaround` in `docs/LOCAL_DEV.md` |
| K57 | var only when the RHS shows the type (9) | Rubric rule 9, verbatim | V | `is preferred only when the right-hand side already makes the type visually obvious` in `docs/CODE_REVIEW_RUBRIC.md` |
| K58 | var is local-variable-only (9) | Rubric rule 9, verbatim | V | `is a Java local-variable-only feature` in `docs/CODE_REVIEW_RUBRIC.md` |
| K59 | Why: read without IDE type hints (9) | Rubric rule 9, verbatim | V | `read top-to-bottom without an IDE's inline type hints` in `docs/CODE_REVIEW_RUBRIC.md` |
| K60 | Example with UlidCreator.getUlid() (9) | Dropped: ulid-creator is unused | E | `ulid-creator` in `.claude/CLAUDE.md` |
| K61 | Five groups in this order (10) | build.gradle spotless comment | E | `Explicit 5-group import order` in `build.gradle` |
| K62 | importOrder call, '' catch-all, '\\#' escaping (10) | build.gradle spotless comment | E | `Groovy string-escaping` in `build.gradle` |
| K63 | javax matches nothing, kept as future-proofing (10) | build.gradle spotless comment | E | `The javax group matches zero imports today` in `build.gradle` |
| K64 | Never hand-order; spotlessApply rewrites (10) | Rule 10 stub | N | `never hand-order imports` in `docs/CODE_STYLE.md` |
| K65 | "the pre-commit hook runs spotlessApply automatically" (10) | Wrong: the hook runs spotlessCheck (A4 fixes ARCHITECTURE's copy) | E | `./gradlew spotlessCheck < /dev/null` in `.githooks/pre-commit` |
| K66 | Why: first-party third is deliberate, not to be "corrected" (10) | build.gradle spotless comment, one phrase (Task 2) | N | `deliberately ahead of third-party` in `build.gradle` |
| K67 | Example import blocks; TaskControllerTest reference (10) | Dropped: the formatter's output is the source | E | `import com.vrudenko.kanban_board.constant.ApiPaths;` in `src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java` |
| K68 | AuthenticationController in security is in scope (11) | LayeringArchTest because() | E | `AuthenticationController into scope even though it lives in` in `src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java` |
| K69 | Why: VALIDATION_FAILED vs CONSTRAINT_VIOLATION split (11) | LayeringArchTest because() | E | `CONSTRAINT_VIOLATION with no errors map` in `src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java` |
| K70 | Measured: three of seven controllers had @Validated (11) | docs/learning/05 | E | `three of seven controllers had` in `docs/learning/05-api-layer.md` |
| K71 | Example; BoardController is the reference (11) | Dropped illustration; live annotation | E | `@Validated` in `src/main/java/com/vrudenko/kanban_board/controller/BoardController.java` |
| K72 | Stack @OptionalNotBlank; @NotBlank only on mandatory fields (12) | MainCodeStyleArchTest; rule 12 stub | N | `docs/CODE_STYLE.md rule 12` in `src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java` |
| K73 | Current application sites list (12) | Dropped: a cache; grep finds them | E | `@BoardName @OptionalNotBlank private String name;` in `src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java` |
| K74 | UpdateColumnRequestDTO.name is the documented exception (12) | Its Javadoc; MainCodeStyleArchTest exemption | E | `does the job @OptionalNotBlank` in `src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java` |
| K75 | Service treats null as unchanged; BoardService 500 until the fix (12) | Rubric rule 12, verbatim | V | `and a version-only board` in `docs/CODE_REVIEW_RUBRIC.md` |
| K76 | Why: built-ins treat null as valid, so @Pattern composition works (12) | OptionalNotBlank Javadoc | E | `every built-in Bean` in `src/main/java/com/vrudenko/kanban_board/dto/annotation/OptionalNotBlank.java` |
| K77 | Why: an easy mistake with no compiler signal (12) | MainCodeStyleArchTest because() | N | `no compiler signal` in `src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java` |
| K78 | Example; OptionalNotBlank.java is the reference (12) | Dropped illustration; live annotation | E | `public @interface OptionalNotBlank` in `src/main/java/com/vrudenko/kanban_board/dto/annotation/OptionalNotBlank.java` |
| K79 | List of test subpackages (13) | Stale cache: omits event/ and e2e/reset/; the directory tree is the source | — | — |
| K80 | e2e/ is entity-subfoldered (13) | Rubric rule 13, verbatim | V | `is itself entity-subfoldered` in `docs/CODE_REVIEW_RUBRIC.md` |
| K81 | KanbanBoardApplicationTests stays: @SpringBootTest walks up packages (13) | Rubric rule 13, verbatim | V | `walks *up* the package hierarchy` in `docs/CODE_REVIEW_RUBRIC.md` |
| K82 | Why: 11 files drifted before the rule (13) | TestPlacementArchTest because(); the file names are stale (docs/learning/08) | E | `where 11 files` in `src/test/java/com/vrudenko/kanban_board/architecture/TestPlacementArchTest.java` |
| K83 | The ArchTest does not catch a wrong subpackage (13) | TestPlacementArchTest Javadoc | E | `not the correct one` in `src/test/java/com/vrudenko/kanban_board/architecture/TestPlacementArchTest.java` |
| K84 | Example: BoardFullReadTest root vs controller (13) | Dropped illustration; live file | E | `package com.vrudenko.kanban_board.controller;` in `src/test/java/com/vrudenko/kanban_board/controller/BoardFullReadTest.java` |
| K85 | Keep a comment only for intent, boundary, constraint, decision (14) | Rule 14 stub, verbatim | V | `records intent, a boundary condition, an external constraint` in `docs/CODE_STYLE.md` |
| K86 | Item 1: delete a restatement of code (14) | Rubric rule 14, verbatim | V | `Delete a restatement of code.` in `docs/CODE_REVIEW_RUBRIC.md` |
| K87 | Item 3: prefer a test, type, lint rule or ADR (14) | Rubric rule 14, verbatim | V | `Prefer a test, type, lint rule, or ADR when it can enforce the claim.` in `docs/CODE_REVIEW_RUBRIC.md` |
| K88 | Item 6: name another identifier only when load-bearing (14) | Rubric rule 14, verbatim | V | `Name another identifier only when the name carries a contract or deliberate absence.` in `docs/CODE_REVIEW_RUBRIC.md` |
| K89 | Item 2: TODO format (14) | Rule 14 stub, verbatim | V | `existing repo path> - <what>` in `docs/CODE_STYLE.md` |
| K90 | Why: usefulness, abstraction level, mood stay review-only (14) | Rubric rule 14, verbatim | V | `whether a comment is useful, at the right` in `docs/CODE_REVIEW_RUBRIC.md` |
| K91 | FROZEN migrations and ROLLOUT_GATED init scripts are exempt (14) | verify-comments.py docstring | E | `ROLLOUT_GATED` in `scripts/verify-comments.py` |
| K92 | Javadoc is never rendered, so markup is noise (14) | Rule 14 stub, verbatim | V | `Javadoc is never rendered here` in `docs/CODE_STYLE.md` |
| K93 | Spotless keeps Javadoc formatting off (14) | build.gradle spotless comment | E | `formatJavadoc(false)` in `build.gradle` |
| K94 | Discouraged/Preferred comment examples (14) | Dropped: the linter selftest holds the markup cases | E | `{@` in `scripts/verify-comments-selftest.py` |
| K95 | Functional markers are consumed by tooling; keep them (14) | Rubric rule 14 verbatim; verify-comments.py allowlist | V | `The functional marker is consumed by repository tooling` in `docs/CODE_REVIEW_RUBRIC.md` |
| K96 | Rule shape: statement, bolded Why, bad-vs-good example (Adding a rule) | Superseded by the enforce-first shape | N | `First try to enforce it` in `docs/CODE_STYLE.md` |
| K97 | Why: an unwritten convention silently reopens every few sessions (13) | "Adding a rule" in CODE_STYLE.md | V | `silently reopens every few sessions` in `docs/CODE_STYLE.md` |

## Shared check scripts

Task 1 step 0 writes these two files, with the bodies below verbatim, into the quick-task directory
`$Q` (`Q=.planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w`). They are this
task's own artifacts. `.planning/` is outside the comment lint, and Task 1 commits them.

`$Q/ledger_check.py <rules|all> <pre|post>`:
- `pre` checks every selected row whose path is not docs/CODE_STYLE.md, and must pass BEFORE
  the cut.
- `post` checks every selected row.
- Each V probe must also exist in the 2fb5710 file.

    import re,subprocess,sys
    P=".planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w/261006-kpj-PLAN.md"
    sel=None if sys.argv[1]=="all" else set(sys.argv[1].split(","))
    phase=sys.argv[2]
    base=subprocess.run(["git","show","2fb5710:docs/CODE_STYLE.md"],capture_output=True,text=True,check=True).stdout
    rows=[l for l in open(P,encoding="utf-8") if re.match(r"^\| K\d\d ",l)]
    hit,skip,none_,miss=0,0,0,[]
    for l in rows:
        c=[x.strip() for x in l.strip().strip("|").split("|")]
        rid,fact,kind=c[0],c[1],c[3]
        m=re.search(r"\((\d+)",fact); rule=m[1] if m else None
        if sel is not None and rule not in sel: skip+=1; continue
        if kind=="—": none_+=1; continue
        probe,path=re.search(r"\| `([^`]+)` in `([^`]+)` \|\s*$",l).groups()
        if phase=="pre" and path=="docs/CODE_STYLE.md": skip+=1; continue
        if kind=="V" and probe not in base: miss.append(rid+":not-in-2fb5710"); continue
        try: ok=probe in open(path,encoding="utf-8").read()
        except FileNotFoundError: ok=False
        hit+=ok
        if not ok: miss.append(rid)
    print(len(rows),"rows;",hit,"hit;",none_,"no destination;",skip,"deferred or unselected; misses:",miss)
    sys.exit(1 if miss or len(rows)!=97 else 0)

`$Q/arch_results.py <red|green> <rule names...>` reads Gradle's own fastTest XML with a regex
rather than an XML parser. `red` requires every named rule to have failed; `green` requires every
named rule to have run and passed.

    import glob,re,sys
    mode,names=sys.argv[1],sys.argv[2:]
    cases=[]
    for f in glob.glob("build/test-results/fastTest/TEST-*.xml"):
        for m in re.finditer(r'<testcase name="([^"]*)"[^>]*?(?:/>|>(.*?)</testcase>)',open(f,encoding="utf-8").read(),re.S):
            cases.append((m[1],bool(m[2]) and ("<failure" in m[2] or "<error" in m[2])))
    bad=[]
    for n in names:
        hits=[c for c in cases if n in c[0]]
        if not hits: bad.append(n+":not-run")
        elif mode=="red" and not all(x[1] for x in hits): bad.append(n+":passed")
        elif mode=="green" and any(x[1] for x in hits): bad.append(n+":failed")
    print(mode,len(names),"rules; problems:",bad); sys.exit(1 if bad else 0)

Architecture run: `./gradlew fastTest --tests 'com.vrudenko.kanban_board.architecture.*'`. This
only runs ArchUnit, so no Testcontainers start. A RED run exits nonzero by design; read its result
through `arch_results.py`.

<tasks>

<task type="tracer">
  <name>Task 1: Tracer, rules 6, 7 and 12 end to end (ArchUnit, rubric, stub, ledger) through one commit</name>
  <files>src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java, docs/CODE_REVIEW_RUBRIC.md, docs/CODE_STYLE.md, .planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w/ledger_check.py, .planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w/arch_results.py</files>
  <read_first>The ArchUnit rule specifications, Rubric assembly and Shared check scripts sections above; LayeringArchTest.java; docs/SESSION_LESSONS.md</read_first>
  <action>
After this task, rules 6, 7 and 12 are enforced in fastTest, rule 2's stub names its real
enforcer, and rubric entries 2 and 12 exist. This proves the whole path (ArchUnit, rubric, index,
ledger) on one slice before the bulk of the doc is touched.

0. Write `ledger_check.py` and `arch_results.py` into the quick-task directory, dedented, with the
   bodies from "Shared check scripts". Smoke-test the ledger script: `ledger_check.py all pre`
   must report 97 rows and 30 hits, all of them the E rows, with every other row missing because
   its destination does not exist yet. That confirms it parses.
1. Create MainCodeStyleArchTest exactly as specified in "ArchUnit rule specifications". It has four
   `@ArchTest` fields, one `@AnalyzeClasses` mirroring LayeringArchTest, and a Javadoc that follows
   rule 14:
   - A summary line, then a blank line.
   - What the class enforces, by CODE_STYLE rule number.
   - A `Known holes:` block with the three holes listed.
   - No planning ids anywhere in comments.
2. RED. Create a scratch main-source fixture
   `src/main/java/com/vrudenko/kanban_board/dto/ScratchStyleViolations.java`. It holds static
   nested classes:
   - `UpdateScratchRequestDTO`: no `@JsonInclude`, no `version` field, two plain String fields, and
     a third String field carrying jakarta `@NotBlank`.
   - `SaveScratchRequestDTO`, annotated `@JsonInclude(JsonInclude.Include.NON_NULL)`.
   - A static method returning `Optional.of("x").orElseThrow()`.

   Run the architecture run. Then run `arch_results.py red` with the four rule names. Record each
   rule's first violation line for the SUMMARY. Each must name a Scratch class and nothing from
   the real tree.
3. GREEN. Delete the fixture with `trash-put` (rm is blocked on this machine). Run the architecture
   run again, then `arch_results.py green` with the same four names plus
   `domain_services_must_load_through_ownership_verified_findById` and
   `test_classes_must_not_reside_directly_in_the_root_package`. If a real class fails, do not
   weaken the rule. Fix it only if the fix is trivial and justified in the SUMMARY. Otherwise stop
   and report.
4. Create docs/CODE_REVIEW_RUBRIC.md with the opening text and entries 2 and 12, per "Rubric
   assembly".
5. Run `ledger_check.py 2,6,7,12 pre`; it must exit 0. Only then replace sections 2, 6, 7 and 12
   of docs/CODE_STYLE.md with their Target content text. Rule 7's replacement removes the
   misfiled LayeringArchTest paragraph. Keep the four headings byte-identical. Run
   `ledger_check.py 2,6,7,12 post`.
6. Run `python3 scripts/verify-comments.py check`, `./gradlew spotlessApply`, then
   `./gradlew spotlessCheck`.
7. Commit in the background through the unmodified hook, staging these five paths explicitly. Use
   the message `feat(quick-261006-kpj): enforce CODE_STYLE rules 6, 7 and 12 with
   MainCodeStyleArchTest`. Wait for the hook to finish (Monitor until `git log -1` shows the
   commit, or the hook reports a failure) before editing anything else: the hook lints the working
   tree.
  </action>
  <verify>
    <automated>Q=.planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w && test ! -e src/main/java/com/vrudenko/kanban_board/dto/ScratchStyleViolations.java && ./gradlew fastTest --tests 'com.vrudenko.kanban_board.architecture.*' -q && python3 -I $Q/arch_results.py green optionals_must_be_unwrapped_with_an_isEmpty_guard_not_orElseThrow update_request_dtos_must_carry_the_partial_update_shape save_and_response_dtos_must_not_carry_json_include update_request_dto_fields_must_not_carry_not_blank && python3 -I $Q/ledger_check.py 2,6,7,12 post && grep -c '^### ' docs/CODE_STYLE.md | grep -qx 14 && grep -q 'DoNotIncludeTests' src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java && ! grep -q '@Tag\|@ArchTag' src/test/java/com/vrudenko/kanban_board/architecture/MainCodeStyleArchTest.java && python3 scripts/verify-comments.py check && S=$(git log -1 --format=%s) && grep -q 'enforce CODE_STYLE rules 6, 7 and 12' <<< "$S"</automated>
  </verify>
  <done>
- Four MainCodeStyleArchTest rules were seen red on the fixture and green on the real tree, with
  both directions recorded.
- The rubric holds entries 2 and 12.
- CODE_STYLE sections 2, 6, 7 and 12 match the target.
- The ledger rows for rules 2, 6, 7 and 12 pass pre and post.
- The commit landed through the hook.
  </done>
</task>

<task type="auto">
  <name>Task 2: Rules 3, 4 and 5 enforced in tests; rubric, ARCHITECTURE section and the full CODE_STYLE rewrite</name>
  <files>src/test/java/com/vrudenko/kanban_board/architecture/TestCodeStyleArchTest.java, docs/CODE_REVIEW_RUBRIC.md, docs/ARCHITECTURE.md, build.gradle, docs/CODE_STYLE.md</files>
  <read_first>The TestCodeStyleArchTest spec, Rubric assembly, ARCHITECTURE.md edits and Target content sections above; TestPlacementArchTest.java; docs/ARCHITECTURE.md lines 289-361</read_first>
  <action>
After this task, every rule a tool can check fails the build on violation, every judgement rule
sits in the rubric with a deciding test, and docs/CODE_STYLE.md is the target index.

1. Create TestCodeStyleArchTest exactly as specified: five checks, `@AnalyzeClasses` mirroring
   TestPlacementArchTest, banned types named by string, and a rule-14 Javadoc with the three
   Known holes.
2. RED. Create a scratch test-source fixture
   `src/test/java/com/vrudenko/kanban_board/architecture/ScratchTestStyleViolations.java`. Give it
   no `@Test` methods, so JUnit never runs it, and chain every assertion so ErrorProne's
   CheckReturnValue accepts it. It contains:
   - A static import of AssertJ's `Assertions.assertThat`, used once.
   - A method annotated with JUnit's `DisplayName`.
   - A local assigned from `org.mockito.Mockito.mock(java.util.List.class)`.
   - A call to JUnit's `Assertions.assertThrows`.
   - A chained AssertJ `assertThatThrownBy`.
   - A static nested class annotated `@WebMvcTest`.

   Run the architecture run, then `arch_results.py red` with the five names. Record each first
   violation line. MainCodeStyleArchTest must stay green in this run, because it imports main
   only.
3. GREEN. Delete the fixture with `trash-put`. Run the architecture run. Run
   `arch_results.py green` with all nine new names plus the LayeringArchTest and
   TestPlacementArchTest names used in Task 1. Never weaken a rule to pass.
4. Add rubric entries 1, 4, 5, 8, 9, 13 and 14 in numeric order, per "Rubric assembly".
5. Apply ARCHITECTURE edits A1-A5.
6. In build.gradle's spotless comment, insert ", deliberately ahead of third-party" after
   "com.vrudenko.* (first-party" on the import-order summary line. Do not add a new line.
7. Run `ledger_check.py all pre`; it must exit 0 with zero misses. Only then Write
   docs/CODE_STYLE.md as the Target content, byte-for-byte. Run `ledger_check.py all post`.
8. Run `python3 scripts/verify-comments.py check`, then `./gradlew spotlessApply spotlessCheck`.
9. Commit in the background through the hook, staging the five paths explicitly. Use the message
   `feat(quick-261006-kpj): enforce rules 3-5 in tests and split CODE_STYLE into index, rubric and
   ARCHITECTURE`. Wait for the hook to finish before Task 3.
  </action>
  <verify>
    <automated>Q=.planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w && test ! -e src/test/java/com/vrudenko/kanban_board/architecture/ScratchTestStyleViolations.java && ./gradlew fastTest --tests 'com.vrudenko.kanban_board.architecture.*' -q && python3 -I $Q/arch_results.py green tests_must_not_use_mockito_or_mock_beans tests_must_not_use_spring_boot_test_slices tests_must_assert_with_assertj_and_capture_exceptions_first assertj_assertions_must_not_be_statically_imported tests_must_not_use_display_name optionals_must_be_unwrapped_with_an_isEmpty_guard_not_orElseThrow update_request_dtos_must_carry_the_partial_update_shape save_and_response_dtos_must_not_carry_json_include update_request_dto_fields_must_not_carry_not_blank && python3 -I $Q/ledger_check.py all post && python3 -I -c 'import re;p=open(".planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w/261006-kpj-PLAN.md",encoding="utf-8").read();t=re.search(r"## Target content: docs/CODE_STYLE.md.*?```markdown\n(.*?)```\n",p,re.S)[1];f=open("docs/CODE_STYLE.md",encoding="utf-8").read();assert f==t,"CODE_STYLE differs from target";print("CODE_STYLE == target,",len(f.encode()),"bytes")' && python3 -I -c 'import re;r=open("docs/CODE_REVIEW_RUBRIC.md",encoding="utf-8").read();n=[int(x) for x in re.findall(r"^### (\d+)\. ",r,re.M)];assert n==[1,2,4,5,8,9,12,13,14],n;assert r.count("**Deciding test:**")==9;print("rubric ok")' && grep -q '^### Writing a new test: package, tier and base class$' docs/ARCHITECTURE.md && ! grep -q 'a handful still at the root package' docs/ARCHITECTURE.md && ! grep -q 'see "State Management" above' docs/ARCHITECTURE.md && grep -q 'deliberately ahead of third-party' build.gradle && python3 scripts/verify-comments.py check && S=$(git log -1 --format=%s) && grep -q 'rules 3-5' <<< "$S"</automated>
  </verify>
  <done>
- Five TestCodeStyleArchTest checks were seen red on the fixture and green on the real tree.
- The rubric holds nine entries, each with a deciding test.
- ARCHITECTURE has the moved section and edits A2-A5.
- CODE_STYLE.md equals the target.
- All 97 ledger rows pass.
- The commit landed through the hook.
  </done>
</task>

<task type="auto">
  <name>Task 3: Repoint citations and the review pointer, then run the full gates and report</name>
  <files>.claude/CLAUDE.md, README.md, docs/SESSION_LESSONS.md, docs/learning/00-README.md, docs/learning/01-domain-model-and-schema.md, docs/learning/05-api-layer.md, docs/learning/08-testing-strategy.md, docs/learning/09-build-quality-and-ci.md</files>
  <read_first>The Citation edits section above (C1-C8) and the heading slug list</read_first>
  <action>
After this task, every citation of the file is true again, an agent reviewing a diff is pointed at
the rubric, and the final tree passes every gate.

1. Apply C1-C8. Each is a small scoped Edit: read only the cited lines first. These edits are
   small, which is why eight files sit in one task.
2. Run `python3 scripts/verify-instruction-budget.py`. CLAUDE.md must stay under the 5,100-byte
   ceiling. Do not raise the ceiling.
3. Run the citation and anchor check from verify. It must report zero bad rule numbers, zero
   unresolved anchors and zero `#L` anchors into CODE_STYLE.md.
4. Run the full gates on the final tree in the background, and wait on them with Monitor:
   `./gradlew test` (CI-equivalent: Kafka, real-socket and the JaCoCo ratchet) and
   `./gradlew spotlessCheck`. Report pass or fail from the actual output. Then run `docker ps -a`,
   and report and remove any orphaned Testcontainers container. The user's memory notes that Ryuk
   is absent.
5. Commit in the background through the hook, staging the eight paths explicitly. Use the message
   `docs(quick-261006-kpj): repoint CODE_STYLE citations and add the review-rubric pointer`. Wait
   for the hook to finish. Do not merge to main: the user reviews the diff first.
6. Write the SUMMARY. It must include:
   - CODE_STYLE.md bytes before (43,974) and after (measured), with bytes/4 token estimates.
   - The `ledger_check.py all post` counts.
   - The RED and GREEN direction for each of the nine new rules, with the first violation line
     of each RED failure.
   - The placement table's overturned rows.
   - Found but not fixed: ARCHITECTURE's "382 test methods" count is stale (488 test-method
     annotations measured), and LogoutHandler uses `HttpServletResponse.SC_OK` (a named
     constant, not a rule-1 violation).
   - Follow-up for the user: point the global fan-out-review brief at docs/CODE_REVIEW_RUBRIC.md.
  </action>
  <verify>
    <automated>python3 -I -c '
import re,subprocess
def slugs(p):
    out,inf=set(),False
    for l in open(p,encoding="utf-8"):
        if l.startswith("```"): inf=not inf; continue
        if not inf and re.match(r"^#{1,6} ",l): out.add(re.sub(r"[^\w\- ]","",l.lstrip("#").strip().lower()).replace(" ","-"))
    return out
S={"CODE_STYLE":slugs("docs/CODE_STYLE.md"),"CODE_REVIEW_RUBRIC":slugs("docs/CODE_REVIEW_RUBRIC.md"),"ARCHITECTURE":slugs("docs/ARCHITECTURE.md"),"LOCAL_DEV":slugs("docs/LOCAL_DEV.md")}
nums={int(n) for n in re.findall(r"^### (\d+)\. ",open("docs/CODE_STYLE.md",encoding="utf-8").read(),re.M)}
fs=[f for f in subprocess.run(["git","ls-files","src","scripts","docs",".githooks",".github",".claude/CLAUDE.md","build.gradle","README.md"],capture_output=True,text=True,check=True).stdout.split() if not f.startswith(("docs/wiki/","docs/raw/"))]
assert len(fs)>100,len(fs)
bn,ba,la,c=[],[],[],0
for f in fs:
    try: s=open(f,encoding="utf-8").read()
    except Exception: continue
    for m in re.finditer(r"CODE_STYLE(?:\.md)?`?\)?\]?(?:\([^)]*\))?,?\s+rules?\s+(\d+(?:(?:,\s*|\s+and\s+)\d+)*)",s):
        for n in re.findall(r"\d+",m[1]):
            c+=1
            if int(n) not in nums: bn.append((f,n))
    for m in re.finditer(r"(CODE_STYLE|CODE_REVIEW_RUBRIC|ARCHITECTURE|LOCAL_DEV)\.md#([^)\s]+)",s):
        if m[1]=="CODE_STYLE" and re.match(r"L\d+",m[2]): la.append((f,m[2]))
        elif m[1] in ("CODE_STYLE","CODE_REVIEW_RUBRIC") and m[2] not in S[m[1]]: ba.append((f,m[2]))
        elif f=="docs/CODE_STYLE.md" and m[2] not in S[m[1]]: ba.append((f,m[2]))
print(c,"rule-number citations; bad numbers",bn,"bad anchors",ba,"line anchors",la)
assert nums==set(range(1,15)) and c>=91 and not bn and not ba and not la
' && grep -q 'Reviewing a Java diff: docs/CODE_REVIEW_RUBRIC.md' .claude/CLAUDE.md && grep -q 'CODE_REVIEW_RUBRIC.md' README.md && grep -q 'CODE_REVIEW_RUBRIC.md' docs/SESSION_LESSONS.md && grep -q 'CODE_REVIEW_RUBRIC.md' docs/learning/00-README.md && grep -q 'records 14 numbered' docs/learning/09-build-quality-and-ci.md && grep -q '(removed 2026-10-06)' docs/learning/01-domain-model-and-schema.md && grep -q 'MainCodeStyleArchTest' docs/learning/05-api-layer.md && grep -q 'TestPlacementArchTest` keeps the count' docs/learning/08-testing-strategy.md && python3 scripts/verify-instruction-budget.py && python3 scripts/verify-comments.py check && python3 -I .planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w/ledger_check.py all post && S=$(git log -1 --format=%s) && grep -q 'repoint CODE_STYLE citations' <<< "$S"</automated>
  </verify>
  <done>
- Every citation resolves.
- The rubric pointer is in CLAUDE.md, within budget.
- `./gradlew test` and `spotlessCheck` passed on the final tree; the output was read, not assumed.
- No orphaned containers remain.
- Three commits landed through the hook, and nothing was merged.
- The SUMMARY carries the byte and token report, the ledger counts and both proof directions per
  rule.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| documentation → future agents and reviewers | A dropped or wrong fact silently misdirects later work |
| pre-commit gate → every commit | A false-positive rule blocks all commits; a weakened rule lets regressions through |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-kpj-01 | Repudiation (knowledge loss) | docs/CODE_STYLE.md rewrite | medium | mitigate | 97-row ledger, with a probe per removed fact. Each task runs `ledger_check.py ... pre` before its cut and `post` after; one miss fails the task. |
| T-kpj-02 | Tampering (gate weakening) | MainCodeStyleArchTest exemptions | medium | mitigate | Two exemptions, each by exact name, with the reason in because() citing the class's own Javadoc. RED on a fixture proves every rule bites. "Never weaken a rule to pass" is in both test tasks. |
| T-kpj-03 | Denial of service | nine new rules in fastTest | medium | mitigate | Zero violations measured at planning time. A GREEN run on the real tree precedes every commit, and the hook runs the full fastTest. |
| T-kpj-04 | Information disclosure | plan and SUMMARY prose under .planning | low | mitigate | gitleaks scans every staged diff. No artifact holds a credential. |
| T-kpj-SC | Tampering | npm/pip/cargo installs | low | accept | There are no package installs. ArchUnit 1.4.2 is already a pinned test dependency. |
</threat_model>

<verification>
- `ledger_check.py all post`: 97 rows, 94 hit, 3 with no destination, zero misses.
- The nine new rules: RED on fixtures and GREEN on the tree, both recorded.
- docs/CODE_STYLE.md equals the target; the 14 headings are identical to 2fb5710.
- The citation and anchor check is clean.
- `./gradlew test`, `./gradlew spotlessCheck`, `verify-comments.py check` and
  `verify-instruction-budget.py` all pass on the final tree.
</verification>

<success_criteria>
docs/CODE_STYLE.md shrinks from 43,974 bytes to about 9,300. Every rule either names the test or
linter that fails the build, or names its rubric entry together with the measured reason no tool
can check it. No fact is lost, and every existing "CODE_STYLE rule N" citation still resolves.
</success_criteria>

<output>
Create `.planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w/261006-kpj-SUMMARY.md` when done.
</output>
