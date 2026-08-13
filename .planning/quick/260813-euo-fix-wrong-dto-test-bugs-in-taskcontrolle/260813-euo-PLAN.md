---
phase: quick-260813-euo
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java
  - src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java
  - src/main/java/com/vrudenko/kanban_board/dto/annotation/SubtaskTitle.java
  - src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java
  - src/main/java/com/vrudenko/kanban_board/dto/board_dto/DeleteBoardByIdRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
  - src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java
  - src/test/java/com/vrudenko/kanban_board/service/ColumnServiceTest.java
  - src/test/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandlerTest.java
  - .planning/STATE.md
autonomous: true
requirements:
  - TODO-260811-TCT
  - TODO-260811-SCT
  - TODO-260811-SUBTITLE
  - TODO-260811-DELDTO
  - TODO-260811-DEADSVC
  - TODO-260812-FLAKE

estimate:
  tokens: 90000
  raw_tokens: 45000
  tasks: 6
  confidence: low

must_haves:
  truths:
    - "TaskControllerTest.UpdateById's invalid-data test fails if @TaskTitle is removed from UpdateTaskRequestDTO.title (D-01) — proven by falsification, not asserted"
    - "SubtaskControllerTest.UpdateById's invalid-data test fails if @SubtaskTitle is removed from UpdateSubtaskRequestDTO.title (D-01) — proven by falsification"
    - "SubtaskTitle's composing @Size names SUBTASK_TITLE_LENGTH_VALIDATION_MESSAGE (D-04), and the @ReportAsSingleViolation behavior that makes it inert is pinned by a test"
    - "DeleteBoardByIdRequestDTO no longer exists and the project still compiles"
    - "TaskService.deleteAllByColumnId no longer exists; the N+1 and cascade-delete properties it guarded remain guarded through the live column-delete entry point (D-02, D-03)"
    - "GlobalExceptionHandlerTest.AccessDeniedTest is deterministic: it uses a fixed board name of 'about' and still passes (D-05)"
    - "./gradlew spotlessCheck test is green with zero test shrinkage relative to the 435-test baseline"
    - "All 6 source todos are in .planning/todos/completed/ with a Resolution section"
  artifacts:
    - src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java
    - .planning/quick/260813-euo-fix-wrong-dto-test-bugs-in-taskcontrolle/260813-euo-SUMMARY.md
  key_links:
    - "UpdateTaskRequestDTO.title @TaskTitle @Size(min=3) -> MethodArgumentNotValidException -> 400 (exercised for the first time by Task 1)"
    - "UpdateSubtaskRequestDTO.title @SubtaskTitle @Size(min=3) -> MethodArgumentNotValidException -> 400 (exercised for the first time by Task 2)"
    - "ColumnService.deleteById -> TaskService.deleteAllByColumn (the surviving live cascade path, and the only one after Task 4)"
    - "fk_tasks_column (no ON DELETE CASCADE) -> a successful column delete is itself proof the task cascade ran"
---

<objective>
Close six independently-confirmed, mechanical findings in one pass: two wrong-DTO test bugs that
make update-title validation untested, one wrong message constant, two pieces of dead code, and one
flaky assertion.

Purpose: Every one of these was found by an audit, written up with its evidence, and left unfixed
because it fell outside the scope of the task that found it. Each is small; together they are a
backlog. Two of them (the wrong-DTO tests) are not cosmetic — update-time title validation on tasks
and subtasks is currently proven by nothing, and would pass its test suite if silently broken.

Output: Six todos closed, two genuinely-new validation assertions with proven teeth, ~90 lines of
dead code removed, one flake made deterministic.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@docs/CODE_STYLE.md

Source todos (read each before touching its files — each carries the evidence that confirmed it):
@.planning/todos/pending/2026-08-11-taskcontrollertest-updateby-blank-title-test-uses-wrong-dt.md
@.planning/todos/pending/2026-08-11-subtaskcontrollertest-updateby-blank-title-test-uses-wrong.md
@.planning/todos/pending/2026-08-11-subtasktitle-composed-annotation-carries-wrong-message-cons.md
@.planning/todos/pending/2026-08-11-delete-dead-deleteboardbyidrequestdto-class.md
@.planning/todos/pending/2026-08-11-taskservice-deleteallbycolumnid-has-zero-production-callers.md
@.planning/todos/pending/2026-08-12-globalexceptionhandlertest-accessdeniedtest-flaky-against-.md
</context>

<facts_established_at_planning_time>

These were verified by reading the code during planning, not assumed. They are the premises the
tasks below rest on; if any turns out false at execution time, stop and report rather than
improvising.

1. `ValidationConstants.MIN_TASK_TITLE_LENGTH` and `MIN_SUBTASK_TITLE_LENGTH` are both `3`;
   `MIN_BOARD_NAME_LENGTH` is `1` and `MAX_BOARD_NAME_LENGTH` is `64`.
2. The whitespace-only gap the two test todos mention as a possible alternative target **is already
   closed and already tested.** `UpdateTaskRequestDTO.title` and `UpdateSubtaskRequestDTO.title`
   both carry `@OptionalNotBlank` (quick task 260811-ufu), and both controller tests already have a
   `shouldReturnBadRequest_whenTitleIsWhitespaceOnly` case
   (`TaskControllerTest.java:306`, `SubtaskControllerTest.java:269`). Retargeting the broken tests
   at whitespace-only would therefore duplicate existing coverage — see D-01.
3. `TaskService.deleteAllByColumn(ColumnEntity)` (TaskService.java:324) is package-private,
   `@Transactional`, and has two live production callers: `ColumnService.deleteAllByBoardId`
   (ColumnService.java:77) and `ColumnService.deleteById` (ColumnService.java:278).
4. `ColumnServiceTest.DeleteByIdTest.shouldCostSameQueryCount_regardlessOfTaskCountInColumn`
   (ColumnServiceTest.java:340) already proves the exact N+1 property that
   `TaskServiceTest.DeleteAllByColumnIdQueryCountTest` was written for — through the live public
   entry point, with a 2-vs-8-task comparison, asserting exact equality rather than a `+2` offset.
   Its own Javadoc (line 333-338) states nothing before it exercised the property at the
   column-delete entry point. This is why D-02 can delete the older test rather than retarget it.
5. `V1__init.sql:33` declares `fk_tasks_column FOREIGN KEY (column_id) REFERENCES columns (id)`
   with **no** `ON DELETE CASCADE` and no `DEFERRABLE`. A column delete that left its tasks behind
   would therefore raise a PostgreSQL FK violation. This is the mechanism D-03 relies on.
6. `AbstractAppTest.mockColumns` / `mockPopulatedColumn` are `ColumnResponseDTO`, not entities — a
   test cannot hand a `ColumnEntity` to `deleteAllByColumn` without a repository lookup. This is
   why D-02/D-03 route through `columnService.deleteById` instead.
7. Todo-completion convention (from `git log --diff-filter=R -- .planning/todos/`): append a
   `## Resolution` section to the todo body, then `git mv` it from `pending/` to `completed/`, in a
   `docs(quick-260813-euo): ...` commit.

</facts_established_at_planning_time>

<design_decisions>

Per `.claude/CLAUDE.md`'s planning directive, each non-obvious fork below carries two considered
alternatives, a trade-off matrix, and its performance/correctness implications.

### D-01 — What "invalid data" should the rewritten update tests actually assert?

| Approach | Pros / Cons | Why picked |
|---|---|---|
| **A. Too-short title (2 chars) + valid version** ✅ | **+** Exercises `@TaskTitle`/`@SubtaskTitle`'s `@Size(min=3)`, which no HTTP-tier test currently touches. **+** The only violation in the payload, so the 400 is unambiguous. **−** Does not test the "most interesting" invalid case the todo mused about. | **Picked.** Fact 2 shows the whitespace-only case is already covered by a sibling test in the same nested class; picking it would move the bug rather than fix it (a duplicate test still leaves `@Size` unproven). This is the todo's own primary recommendation. |
| B. Whitespace-only title | **+** The todo's stated preference *if* the `OptionalNotBlank` gap were still open. **−** It is not open, and this exact assertion already exists ~50 lines below in both files. | Rejected: duplicates `shouldReturnBadRequest_whenTitleIsWhitespaceOnly`. |
| C. Over-long title (33 chars) | **+** Also exercises `@Size`, at the `max` bound. **−** Equivalent information to A at higher payload cost, and `MAX` is a less likely regression target than `MIN`. | Rejected as equivalent-but-noisier. Noted as the natural second case if coverage is ever extended. |

**Non-obvious risk this closes:** the current test passes *because* of a missing-`version`
`@NotNull` trip, which `shouldReturnBadRequest_whenVersionIsMissing` in the same class already
asserts. So today the suite has two tests proving one property and zero proving the other. Task 1
and Task 2 therefore each end with a falsification step — remove the title constraint, watch the
test go red, restore it — because a rewritten test that still passes for the wrong reason would be
indistinguishable from a fixed one.

### D-02 — What happens to `TaskServiceTest.DeleteAllByColumnIdQueryCountTest` when its method dies?

| Approach | Pros / Cons | Why picked |
|---|---|---|
| **A. Delete it, citing the superseding test by name** ✅ | **+** Fact 4 establishes `ColumnServiceTest.shouldCostSameQueryCount_regardlessOfTaskCountInColumn` already proves the same property, better (live entry point, exact equality, larger spread). **+** Zero coverage lost. **−** Requires trusting fact 4, so the executor must re-verify it by reading the test. | **Picked.** This is not "delete a test to make a refactor easy" — it is removing a strictly weaker duplicate of a test that already exists. The executor re-verifies before deleting. |
| B. Retarget it to `columnService.deleteById` | **+** Preserves the file's test count. **−** Produces a byte-for-byte weaker copy of an existing `ColumnServiceTest` case, in the wrong class (it would be a `ColumnService` test living in `TaskServiceTest`, against `docs/CODE_STYLE.md` rule 4's which-package rule). | Rejected as deliberate duplication. |
| C. Keep `deleteAllByColumnId` alive just to keep the test | Rejected outright — that is the dead code this task exists to remove, and the source todo's option 2 (keep + document) was already declined by the task framing. | — |

**Performance property at stake:** the guarded invariant is that a column delete costs a
*constant* number of prepared statements regardless of task count (measured at 33 queries for 8
tasks before the original batching fix). That invariant is preserved — only its weaker second
witness is removed.

### D-03 — What happens to `TaskServiceTest.DeleteAllByColumnIdTest.shouldDeleteAll_whenTasksExist`?

This one is *not* duplicated anywhere: `ColumnServiceTest.DeleteByIdTest` currently has three cases
(not-found, not-owned, query-count) and none asserts that a column delete actually removes its
tasks. Deleting it would be real coverage loss, which this plan does not permit.

| Approach | Pros / Cons | Why picked |
|---|---|---|
| **A. Migrate to `ColumnServiceTest.DeleteByIdTest`, asserting non-zero tasks before + delete succeeds, with the FK as the proof mechanism** ✅ | **+** Uses only existing public service reads. **+** Fact 5 makes it airtight: with no `ON DELETE CASCADE` on `fk_tasks_column`, a column delete that skipped its tasks would raise a PostgreSQL FK violation, so completion *is* the assertion. **+** Lands in the class that owns the behavior (CODE_STYLE rule 4). **−** The proof is indirect and needs a Javadoc to be legible. | **Picked.** The Javadoc is the cost, and it is worth paying — the alternative mechanisms all cost more. |
| B. Inject `TaskRepository` into the test and assert `findAllByColumnId` is empty | **+** Most direct possible assertion. **−** Introduces the first repository dependency in a service test, and `LayeringArchTest` scopes one of its rules to `com.vrudenko.kanban_board.service..` — a test class in that same package may or may not be in the ArchUnit import set, which turns a two-line assertion into an architecture-rule investigation. | Rejected: disproportionate risk for a mechanical cleanup. |
| C. Call `deleteAllByColumn(ColumnEntity)` directly from the test | **−** Needs a `ColumnEntity` the fixtures do not expose (fact 6), **and** Spring's proxy-based `@Transactional` is applied to public methods only, so the package-private method's own `@Transactional` would not take effect — `entityManager.flush()` would run with no active transaction. | Rejected: silently broken. |

### D-04 — `SubtaskTitle`: fix the constant only, or also lock in why it is inert?

Fix the constant (the todo's ask) **and** add one small test pinning that `@ReportAsSingleViolation`
makes the composing `@Size` message unreachable. Alternative considered: fix the constant alone, per
the todo's own "there is no meaningful RED-then-GREEN test here" note. Picked the former because the
todo's *reason* for the fix is "a future reader has no way to know it's inert without re-deriving
the interaction from scratch" — a test that asserts the rendered message is `SubtaskTitle`'s own
default answers that permanently, and goes red the day someone removes `@ReportAsSingleViolation`
and silently changes the client-visible text. It costs one small test class and no production code.

### D-05 — How to de-flake `GlobalExceptionHandlerTest.AccessDeniedTest`

| Approach | Pros / Cons | Why picked |
|---|---|---|
| **A. Fixed board name `"about"` + pin `type`, then leak-check every *other* field** ✅ | **+** Deterministic: the collision case now runs on every build instead of ~1-in-N. **+** Keeps whole-body leak coverage; excludes exactly one field, and pins that field's value so the exclusion cannot silently widen. **−** Slightly more code than a one-line change. | **Picked.** It fixes the flake *and* converts the unlucky draw into a permanent regression test for the very collision that caused it. `"about"` is 5 chars, inside `[1, 64]`, so it is a valid board name (fact 1). |
| B. Scope the assertion to the `detail` field only (the todo's suggestion) | **+** One line. **−** Silently stops checking `title`, `instance`, `code` and any future extension property for leaks — narrowing a security assertion to fix a cosmetic flake. **−** Still random, so the collision case is never actually exercised. | Rejected: trades away real coverage. |
| C. Seed/stub `dataFactory` to a fixed word | **+** Deterministic. **−** Adds a test seam to `AbstractAppTest` for one test's benefit; a literal name achieves the same thing with less machinery. | Rejected as over-built. |

**Security note (why B is not merely stylistic):** this test is the only assertion that a 403 on
another user's board does not echo that board's name back to the caller. Narrowing it to `detail`
would leave `title`, `instance` and every future ProblemDetail extension property unguarded against
a leak. A is the only option that removes the flake without removing coverage.

### Cross-cutting notes

- **No production behavior changes.** The only `src/main` edits are a message constant that is
  provably never rendered (D-04), and the deletion of two symbols with zero production callers.
  No endpoint, response shape, query, or validation outcome changes.
- **No new dependencies.** No `npm`/`pip`/`cargo`/Gradle dependency is added, so no package
  legitimacy audit applies.
- **Test-count arithmetic.** Baseline is 435 (STATE.md). Expected delta: +1 (D-04's new test),
  −1 (D-02's deleted query-count test), 0 (D-03 migrates rather than removes). Net **436**. Any
  other final number means something was dropped — Task 6 treats that as a failure, not a surprise.

</design_decisions>

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Fix TaskControllerTest.UpdateById's wrong-DTO test and prove it has teeth</name>
  <files>src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java</files>
  <read_first>
    - `.planning/todos/pending/2026-08-11-taskcontrollertest-updateby-blank-title-test-uses-wrong-dt.md`
    - `src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java` lines 235-330 —
      the target test at 236-255 plus its two neighbours (`whenVersionIsMissing` at 284,
      `whenTitleIsWhitespaceOnly` at 306) that establish what is already covered.
    - `src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java`
  </read_first>
  <behavior>
    - A PUT to the task update endpoint carrying a valid `version` and a 2-character `title`
      returns 400, and the 400 is attributable to the title, not to a missing field.
    - The same request with a valid-length title does not return 400 (implicitly held by the
      existing passing tests in this nested class — do not add a redundant case for it).
    - Removing `@TaskTitle` from `UpdateTaskRequestDTO.title` makes this test fail.
  </behavior>
  <action>
    This is the tracer: it establishes the exact shape Task 2 replicates, and it proves the whole
    constraint chain (DTO annotation to validator to 400) end-to-end before that shape is copied.

    Rewrite `testWithAuthenticatedUser_shouldReturnBadRequest_whenDataIsInvalid` (currently
    TaskControllerTest.java:236-255) to build its request body from `UpdateTaskRequestDTO.builder()`
    instead of `SaveTaskRequestDTO.builder()`, matching every other test in the `UpdateById` nested
    class. Set `version` to `mockPopulatedTask.getVersion()` (a valid, current version) and set
    `title` to a 2-character literal — below `ValidationConstants.MIN_TASK_TITLE_LENGTH`, per D-01 —
    so the payload's only constraint violation is `@TaskTitle`'s `@Size` minimum. Leave the
    surrounding arrange/act/assert structure, the `url` construction, the `.andDo(print())` chain
    and the `isBadRequest()` expectation as they are.

    Replace the stale `// Assuming blank name is invalid` line with a comment stating what the
    payload is now designed to violate and why the version is deliberately valid. Do not write a
    comment that names the old DTO type.

    If `SaveTaskRequestDTO` becomes unused in this file, `spotlessApply` removes the import; do not
    hand-edit imports.

    Then falsify the test, per D-01's teeth requirement — a rewritten test that passes for a third
    unrelated reason is worth no more than the one it replaced. Temporarily delete the `@TaskTitle`
    annotation from `UpdateTaskRequestDTO.title`, run this single test method, and confirm it fails
    (the 2-character title now validates, the update succeeds, and the expected 400 does not
    arrive). Restore the annotation, re-run, confirm green, and confirm `git diff --stat` reports
    zero net change under `src/main`. Record both observed outcomes — the failure message and the
    restored-green result — for the summary.
  </action>
  <verify>
    <automated>./gradlew test --tests '*TaskControllerTest' 2>&amp;1 | tail -20</automated>
    <automated>git diff --stat src/main | wc -l</automated>
  </verify>
  <done>
    The test builds an `UpdateTaskRequestDTO` with a valid version and a 2-char title; it passes;
    it was observed failing with `@TaskTitle` removed and passing again once restored; `src/main`
    carries zero net diff from this task.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Apply the same fix to SubtaskControllerTest.UpdateById</name>
  <files>src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java</files>
  <read_first>
    - `.planning/todos/pending/2026-08-11-subtaskcontrollertest-updateby-blank-title-test-uses-wrong.md`
    - `src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java` lines 246-294
    - `src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/UpdateSubtaskRequestDTO.java`
  </read_first>
  <behavior>
    - A PUT to the subtask update endpoint carrying a valid `version` and a 2-character `title`
      returns 400, attributable to the title rather than to a missing field.
    - Removing `@SubtaskTitle` from `UpdateSubtaskRequestDTO.title` makes this test fail.
  </behavior>
  <action>
    Horizontal expansion of the shape Task 1 proved. Rewrite
    `testWithAuthenticatedUser_shouldReturnBadRequest_whenDataIsInvalid`
    (SubtaskControllerTest.java:247-266) to build from `UpdateSubtaskRequestDTO.builder()` with
    `version` set to `mockSubtasks.getFirst().getVersion()` and `title` set to a 2-character literal
    (below `ValidationConstants.MIN_SUBTASK_TITLE_LENGTH`), per D-01.

    Note the DTO difference from Task 1: `UpdateSubtaskRequestDTO`'s `atLeastOneFieldPopulated`
    check is satisfied by `title` or `isCompleted`, and a present-but-too-short title satisfies it —
    so `isCompleted` must be left unset, keeping the `@Size` minimum as the payload's sole
    violation. Setting it would add a second reason the request could be considered well-formed and
    muddy what the 400 proves.

    Falsify identically: temporarily remove `@SubtaskTitle` from `UpdateSubtaskRequestDTO.title`,
    confirm this single test goes red, restore, confirm green and a zero net `src/main` diff.
    Record both observed outcomes.
  </action>
  <verify>
    <automated>./gradlew test --tests '*SubtaskControllerTest' 2>&amp;1 | tail -20</automated>
    <automated>git diff --stat src/main | wc -l</automated>
  </verify>
  <done>
    The test builds an `UpdateSubtaskRequestDTO` with a valid version, a 2-char title and no
    `isCompleted`; it passes; it was observed failing with `@SubtaskTitle` removed and passing
    again once restored.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 3: Correct SubtaskTitle's message constant and pin why it is inert</name>
  <files>src/main/java/com/vrudenko/kanban_board/dto/annotation/SubtaskTitle.java, src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java</files>
  <read_first>
    - `.planning/todos/pending/2026-08-11-subtasktitle-composed-annotation-carries-wrong-message-cons.md`
    - `src/main/java/com/vrudenko/kanban_board/dto/annotation/SubtaskTitle.java`
    - `src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java` — the existing
      validator-tier test pattern in this package; follow its `jakarta.validation.Validator`
      setup and `@Nested` grouping rather than inventing a new one.
  </read_first>
  <behavior>
    - Validating a `SaveSubtaskRequestDTO` with an over-long title yields exactly one violation
      whose message is `SubtaskTitle`'s own default, `"Subtask title cannot be empty"`.
    - The same holds for `UpdateSubtaskRequestDTO` with a valid version.
    - Neither DTO ever renders a board-name-flavored message.
  </behavior>
  <action>
    Per D-04, change the `message` attribute of `SubtaskTitle`'s composing `@Size`
    (SubtaskTitle.java:21-24) so it names `ValidationConstants.SUBTASK_TITLE_LENGTH_VALIDATION_MESSAGE`.
    Nothing else in the annotation changes — `@ReportAsSingleViolation`, the `min`/`max` bounds and
    the `message()` default all stay byte-identical.

    Then create `src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java`, modeled
    on `OptionalNotBlankTest`, that builds a `jakarta.validation.Validator`, validates a
    `SaveSubtaskRequestDTO` and an `UpdateSubtaskRequestDTO` each carrying a title longer than
    `ValidationConstants.MAX_SUBTASK_TITLE_LENGTH`, and asserts that the resulting violation set
    has exactly one element whose message equals the `SubtaskTitle` default. Give the class a
    Javadoc explaining that `@ReportAsSingleViolation` collapses composing-constraint failures onto
    the composed annotation's own default, which is why the inner `@Size` message is unreachable
    from any API response and why this correction is a source-legibility fix rather than a behavior
    change — and that this test goes red if that meta-annotation is ever removed, at which point the
    inner message becomes client-visible and the constant's correctness starts to matter.

    Prove the assertion is real before trusting it: temporarily remove `@ReportAsSingleViolation`
    from `SubtaskTitle`, confirm the new test fails (the rendered message becomes the inner `@Size`
    text), restore it, confirm green. This is the only way to demonstrate the test is pinning the
    behavior it claims to pin, since the constant change itself is observationally inert.
  </action>
  <verify>
    <automated>grep -n 'message = ValidationConstants' src/main/java/com/vrudenko/kanban_board/dto/annotation/SubtaskTitle.java</automated>
    <automated>./gradlew test --tests '*SubtaskTitleMessageTest' --tests '*SubtaskControllerTest' 2>&amp;1 | tail -20</automated>
  </verify>
  <done>
    The grep shows the composing `@Size` naming `SUBTASK_TITLE_LENGTH_VALIDATION_MESSAGE`;
    `SubtaskTitleMessageTest` passes and was observed failing with `@ReportAsSingleViolation`
    removed; no API response text changed.
  </done>
</task>

<task type="auto">
  <name>Task 4: Remove both dead symbols, preserving every property their tests guarded</name>
  <files>src/main/java/com/vrudenko/kanban_board/dto/board_dto/DeleteBoardByIdRequestDTO.java, src/main/java/com/vrudenko/kanban_board/service/TaskService.java, src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java, src/test/java/com/vrudenko/kanban_board/service/ColumnServiceTest.java</files>
  <read_first>
    - `.planning/todos/pending/2026-08-11-delete-dead-deleteboardbyidrequestdto-class.md`
    - `.planning/todos/pending/2026-08-11-taskservice-deleteallbycolumnid-has-zero-production-callers.md`
    - `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java` lines 31-58 and 309-326
    - `src/test/java/com/vrudenko/kanban_board/service/ColumnServiceTest.java` lines 302-400
    - `src/main/java/com/vrudenko/kanban_board/service/TaskService.java` lines 281-336
  </read_first>
  <action>
    Start by re-verifying the two "zero callers" claims with a fresh grep across `src/main` and
    `src/test` — this repo's own precedent (quick tasks 260803-m3i and 260803-ns9) is to re-derive
    that claim immediately before deleting rather than inherit it from a todo. Expected: the only
    hits are the declarations themselves plus `TaskServiceTest`'s three references. If the grep
    disagrees, stop and report.

    **Delete `DeleteBoardByIdRequestDTO.java`.** `BoardController.deleteById` binds a bare
    `@PathVariable`, so board deletion never had a request body to bind. A compile failure would
    falsify the claim; nothing else is expected to move.

    **Delete `TaskService.deleteAllByColumnId`** (TaskService.java:281-286), per option 1 of its
    todo and the precedent of quick task 260802-q6n. Leave the package-private
    `deleteAllByColumn(ColumnEntity)` below it completely untouched — it is live, with two
    `ColumnService` callers, and it is where the batching actually lives.

    Then dispose of the two `TaskServiceTest` nested classes that referenced the deleted method,
    per D-02 and D-03. Do not simply delete them.

    *`DeleteAllByColumnIdQueryCountTest` (lines 36-58) — delete, per D-02.* First re-verify the
    supersession by reading `ColumnServiceTest.DeleteByIdTest.shouldCostSameQueryCount_regardlessOfTaskCountInColumn`
    (line 340) and confirming it asserts the same no-scaling property through
    `columnService.deleteById` on a 2-task and an 8-task column. Only if that holds, delete the
    older test and its explanatory comment block (lines 31-35). If it does not hold, stop and
    report rather than deleting coverage.

    *`DeleteAllByColumnIdTest.shouldDeleteAll_whenTasksExist` (lines 309-326) — migrate, per D-03.*
    This assertion is not duplicated anywhere, so move it rather than drop it. Add a test to
    `ColumnServiceTest.DeleteByIdTest` that captures `taskService.getTaskCountByColumnId` for
    `mockPopulatedColumn` before the delete, asserts it is non-zero, calls
    `columnService.deleteById`, and asserts the call completed without throwing. Give it a Javadoc
    stating that `fk_tasks_column` (declared in `V1__init.sql` with no `ON DELETE CASCADE`) is what
    makes completion a proof: a column delete that left its tasks behind would raise a foreign-key
    violation, so a clean return is the assertion. Remove the now-empty nested class from
    `TaskServiceTest`. `ColumnServiceTest` will need `taskService` injected if it is not already.

    Do not add `@Transactional` to any test, and do not inject a repository into either test class —
    see D-03's rejected options B and C for why both routes are traps here.
  </action>
  <verify>
    <automated>grep -rn 'DeleteBoardByIdRequestDTO\|deleteAllByColumnId' src/main src/test --include=*.java; test $? -eq 1</automated>
    <automated>./gradlew test --tests '*TaskServiceTest' --tests '*ColumnServiceTest' 2>&amp;1 | tail -20</automated>
  </verify>
  <done>
    Both symbols are gone and grep finds no surviving reference; the project compiles;
    `TaskServiceTest` and `ColumnServiceTest` both pass; the column-delete cascade is asserted by a
    named test in `ColumnServiceTest.DeleteByIdTest`; the N+1 property remains guarded by the
    pre-existing query-count test.
  </done>
</task>

<task type="auto">
  <name>Task 5: Make GlobalExceptionHandlerTest.AccessDeniedTest deterministic</name>
  <files>src/test/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandlerTest.java</files>
  <read_first>
    - `.planning/todos/pending/2026-08-12-globalexceptionhandlertest-accessdeniedtest-flaky-against-.md`
    - `src/test/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandlerTest.java` lines 76-111
  </read_first>
  <action>
    Per D-05, apply both halves of the fix — the randomness and the over-broad string match are two
    separate defects that happen to collide.

    First, replace the `dataFactory.getRandomWord(ValidationConstants.MIN_BOARD_NAME_LENGTH + 4)`
    call that names `otherBoard` with a fixed literal board name of `"about"`. It is 5 characters,
    inside the `[1, 64]` bound, so it is a valid name; and it is precisely the value that used to
    trigger the intermittent failure, so the previously-unlucky case now runs on every build.

    Second, replace the raw-body `Assertions.assertThat(response.getContentAsString()).doesNotContain(otherBoard.getName())`
    with a field-scoped check built on the `JsonNode` the test already parses two lines later.
    Assert that the `type` field is exactly `about:blank` — pinning the RFC 7807 boilerplate that
    caused the collision, so the exclusion cannot silently widen if Spring ever starts emitting a
    real type URI — and then iterate every remaining field of the object, skipping only `type`, and
    assert none of their textual values contains the board name. Keep the existing status,
    content-type and `code` assertions unchanged. Give the assertion a failure message naming the
    offending field, so a future leak reports which field leaked rather than dumping the envelope.

    Do not narrow the check to `detail` alone: this is the only test asserting that a 403 on another
    user's board does not echo that board's name, and `title`, `instance` and any future extension
    property need to stay covered (D-05).

    Verify the fix does what the todo asked by running this test class in isolation and confirming
    it passes with the now-guaranteed `"about"` name — which is the todo's suggested verification
    (force the unlucky word, confirm the test still passes correctly), made permanent instead of
    temporary.
  </action>
  <verify>
    <automated>./gradlew test --tests '*GlobalExceptionHandlerTest' 2>&amp;1 | tail -20</automated>
  </verify>
  <done>
    The test uses a fixed `"about"` board name, pins `type` to `about:blank`, leak-checks every
    other field, and passes; the assertion's coverage is no narrower than before.
  </done>
</task>

<task type="auto">
  <name>Task 6: Full gate, close the six todos, record state</name>
  <files>.planning/todos/pending/*.md, .planning/todos/completed/*.md, .planning/STATE.md, .planning/quick/260813-euo-fix-wrong-dto-test-bugs-in-taskcontrolle/260813-euo-SUMMARY.md</files>
  <precondition>Docker daemon is running and can start Testcontainers PostgreSQL and Redpanda containers — the full `test` task needs both, and a stopped daemon surfaces as unrelated container-startup failures rather than an obvious cause.</precondition>
  <action>
    Run the full gate: `./gradlew spotlessApply` first (this repo's formatting is enforced, not
    advisory), then `./gradlew spotlessCheck test`. The full suite ran ~411s at last measurement, so
    invoke it with an extended timeout rather than the 120s default; if it approaches the 600s cap,
    move it to the background and poll for completion instead of re-running it.

    Read the reported test count. Baseline is 435 (STATE.md); the expected total is **436** —
    `SubtaskTitleMessageTest` adds one, D-02 removes one, D-03 migrates one without changing the
    count. Any other number means coverage moved somewhere unplanned: investigate and report it
    rather than accepting it. Do not proceed to the todo moves on a red or short suite.

    With the gate green, close all six source todos following the convention in fact 7: append a
    `## Resolution` section to each todo body recording what was actually done, the evidence
    observed (for the two controller-test todos, the falsification result specifically), and any
    decision that diverged from the todo's own suggestion — the two that need this most are the
    D-01 choice of a too-short title over the whitespace-only case the todos favored (because that
    case is already covered), and the D-05 choice to keep whole-body leak coverage rather than
    narrowing to `detail` as the todo suggested. Then `git mv` each file from
    `.planning/todos/pending/` to `.planning/todos/completed/`.

    The six files are the ones listed in this plan's `<context>` block.

    Write `260813-euo-SUMMARY.md` in this plan's directory, and update `.planning/STATE.md`:
    add a `[Quick/260813-euo]` entry to Accumulated Context > Decisions summarizing the six fixes
    and the two design calls that diverged from the todos' own suggestions, remove the six closed
    items from Pending Todos, and refresh `last_activity` / `last_activity_desc`.
  </action>
  <verify>
    <automated>./gradlew spotlessCheck test 2>&amp;1 | tail -30</automated>
    <automated>ls .planning/todos/completed/ | grep -c '2026-08-11-taskcontrollertest-updateby\|2026-08-11-subtaskcontrollertest-updateby\|2026-08-11-subtasktitle-composed\|2026-08-11-delete-dead-deleteboardbyid\|2026-08-11-taskservice-deleteallbycolumnid\|2026-08-12-globalexceptionhandlertest-accessdenied'</automated>
  </verify>
  <done>
    `spotlessCheck` and `test` are both green with 436 tests and zero failures; all six todo files
    are under `.planning/todos/completed/` each carrying a `## Resolution` section; SUMMARY.md and
    STATE.md are written.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client → REST API (PUT task/subtask update) | Untrusted title/version payload crosses here; Bean Validation is the gate |
| server → client (403 error envelope) | Another user's data must not cross outward in the ProblemDetail body |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-euo-01 | Tampering | `UpdateTaskRequestDTO.title` / `UpdateSubtaskRequestDTO.title` `@Size` enforcement | medium | mitigate | Tasks 1-2 replace tests that pass for an unrelated reason with tests that fail when the title constraint is removed — verified by falsification, so a future regression in update-time title validation is caught rather than silently tolerated |
| T-euo-02 | Information Disclosure | `GlobalExceptionHandler` 403 ProblemDetail envelope | medium | mitigate | Task 5 keeps the leak check spanning every envelope field except a value-pinned `type`, rather than narrowing it to `detail`; the previously-random collision case now runs deterministically on every build |
| T-euo-03 | Denial of Service | `TaskService.deleteAllByColumn` batching (N+1 on column delete) | low | accept | Property is unchanged by this task and remains guarded by `ColumnServiceTest.shouldCostSameQueryCount_regardlessOfTaskCountInColumn`; D-02 removes only a weaker duplicate witness, after re-verifying the stronger one |
| T-euo-04 | Elevation of Privilege | Deletion of `TaskService.deleteAllByColumnId`'s ownership re-verification | low | accept | The deleted method's `verifyOwnershipOfColumn` call had zero production callers; every live cascade path reaches `deleteAllByColumn` through `ColumnService`, which performs its own ownership verification first — no check is removed from any reachable path |

No package-manager installs occur in this plan, so no package-legitimacy gate applies.
</threat_model>

<verification>
- `./gradlew spotlessCheck` green.
- `./gradlew test` green at 436 tests, 0 failures (435 baseline +1 −1 +0, per the cross-cutting note in `<design_decisions>`).
- `git diff --stat src/main` shows changes confined to: one message constant in `SubtaskTitle.java`, the deleted `DeleteBoardByIdRequestDTO.java`, and the deleted `deleteAllByColumnId` method in `TaskService.java`. No other production file is modified.
- `grep -rn 'DeleteBoardByIdRequestDTO\|deleteAllByColumnId' src/main src/test` returns nothing.
- Falsification results recorded for Task 1, Task 2 and Task 3 — each observed red, then green after restore.
</verification>

<success_criteria>
- Update-time title validation on both tasks and subtasks is proven by a test that demonstrably fails when the constraint is removed.
- `SubtaskTitle`'s source no longer carries a misleading cross-domain constant, and the reason it was inert is captured by a test rather than by a comment.
- Two dead symbols are gone with every property their tests guarded still guarded, by a named test in each case.
- `GlobalExceptionHandlerTest.AccessDeniedTest` cannot flake on this collision again, and its leak coverage is no narrower than before.
- All six source todos are closed with a Resolution section, following the repo's existing convention.
</success_criteria>

<output>
Create `.planning/quick/260813-euo-fix-wrong-dto-test-bugs-in-taskcontrolle/260813-euo-SUMMARY.md` when done
</output>
