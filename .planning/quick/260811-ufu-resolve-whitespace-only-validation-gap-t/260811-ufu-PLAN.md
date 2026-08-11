---
phase: quick-260811-ufu
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - src/main/java/com/vrudenko/kanban_board/dto/annotation/OptionalNotBlank.java
  - src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/UpdateSubtaskRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/user_dto/SignupRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java
  - src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java
  - src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java
  - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java
  - src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java
  - src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java
  - docs/CODE_STYLE.md
  - .planning/todos/pending/
  - .planning/todos/completed/
  - .planning/STATE.md
autonomous: true
requirements: [UFU-01, UFU-02, UFU-03, UFU-04, UFU-05, UFU-06]
user_setup: []

estimate:
  tokens: 95000
  raw_tokens: 95000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "A whitespace-only value on UpdateBoardRequestDTO.name, UpdateTaskRequestDTO.title, UpdateSubtaskRequestDTO.title and SignupRequestDTO.displayName is rejected — HTTP 400, not persisted (D-01, UFU-02)"
    - "Omitting (null) any of those four fields still passes validation — the documented optionality that SignupRequestDTOTest.whenDisplayNameIsMissing_thenNoViolation and the @JsonInclude(NON_NULL) partial-update convention depend on is not regressed (D-01, UFU-02)"
    - "The blank check is one reusable field annotation applied four times, not four per-DTO @AssertTrue methods — a future optional String field adopts it by adding a single annotation (D-01, UFU-01)"
    - "UpdateColumnRequestDTO.name still carries @NotBlank and still rejects null — unchanged behavior, now with a class-level Javadoc saying why that asymmetry is deliberate (D-02, UFU-03)"
    - "UpdateBoardRequestDTO's D-13 comment no longer claims its optional-name shape matches the Column DTO (D-02, UFU-04)"
    - "Board's own version-only-update assumption is filed as a new [minor] todo and is NOT fixed here (D-02, UFU-05)"
    - "./gradlew spotlessCheck and ./gradlew test both pass, with zero test shrinkage against the pre-task count (UFU-06)"
    - "The source todo is moved to .planning/todos/completed/ with a resolution note naming both decisions and what was deliberately deferred (UFU-06)"
  artifacts:
    - src/main/java/com/vrudenko/kanban_board/dto/annotation/OptionalNotBlank.java
    - src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java
    - .planning/todos/completed/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md
    - .planning/todos/pending/ (one new [minor] todo about UpdateBoardRequestDTO.name's untested optionality)
  key_links:
    - "@OptionalNotBlank composes @Pattern, and Bean Validation's built-in @Pattern treats null as valid — that null-passthrough is the entire mechanism that makes 'optional but not blank' work. Replacing @Pattern with @NotBlank inside the composition would silently make all four fields mandatory."
    - "@Pattern uses Matcher.matches() (whole-string), and '.' excludes newlines unless Pattern.Flag.DOTALL is set — a regex of '\\S.*' without DOTALL would reject every legitimate multi-line value, breaking the annotation for the reuse case it exists to serve."
    - "The annotation is applied ALONGSIDE each field's existing composed annotation (@BoardName / @TaskTitle / @SubtaskTitle / @DisplayName), never replacing it — those carry the @Size and character-class rules this one does not."
---

<objective>
Close the whitespace-only validation gap on the four fields the 260811-qru audit empirically
confirmed accept `"   "` today, without breaking their documented optionality, and settle the
`UpdateColumnRequestDTO.name` asymmetry as a documented deliberate choice rather than a bug.

Purpose: a whitespace-only board name, task title, subtask title or signup display name reaches
persistence today (severity: major). The naive fix — adding `@NotBlank` — would also reject `null`
and silently make four optional fields mandatory, breaking the `@JsonInclude(NON_NULL)`
partial-update contract and an explicitly asserted signup behavior.

Output: one reusable `@OptionalNotBlank` composed constraint annotation, applied to four fields,
with per-field regression coverage at both the validator and HTTP tiers; a documented rationale on
`UpdateColumnRequestDTO`; a corrected comment on `UpdateBoardRequestDTO`; one new deferred `[minor]`
todo; the source todo closed; STATE.md updated.
</objective>

<approach_analysis>
## Alternatives Considered (CLAUDE.md GSD Execution Directive)

**Approach A — reusable composed constraint annotation (`@OptionalNotBlank` composing `@Pattern`).**
A new annotation in `dto/annotation/` built exactly like `BoardName.java`: `@Constraint(validatedBy = {})`
with no `ConstraintValidator` implementation, composing a single built-in `@Pattern`. Bean
Validation's built-in validators return *valid* for `null` (only `@NotNull` / `@NotBlank` /
`@NotEmpty` reject it), so the composition means "reject blank, ignore absent" for free.

**Approach B — per-DTO `@AssertTrue` cross-check** (`value == null || !value.isBlank()`), the shape
the source todo proposed, following the `atLeastOneFieldPopulated()` precedent already on
`UpdateTaskRequestDTO` / `UpdateSubtaskRequestDTO`.

**Approach C — custom `ConstraintValidator<OptionalNotBlank, String>`** with a hand-written
`isValid` that returns `value == null || !value.isBlank()`.

| Approach | Pros / Cons | Why Picked |
|---|---|---|
| **A. Composed `@Pattern` annotation** | **+** One artifact, four one-word application sites; matches the seven existing annotations in `dto/annotation/` exactly, so nothing new to learn. **+** Zero imperative code, therefore zero code paths to unit-test in isolation. **+** Reuse is a single annotation, satisfying D-01's "genuinely reusable" requirement. **−** Expresses "not blank" as a regex, which is less self-evident than `!value.isBlank()`. **−** Regex must be written carefully (whole-string match, DOTALL) or it silently over-rejects. | **PICKED — locked by CONTEXT.md D-01.** Also the correct call on merits: the property is field-local, so a field-level constraint is the right granularity, and it composes with the existing per-field annotations instead of competing with them. |
| **B. Per-DTO `@AssertTrue`** | **+** Reads plainly in Java. **+** Precedent already exists in this codebase. **−** Not reusable — four near-identical private methods across four DTOs, one of which (`SignupRequestDTO`) is not an Update DTO at all. **−** The violation's property path is the *method* name, not the field, so the RFC 7807 `errors` map would key on `atLeastOneFieldPopulated`-style names instead of `name`/`title`/`displayName`, degrading the error envelope that quick task 260811-p9c just converged. | **REJECTED** — explicitly ruled out by D-01, and the property-path degradation is a concrete regression against 260811-p9c's work. |
| **C. Custom `ConstraintValidator`** | **+** Most literal expression of the rule; no regex subtleties. **−** Introduces the first hand-written `ConstraintValidator` in a codebase whose seven existing annotations are all validator-free compositions — a new pattern for zero behavioral gain. **−** More code to own and test than the property justifies. | **REJECTED** — strictly more machinery than A for identical observable behavior. |

## Non-obvious trade-offs

**Regex form — `\S.*` vs `.*\S.*` with DOTALL.** `@Pattern` evaluates with `Matcher.matches()`
(whole-string), and `.` does not match a newline unless `Pattern.Flag.DOTALL` is set. So the
`\S.*` form CONTEXT.md offered as an illustration has two properties worth naming before adopting
it: (1) it rejects any multi-line value outright, which is wrong for the reuse case (a future
optional `@Description`-style field), and (2) it rejects a leading space (`" a"`), which is
*stricter* than the `@NotBlank` on `UpdateColumnRequestDTO` that this task is deliberately taking
as the reference behavior. This plan therefore uses `".*\\S.*"` with `Pattern.Flag.DOTALL`, which
is exactly `@NotBlank` semantics minus the null rejection. CONTEXT.md's D-01 explicitly permits
"or equivalent"; this is that equivalent, chosen for behavioral parity with the one sibling the
todo calls correct.

**ReDoS (performance/security).** Constraint evaluation order is not specified by Bean Validation,
so `@OptionalNotBlank` may run *before* the sibling `@Size(max = …)` that bounds length — the regex
must be safe on unbounded input on its own. `.*\S.*` is linear: against an all-whitespace string of
length n the greedy `.*` backtracks one position at a time and each position fails `\S` in O(1),
giving O(n) total with no nested quantifier and therefore no catastrophic backtracking. This is a
deliberate check, not an assumption — a form like `(\s*\S)*` would have been exponential.

**Violation-count interaction (state-invalidation risk).** Adding a second independent constraint to
a field means a value that violates *both* now yields 2 violations instead of 1, which would break
any existing test asserting an exact count. Surveyed before planning: every existing exact-count
assertion (`SignupRequestDTOTest`) uses non-blank fixtures, and every empty-string fixture
(`BoardControllerTest:169`, `ColumnControllerTest:252`, `TaskControllerTest:244`,
`SubtaskControllerTest:255`, `BoardCreationE2ETest:127`) asserts only an HTTP status, not a count —
and the two `*ControllerTest` ones build `Save*RequestDTO`, which this task does not touch. No
breakage predicted; Task 3's full-suite run is what actually proves it.

**Memory/runtime cost.** One additional compiled `Pattern` per annotated field, cached by Hibernate
Validator at metadata-build time. Not measurable against a suite already dominated by Testcontainers
startup and BCrypt.
</approach_analysis>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/quick/260811-ufu-resolve-whitespace-only-validation-gap-t/260811-ufu-CONTEXT.md
@.planning/todos/pending/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md
@docs/CODE_STYLE.md
@src/main/java/com/vrudenko/kanban_board/dto/annotation/BoardName.java
@src/main/java/com/vrudenko/kanban_board/dto/user_dto/UpdateThemeRequestDTO.java
</context>

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Create @OptionalNotBlank and prove it end-to-end on UpdateBoardRequestDTO.name</name>
  <files>
    src/main/java/com/vrudenko/kanban_board/dto/annotation/OptionalNotBlank.java,
    src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java,
    src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java,
    src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java
  </files>
  <read_first>
    src/main/java/com/vrudenko/kanban_board/dto/annotation/BoardName.java,
    src/test/java/com/vrudenko/kanban_board/dto/SignupRequestDTOTest.java,
    src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java (the UpdateById @Nested class, lines 103-206),
    docs/CODE_STYLE.md rules 3, 4 and 5
  </read_first>
  <behavior>
    Validator tier (new class `OptionalNotBlankTest`, package `com.vrudenko.kanban_board.dto`,
    built like `SignupRequestDTOTest`: a plain non-Spring JUnit class holding a
    `Validation.buildDefaultValidatorFactory().getValidator()`):
    - `UpdateBoardRequestDTO` with `name = "   "` and a non-null `version` produces exactly ONE
      violation, whose property path is `name`.
    - `UpdateBoardRequestDTO` with `name = null` and a non-null `version` produces ZERO violations
      (the D-13 optionality is preserved).
    - A control case: `name = " Valid Name "` (leading and trailing spaces around real content)
      produces ZERO violations — proves the rule is "contains a non-whitespace character", the same
      contract `@NotBlank` gives `UpdateColumnRequestDTO`, not "starts with one".

    HTTP tier (added to the existing `BoardControllerTest.UpdateById` @Nested class):
    - `PUT /api/boards/{boardId}` as the owning user, body `UpdateBoardRequestDTO` with
      `name = "   "` and the board's current `version`, returns 400.
  </behavior>
  <action>
    RED FIRST. Write both new test bodies before `UpdateBoardRequestDTO` is touched, run them, and
    record in the summary that the whitespace cases fail (the validator case reports zero
    violations; the HTTP case returns 200) — that failure IS the reproduction of the major-severity
    defect. Do not proceed to the fix until the red output has been observed and quoted.

    Create `src/main/java/com/vrudenko/kanban_board/dto/annotation/OptionalNotBlank.java` in package
    `com.vrudenko.kanban_board.dto.annotation`, following `BoardName.java`'s skeleton exactly:
    `@Documented`, `@Target({ElementType.FIELD})`, `@Retention(RetentionPolicy.RUNTIME)`,
    `@ReportAsSingleViolation`, `@Constraint(validatedBy = {})`, and exactly one composing
    constraint — `@Pattern(regexp = ".*\\S.*", flags = Pattern.Flag.DOTALL)` from
    `jakarta.validation.constraints.Pattern`. Declare the three required members: `String message()
    default "must not be blank when provided"`, `Class<?>[] groups() default {}`, and
    `Class<? extends Payload>[] payload() default {}`. Use explicit single-type imports (not the
    `java.lang.annotation.*` wildcard `BoardName.java` happens to use) and let `spotlessApply`
    place them per CODE_STYLE rule 10.

    Give the annotation a Javadoc that states the contract and the two mechanisms a future reader
    must not break: that the composing constraint is `@Pattern` specifically because Bean
    Validation's built-in constraints treat `null` as valid — swapping it for `@NotBlank` would make
    every annotated field mandatory — and that `Pattern.Flag.DOTALL` is required because `@Pattern`
    is a whole-string `Matcher.matches()` and an undotted `.` would reject legitimate multi-line
    values. Note that it is meant to be stacked alongside a field's existing composed annotation
    (which owns the `@Size` and character-class rules), never to replace it. Do not add a new
    constant to `ValidationConstants` — the default message is deliberately generic and not
    overridden per site, because the RFC 7807 envelope already keys `errors` by field name (quick
    task 260811-p9c) and per-site message constants are exactly the surface that produced the two
    mismatch bugs 260811-qru found.

    Then annotate `UpdateBoardRequestDTO.name` with `@OptionalNotBlank` in addition to its existing
    `@BoardName`. Re-run both tests, observe GREEN. Do not touch `UpdateBoardRequestDTO`'s D-13
    comment in this task — that correction is Task 3's, so the diff stays reviewable one concern at
    a time.

    Name the HTTP-tier test per CODE_STYLE rule 5 and place it in the existing `UpdateById` @Nested
    class next to `testWithAuthenticatedUser_shouldReturnBadRequest_whenDataIsInvalid`. Leave that
    existing sibling test exactly as it is.
  </action>
  <verify>
    <automated>./gradlew spotlessApply &amp;&amp; ./gradlew test --tests 'com.vrudenko.kanban_board.dto.OptionalNotBlankTest' --tests 'com.vrudenko.kanban_board.controller.BoardControllerTest'</automated>
  </verify>
  <done>
    `OptionalNotBlank.java` exists and compiles with no `ConstraintValidator` implementation class.
    A whitespace-only board name is rejected with exactly one violation on property path `name` at
    the validator tier and with HTTP 400 at the endpoint; a null name and a space-padded valid name
    both still pass. The red-then-green transition is recorded with quoted output in the summary.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Apply @OptionalNotBlank to the remaining three fields with per-field regression coverage</name>
  <files>
    src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java,
    src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/UpdateSubtaskRequestDTO.java,
    src/main/java/com/vrudenko/kanban_board/dto/user_dto/SignupRequestDTO.java,
    src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java,
    src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java,
    src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java,
    src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java
  </files>
  <read_first>
    src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java (the UpdateById @Nested class, from line 112),
    src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java (the UpdateById @Nested class, from line 114),
    src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java (the Signup.FieldValidation @Nested class, from line 378)
  </read_first>
  <behavior>
    Validator tier (extend `OptionalNotBlankTest` with one @Nested group per DTO, mirroring the
    Board group Task 1 established):
    - `UpdateTaskRequestDTO` with `title = "   "`, a non-null `version`, and no `description`:
      exactly ONE violation, property path `title`. With `title = null`, `description` set to a
      valid value and a non-null `version`: ZERO violations (the `atLeastOneFieldPopulated()`
      cross-check is satisfied by `description`, so title optionality is genuinely isolated).
    - `UpdateSubtaskRequestDTO` with `title = "   "` and a non-null `version`: exactly ONE
      violation, property path `title`. With `title = null`, `isCompleted` set and a non-null
      `version`: ZERO violations.
    - `SignupRequestDTO` with `displayName = "   "` and a valid email and password: exactly ONE
      violation, property path `displayName`. With `displayName = null` and a valid email and
      password: ZERO violations.

    HTTP tier:
    - `PUT` task update with `title = "   "` and the task's current `version` returns 400.
    - `PUT` subtask update with `title = "   "` and the subtask's current `version` returns 400.
    - `POST /api/signup` with `displayName = "   "` and a valid email and password returns 400 with
      `$.code` = `VALIDATION_FAILED` and `$.errors.displayName` present.
  </behavior>
  <action>
    RED FIRST again, as one batch: write all six test bodies, run them, record which fail and how
    (per D-01, all three whitespace validator cases should report zero violations and all three
    whitespace HTTP cases should succeed rather than 400 — that is the defect, reproduced on the
    remaining three fields). Then add `@OptionalNotBlank` alongside the existing `@TaskTitle`,
    `@SubtaskTitle` and `@DisplayName` on the three fields, re-run, observe GREEN.

    For the fixture values: whitespace-only strings must be at least three characters, because
    `MIN_TASK_TITLE_LENGTH`, `MIN_SUBTASK_TITLE_LENGTH` and `MIN_USER_DISPLAY_NAME_LENGTH` are all
    3 — a shorter blank string would fail on `@Size` instead and would prove nothing about this
    task's change. For `SignupRequestDTO`, reuse the collision-proof email and valid-password
    helpers that `AuthenticationTest` and `SignupRequestDTOTest` already use rather than
    hand-rolling fixtures; both carry Javadoc explaining why the naive `DataFactory` forms are
    flaky.

    Build every new HTTP-tier update body from the matching `Update*RequestDTO` with a valid
    `version`, so the whitespace title is the only violated constraint. Two pending todos record
    that `TaskControllerTest.UpdateById` and `SubtaskControllerTest.UpdateById` each already contain
    a `whenDataIsInvalid` test that builds its body from the wrong DTO type; those tests are tracked
    separately and are out of this task's scope — add the new tests beside them and change neither.
    If a new test cannot be made to pass without touching one of them, stop and report rather than
    widening scope.

    Place the signup test inside the existing `AuthenticationTest.Signup.FieldValidation` @Nested
    class next to the malformed-email and weak-password cases, and assert the same three-part shape
    those use (status, `$.code`, `$.errors.<field>`), since that group exists specifically to prove
    per-field constraints fire on the signup body.
  </action>
  <verify>
    <automated>./gradlew spotlessApply &amp;&amp; ./gradlew test --tests '*OptionalNotBlankTest' --tests '*TaskControllerTest' --tests '*SubtaskControllerTest' --tests '*SignupRequestDTOTest' --tests '*AuthenticationTest'</automated>
  </verify>
  <done>
    All four fields named in D-01 carry `@OptionalNotBlank` alongside their pre-existing composed
    annotation. Each has a validator-tier pair (whitespace rejected with one violation on the right
    property path, null accepted) and an HTTP-tier 400 proof. `SignupRequestDTOTest` still passes
    unchanged, including its exact-violation-count assertions. The red-then-green transition is
    recorded with quoted output in the summary.
  </done>
</task>

<task type="auto">
  <name>Task 3: Document the Column asymmetry, correct the Board comment, file the deferred todo, close out</name>
  <files>
    src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java,
    src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java,
    docs/CODE_STYLE.md,
    .planning/todos/pending/,
    .planning/todos/completed/,
    .planning/STATE.md
  </files>
  <read_first>
    src/main/java/com/vrudenko/kanban_board/dto/user_dto/UpdateThemeRequestDTO.java (the class-level exemption-note precedent),
    docs/CODE_STYLE.md rule 6 and rule 11 (heading and body shape for a new rule),
    .planning/todos/pending/2026-08-11-delete-dead-deleteboardbyidrequestdto-class.md (todo frontmatter shape)
  </read_first>
  <action>
    Per D-02, make NO change to `UpdateColumnRequestDTO.name`'s annotations — it keeps `@NotBlank`
    and keeps rejecting null. Add a class-level Javadoc to `UpdateColumnRequestDTO` modelled on
    `UpdateThemeRequestDTO`'s rule-6 exemption note: record that `name` is deliberately mandatory
    rather than independently optional, that this is the intended reading of a DTO whose only
    mutable property is `name` (a version-only column update has no use case), and that the
    investigation behind D-02 found no test in `BoardServiceTest`/`BoardControllerTest` and no
    mockup evidence of a touch-without-rename flow. State plainly that `@NotBlank` here does the job
    `@OptionalNotBlank` does elsewhere *plus* the null rejection, so a future audit comparing the
    two DTOs sees an answer instead of an inconsistency.

    Update the D-13 comment block on `UpdateBoardRequestDTO.version`. Its current text asserts that
    `name` being the only independently optional field puts this DTO in the same shape as the column
    DTO; that parity claim is false as of this task and must be replaced. The replacement keeps the
    still-true part (why `version` is `@NotNull`, and why no `atLeastOneFieldPopulated()` check is
    needed with a single optional field), drops the cross-DTO equivalence, and points at
    `UpdateColumnRequestDTO`'s new class Javadoc for why that DTO deliberately differs.

    Add a new rule to the end of `docs/CODE_STYLE.md`'s numbered list (rule 12, matching rules 6 and
    11 in structure: a `### N. <imperative one-liner>` heading, a short body, a bolded **Why:**, and
    Discouraged/Preferred snippets) documenting the `@OptionalNotBlank` pattern: an optional String
    field that must reject blank values carries `@OptionalNotBlank` stacked on its existing composed
    annotation; `@NotBlank` is reserved for fields that are genuinely mandatory. Name the four
    current application sites and name `UpdateColumnRequestDTO` as the documented exception.

    File one new todo in `.planning/todos/pending/` with `severity: minor`, matching the frontmatter
    shape of the existing pending todos (`created`, `title`, `area: backend`, `severity`, `files`),
    describing that `UpdateBoardRequestDTO.name`'s optionality (D-13) rests on the same unexamined
    assumption that D-02 declined to accept for the column DTO — no test and no mockup evidence
    justifies a version-only board update either — and that it may deserve the same scrutiny.
    State explicitly in the todo that this task deliberately did not change that behavior.

    Move `.planning/todos/pending/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md`
    to `.planning/todos/completed/` with a resolution note appended, recording: how each of the
    todo's two open questions was decided (question 1 yes via the shared annotation, question 2 no —
    column stays mandatory and is now documented), that the `@AssertTrue` implementation the todo
    proposed was considered and rejected with the reason, and the one thing carried forward as the
    new `[minor]` todo.

    Update `.planning/STATE.md` per the convention every prior quick task follows: append a row to
    the "Quick Tasks Completed" table (`260811-ufu`, description, date, commit hashes, status,
    directory link), add a `[Quick/260811-ufu]:` entry to Accumulated Context > Decisions capturing
    the two locked decisions and the measured outcome, remove the now-closed `[major]` whitespace
    entry from Pending Todos, add the new `[minor]` entry, and refresh `last_activity` /
    `last_activity_desc` in the frontmatter.

    Run the full gate last: `./gradlew spotlessCheck` then `./gradlew test`. Report the exact
    before/after test counts — the pre-task baseline is 417 (STATE.md, quick task 260811-s5e) — and
    confirm zero shrinkage. If any pre-existing test fails, diagnose whether this task's second
    constraint changed a violation count before assuming it is unrelated.
  </action>
  <verify>
    <automated>./gradlew spotlessCheck &amp;&amp; ./gradlew test</automated>
    <automated>test -f .planning/todos/completed/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md &amp;&amp; grep -c 'OptionalNotBlank' docs/CODE_STYLE.md src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java</automated>
  </verify>
  <done>
    `UpdateColumnRequestDTO` carries a class-level Javadoc explaining its deliberate mandatory
    `name`, with its annotations unchanged. `UpdateBoardRequestDTO`'s D-13 comment states the
    current, accurate relationship between the two DTOs. `docs/CODE_STYLE.md` has a new numbered
    rule covering the pattern. One new `[minor]` todo exists in `.planning/todos/pending/`; the
    source todo is in `.planning/todos/completed/` with a resolution note. STATE.md reflects the
    completed task. `spotlessCheck` and the full `test` task both pass with no test shrinkage
    against the 417 baseline.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| HTTP client → REST API | Untrusted JSON request bodies cross here; Bean Validation on `@Valid`-annotated DTO parameters is the first gate, before any service or persistence code runs. |
| DTO → persistence | Values that survive validation are mapped to entities and written to PostgreSQL; a value that passes validation is trusted from here on. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-ufu-01 | Tampering | `UpdateBoardRequestDTO.name`, `UpdateTaskRequestDTO.title`, `UpdateSubtaskRequestDTO.title`, `SignupRequestDTO.displayName` | medium | mitigate | The defect itself: a whitespace-only identifier persists and renders as an unlabelled board/task/subtask/user, and a whitespace display name is effectively an impersonation-adjacent blank identity. Closed by `@OptionalNotBlank` on all four fields (Tasks 1-2), each with an HTTP-tier 400 regression test. |
| T-ufu-02 | Denial of Service | `@OptionalNotBlank`'s composed `@Pattern` | low | mitigate | Constraint evaluation order is unspecified, so the regex may run before the sibling `@Size(max = …)` and must be safe on unbounded input. `.*\S.*` has no nested quantifier and backtracks linearly (O(n)) on a worst-case all-whitespace input — analysed in `<approach_analysis>`, not assumed. |
| T-ufu-03 | Information disclosure | Validation error envelope | low | accept | The default violation message is generic and names no internal identifier; the RFC 7807 `errors` map keys on the public field name, which the API contract already exposes. No new information is disclosed relative to the existing per-field constraints. |
| T-ufu-04 | Tampering | Existing exact-violation-count assertions | low | mitigate | Adding a second independent constraint to a field changes violation counts for values that violate both. Pre-surveyed (no existing exact-count assertion uses a blank fixture on an affected field); Task 3's full-suite run is the actual proof. |
| T-ufu-SC | Tampering | npm/pip/cargo installs | n/a | n/a | No package-manager installs in this task — no dependency is added, removed or version-changed. Package Legitimacy Gate does not apply. |
</threat_model>

<verification>
- `./gradlew spotlessCheck` passes.
- `./gradlew test` passes with no test shrinkage against the 417-test baseline recorded in STATE.md.
- A red-then-green transition is recorded, with quoted command output, for each of the four fields
  before/after the annotation is applied.
- `UpdateColumnRequestDTO`'s field annotations are byte-identical to their pre-task state — the only
  change to that file is the added class-level Javadoc.
- The new `[minor]` todo exists and describes the Board optionality question as unfixed.
</verification>

<success_criteria>
- Whitespace-only values are rejected with HTTP 400 on all four fields named in D-01.
- Null/omitted values still pass validation on all four fields.
- One reusable annotation, four application sites, zero `ConstraintValidator` implementations.
- `UpdateColumnRequestDTO` behavior unchanged, rationale documented at the class level.
- `UpdateBoardRequestDTO`'s comment no longer makes a claim contradicted by D-02.
- Source todo closed with a resolution note; one new `[minor]` todo filed; STATE.md updated.
</success_criteria>

<output>
Create `.planning/quick/260811-ufu-resolve-whitespace-only-validation-gap-t/260811-ufu-SUMMARY.md` when done
</output>
