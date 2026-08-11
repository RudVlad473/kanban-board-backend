# 260811-qru: DTO and Controller Test Coverage Audit — Findings

Read-only audit. No file under `src/` was modified to produce this document. Every finding in
Section 4 carries file:line evidence and, where feasible, empirical verification (a throwaway
`jakarta.validation.Validator` harness run outside `src/`, compiled against `build/classes/java/main`
and the resolved Hibernate Validator 8.0.2.Final classpath — not assumed from reading annotations
alone). Verification commands and raw output are quoted inline next to the findings they support.

## Section 1 — Binding audit (QRU-01)

Enumerated directly from source: every `@PostMapping`/`@PutMapping`/`@PatchMapping` handler across
`BoardController`, `ColumnController`, `TaskController`, `SubtaskController`, `TaskMoveController`,
`UserController`, `ActivityController` (all seven under `controller/`) and `AuthenticationController`
(under `security/`).

| Controller | Handler | file:line | DTO parameter | `@RequestBody`? | `@Valid`? |
|---|---|---|---|---|---|
| `BoardController` | `save` | `controller/BoardController.java:49-56` | `SaveBoardRequestDTO` | yes | yes |
| `BoardController` | `updateById` | `controller/BoardController.java:66-72` | `UpdateBoardRequestDTO` | yes | yes |
| `BoardController` | `addColumnByBoardId` | `controller/BoardController.java:74-82` | `SaveColumnRequestDTO` | yes | yes |
| `ColumnController` | `addTaskByColumnId` | `controller/ColumnController.java:45-53` | `SaveTaskRequestDTO` | yes | yes |
| `ColumnController` | `updateById` | `controller/ColumnController.java:55-61` | `UpdateColumnRequestDTO` | yes | yes |
| `ColumnController` | `reorder` | `controller/ColumnController.java:73-79` | `ReorderColumnRequestDTO` | yes | yes |
| `TaskController` | `updateById` | `controller/TaskController.java:51-57` | `UpdateTaskRequestDTO` | yes | yes |
| `TaskController` | `addSubtaskByTaskId` | `controller/TaskController.java:59-67` | `SaveSubtaskRequestDTO` | yes (fixed by quick task 260811-me4) | yes |
| `SubtaskController` | `updateById` | `controller/SubtaskController.java:53-59` | `UpdateSubtaskRequestDTO` | yes | yes |
| `TaskMoveController` | `moveToColumn` | `controller/TaskMoveController.java:33-39` | `MoveTaskRequestDTO` | yes | yes |
| `UserController` | `updateTheme` | `controller/UserController.java:44-48` | `UpdateThemeRequestDTO` | yes | yes |
| `AuthenticationController` | `signin` | `security/AuthenticationController.java:70-72` | `SigninRequestDTO` | yes | yes |
| `AuthenticationController` | `signup` | `security/AuthenticationController.java:109-111` | `SignupRequestDTO` | yes | yes |
| `ActivityController` | — | `controller/ActivityController.java:40-46` | none — `findAllByBoardId` is the only handler, `@GetMapping` with a `Pageable`, no DTO parameter, no mutating verb | n/a | n/a |

**Exhaustiveness check against the planning inventory:** the source enumeration above matches the
planning inventory's 14-row table exactly — same 13 DTO-carrying mutating handlers, same
"`ActivityController` has no mutating handler" conclusion. No discrepancy found; the planning
inventory's grep was accurate.

**Non-DTO mutating handlers**, recorded so the table above is provably exhaustive rather than
filtered: every `@DeleteMapping` in the codebase (`BoardController.deleteById`,
`ColumnController.deleteById`, `TaskController.deleteById`, `SubtaskController.deleteById`) takes
only `@PathVariable` identifiers, no request-body DTO — correctly excluded from this table, no
`@RequestBody`/`@Valid` question applies to them.

**Result: zero findings.** All 13 DTO-parameter mutating handlers, across all eight controllers,
carry both `@RequestBody` and `@Valid`. `AuthenticationController` (in `security/`, not
`controller/`) is fully in scope and clean. The binding failure mode that produced quick task
260811-me4's defect does not recur elsewhere.

## Section 1b — Test-side binding audit

For each of the 13 mutating handlers, where its request body is actually driven from at test time,
and whether that drive is a real JSON body, a query/form param, or a direct service call.

| Handler | Test class | Package/tier | Body mechanism |
|---|---|---|---|
| `BoardController.save` | `BoardCreationE2ETest.CreateBoard` | root package, `@Tag("realSocket")` (`AbstractAppE2ETest`) | real JSON body (RestAssured `.body(dto)`) |
| `BoardController.updateById` | `BoardControllerTest.UpdateById` | `controller/` (`AbstractAppTest` + MockMvc) | real JSON body |
| `BoardController.addColumnByBoardId` | `BoardControllerTest.AddColumnByBoardId` | `controller/` | real JSON body |
| `ColumnController.addTaskByColumnId` | `ColumnControllerTest.AddTaskByColumnId` | `controller/` | real JSON body |
| `ColumnController.updateById` | `ColumnControllerTest.UpdateById` | `controller/` | real JSON body |
| `ColumnController.reorder` | `ColumnOrderingTest` | root package (`AbstractAppMockMvcTest`) | real JSON body |
| `TaskController.updateById` | `TaskControllerTest.UpdateById` | `controller/` | real JSON body |
| `TaskController.addSubtaskByTaskId` | `TaskControllerTest.AddSubtaskByTaskId` | `controller/` | real JSON body (plus a deliberate negative test, `shouldReturnBadRequest_whenTitleIsSentAsQueryParamWithNoBody`, using `.param(...)` on purpose to prove the fixed bug stays fixed) |
| `SubtaskController.updateById` | `SubtaskControllerTest.UpdateById` | `controller/` | real JSON body |
| `TaskMoveController.moveToColumn` | `TaskMoveTest.MoveToColumn` | `e2e/task/` (`AbstractAppMockMvcTest`) | real JSON body |
| `UserController.updateTheme` | `ThemePersistenceTest.UpdateTheme` | root package (`AbstractAppMockMvcTest`) | real JSON body |
| `AuthenticationController.signin` | `AuthenticationTest.Signin` | `security/` (`AbstractAppMockMvcTest`) | real JSON body |
| `AuthenticationController.signup` | `AuthenticationTest.Signup` | `security/` (`AbstractAppMockMvcTest`) | real JSON body |

**Form-parameter grep across the controller and e2e test trees:** `grep -rln "\.param(" src/test/java/com/vrudenko/kanban_board --include="*.java"` returns exactly one file, `TaskControllerTest.java` — and that single usage is the deliberate negative test named above, not a binding workaround. No other test in the suite drives a mutating endpoint via query/form params.

**Result: every mutating handler has real HTTP-JSON-body coverage.** The specific blind spot that
hid the 260811-me4 defect (a controller test silently working around a binding bug via
`.param(...)`) does not recur. Two related, but distinct, test-quality defects were found instead —
see F-02 and F-03 below.

## Section 2 — Validation-annotation audit (QRU-02)

### Composed custom annotations, re-verified against `dto/annotation/` (not trusted from the planning inventory)

| Annotation | Composes | Message constant used | Implies non-blank? |
|---|---|---|---|
| `AppEmail` (`dto/annotation/AppEmail.java:20`) | `@NotBlank` + `@Email` | own literal + `@Email` default | yes |
| `Password` (`dto/annotation/Password.java:27-35`) | `@NotBlank` + `@Size` + `@Pattern` | `ValidationConstants.PASSWORD_LENGTH_VALIDATION_MESSAGE` (correct) | yes |
| `BoardName` (`dto/annotation/BoardName.java:18-24`) | `@Size` + `@Pattern("^[a-zA-Z0-9 ]*$")` | `ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE` (correct — this constant is the board-name message, confusingly generic name aside) | **no** |
| `DisplayName` (`dto/annotation/DisplayName.java:21-26`) | `@Size` + `@Pattern("^[a-zA-Z ]*$")` | `ValidationConstants.DISPLAY_NAME_LENGTH_VALIDATION_MESSAGE` (correct) | **no** |
| `TaskTitle` (`dto/annotation/TaskTitle.java:17-20`) | `@Size` only | `ValidationConstants.TASK_TITLE_LENGTH_VALIDATION_MESSAGE` (correct) | **no** |
| `SubtaskTitle` (`dto/annotation/SubtaskTitle.java:16-24`) | `@Size` only | `ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE` (**wrong** — see F-03) | **no** |
| `Description` (`dto/annotation/Description.java:16-25`) | `@Size` only | `ValidationConstants.TASK_DESCRIPTION_LENGTH_VALIDATION_MESSAGE` (correct) | no (correct — description is legitimately optional) |

`@Pattern`'s `*` quantifier admits the empty string on its own, but every `@Size(min=...)` in this
codebase has `min >= 1` (`ValidationConstants`: board=1, display=3, column=3, task=3, subtask=3),
so an empty string ("") is already rejected by `@Size` alone wherever it appears. The gap this
section actually found is **whitespace-only content at or above the minimum length** — see F-06.

### Field-by-field effective-constraint table, all fourteen `Save*RequestDTO`/`Update*RequestDTO` classes

| DTO | Field | Declared | Effective constraint (composed, expanded) | Rule 6 shape (`Update*` only) |
|---|---|---|---|---|
| `SaveBoardRequestDTO` (`board_dto/SaveBoardRequestDTO.java:17-18`) | `name` | `@NotBlank` + `@BoardName` | non-null, non-blank, 1-64 chars, alnum+space | n/a (Save) |
| `UpdateBoardRequestDTO` (`board_dto/UpdateBoardRequestDTO.java:21,27`) | `name`, `version` | `@BoardName`; `@NotNull Long version` | `name`: nullable, but if present 1-64 chars alnum+space **with no blank guard** (F-06); `version`: mandatory | `@JsonInclude(NON_NULL)` yes, `@NotNull version` yes, no `atLeastOneFieldPopulated()` (correct — single optional field) |
| `DeleteBoardByIdRequestDTO` (`board_dto/DeleteBoardByIdRequestDTO.java:15`) | `id` | `@NotNull @UUID` | dead code — see F-10 | n/a |
| `SaveColumnRequestDTO` (`column_dto/SaveColumnRequestDTO.java:18-22`) | `name` | `@NotBlank` + inline `@Size(3,32, message=COLUMN_NAME_LENGTH_VALIDATION_MESSAGE)` (was `NAME_LENGTH_VALIDATION_MESSAGE` — **fixed, see F-04**) | non-null, non-blank, 3-32 chars, correct message (post-fix) | n/a (Save) |
| `UpdateColumnRequestDTO` (`column_dto/UpdateColumnRequestDTO.java:21-27`) | `name`, `version` | `@NotBlank` + inline `@Size(3,32, message=COLUMN_NAME_LENGTH_VALIDATION_MESSAGE)`; `@NotNull Long version` | `name`: **mandatory even though this is an Update DTO** — see F-06; `version`: mandatory | `@JsonInclude(NON_NULL)` yes, `@NotNull version` yes, no `atLeastOneFieldPopulated()` (correct — single field, and here it's not even independently optional) |
| `ReorderColumnRequestDTO` (`column_dto/ReorderColumnRequestDTO.java:22-26`) | `version`, `targetPosition` | `@NotNull Long version`; `@NotNull @Min(0) Integer targetPosition` | both mandatory | not named `Update*RequestDTO`; both fields mandatory (deliberate, documented at class level vs. sibling `MoveTaskRequestDTO`) — not a rule-6 shape at all, correctly so |
| `SaveTaskRequestDTO` (`task_dto/SaveTaskRequestDTO.java:19-25`) | `title`, `description` | `@NotBlank` + inline `@Size(3,32, message=TASK_TITLE_LENGTH_VALIDATION_MESSAGE)`; `@Description` (optional) | `title`: non-null, non-blank, 3-32 chars, correct message; `description`: optional, 1-512 chars if present | n/a (Save) |
| `UpdateTaskRequestDTO` (`task_dto/UpdateTaskRequestDTO.java:23-35`) | `title`, `description`, `version` | `@TaskTitle` (optional); `@Description` (optional); `@NotNull Long version` | `title`: nullable, but if present 3-32 chars **with no blank guard** (F-06); `description`: nullable, 1-512 chars if present; `version`: mandatory | `@JsonInclude(NON_NULL)` yes, `@NotNull version` yes, `atLeastOneFieldPopulated()` present (correct — 2 independently optional fields) |
| `MoveTaskRequestDTO` (`task_dto/MoveTaskRequestDTO.java:18-26`) | `targetColumnId`, `version`, `targetPosition` | `@NotBlank targetColumnId`; `@NotNull Long version`; `@Min(0)` nullable `targetPosition` | `targetColumnId`/`version` mandatory, `targetPosition` deliberately nullable (documented, D-04) | not named `Update*RequestDTO`; single genuinely-optional field with a meaningful null semantic, documented at class level as intentionally asymmetric with `ReorderColumnRequestDTO` — reasoned deviation, not a finding |
| `SaveSubtaskRequestDTO` (`subtask_dto/SaveSubtaskRequestDTO.java:16`) | `title` | `@SubtaskTitle` only | nullable, no blank guard at all when null-check is absent — **already tracked, F-01** | n/a (Save) |
| `UpdateSubtaskRequestDTO` (`subtask_dto/UpdateSubtaskRequestDTO.java:22-37`) | `title`, `isCompleted`, `version` | `@SubtaskTitle` (optional); `isCompleted` (optional, no annotation — boolean, nothing to validate); `@NotNull Long version` | `title`: nullable, but if present 3-32 chars **with no blank guard** (F-06); `version`: mandatory | `@JsonInclude(NON_NULL)` yes, `@NotNull version` yes, `atLeastOneFieldPopulated()` present (correct — 2 independently optional fields) |
| `SigninRequestDTO` (`user_dto/SigninRequestDTO.java:16-18`) | `email`, `password` | `@AppEmail`; `@Password` | both non-null, non-blank, format-checked | n/a (not Save/Update shape — no entity backing, no version) |
| `SignupRequestDTO` (`user_dto/SignupRequestDTO.java:17-21`) | `displayName`, `email`, `password` | `@DisplayName` (optional); `@AppEmail`; `@Password` | `displayName`: nullable, but if present 3-32 chars alnum+space **with no blank guard** (F-06); `email`/`password`: non-null, non-blank | n/a |
| `UpdateThemeRequestDTO` (`user_dto/UpdateThemeRequestDTO.java:35`) | `theme` | `@NotNull ThemePreference` | mandatory enum | Documented, reasoned exemption from rule 6's `@JsonInclude`/`version` shape (Javadoc lines 11-29) — not a finding, per the plan's exemption instruction |

## Section 3 — Coverage classification (QRU-03)

Per docs/CODE_STYLE.md rule 4's which-package rule: `controller/*ControllerTest.java` proves one
HTTP endpoint's contract; `*E2ETest`-suffixed/tracer classes elsewhere are correct when the concern
genuinely needs real infrastructure or multi-request/concurrent flows; a "genuine cross-service flow
test" covering an endpoint is not automatically a gap.

| Endpoint | Controller-tier (`controller/*ControllerTest.java`)? | Actual coverage location | Rule-4 assessment |
|---|---|---|---|
| `POST /boards` | no | `BoardCreationE2ETest` (`@Tag("realSocket")`, `AbstractAppE2ETest`) | Real JSON-body HTTP coverage exists. The class is a documented tracer (Javadoc: "Tracer proving GAP-01 end to end") and its `ConcurrentCreate` nested class needs genuine socket concurrency, which does need the e2e tier per rule 4. The plain happy-path/validation/auth cases in `CreateBoard` do not themselves need the real-socket tier, but bundling them with the concurrency test that does is consistent with this codebase's established tracer-class convention (`ThemePersistenceTest`, `TaskMoveTest`, `SubtaskLockingTest` are the same shape) — not a gap, see F-09. |
| `PUT /boards/{id}` | yes | `BoardControllerTest.UpdateById` | Covered, correctly tiered. |
| `POST /boards/{id}/columns` | yes | `BoardControllerTest.AddColumnByBoardId` | Covered, correctly tiered. |
| `POST /columns/{id}` (add task) | yes | `ColumnControllerTest.AddTaskByColumnId` | Covered, correctly tiered. |
| `PUT /columns/{id}` | yes | `ColumnControllerTest.UpdateById` | Covered, correctly tiered. |
| `PATCH /columns/{id}/reorder` | no | `ColumnOrderingTest` (root package, `AbstractAppMockMvcTest`) | Real JSON-body HTTP coverage exists; this class also proves position-contiguity invariants across create/reorder/delete, which is a genuine cross-endpoint flow concern rule 4 assigns to the flow tier, not a single-endpoint contract gap. |
| `PUT /tasks/{id}` | yes | `TaskControllerTest.UpdateById` | Covered, correctly tiered. |
| `POST /tasks/{id}/subtasks` | yes | `TaskControllerTest.AddSubtaskByTaskId` | Covered, correctly tiered (this is the endpoint 260811-me4 fixed). |
| `PUT /subtasks/{id}` | yes | `SubtaskControllerTest.UpdateById` | Covered, correctly tiered. |
| `PATCH /tasks/{id}/move` | no | `TaskMoveTest` (`e2e/task/`, `AbstractAppMockMvcTest`) | Real JSON-body HTTP coverage exists, including stale-version/concurrent-conflict/cross-board/unowned-target/missing-version/unknown-id cases — thorough single-endpoint contract coverage, just not under `controller/`. `TaskMoveController`'s own class Javadoc explains why it cannot live inside `TaskController` (flat vs. nested route), which is also why it has no sibling `TaskMoveControllerTest`. |
| `PUT /users/me/theme` | no | `ThemePersistenceTest` (root package, `AbstractAppMockMvcTest`) | Real JSON-body HTTP coverage exists, including the session-persistence round-trip (logout/re-signin) that is the actual reason this class must be a tracer rather than a plain controller test — proving persistence to the `users` table, not just the HTTP contract, is explicitly the stated point of the class (Javadoc lines 25-37). |
| `POST /signin` | no | `AuthenticationTest.Signin` (`security/`, `AbstractAppMockMvcTest`) | Real JSON-body HTTP coverage exists, including field-validation and anti-enumeration cases. `security/` rather than `controller/` because `AuthenticationController` itself lives in `security/`. |
| `POST /signup` | no | `AuthenticationTest.Signup` (`security/`, `AbstractAppMockMvcTest`) | Real JSON-body HTTP coverage exists, including field-validation and duplicate-email cases. Same package rationale as signin. |

**Result:** every one of the 13 mutating endpoints has real, HTTP-JSON-body-driven coverage
somewhere in the suite. Four endpoints (`POST /boards`, `PATCH /tasks/{id}/move`,
`PUT /users/me/theme`, `POST /signin`/`POST /signup`) are covered outside a dedicated
`controller/*ControllerTest.java` class — this matches the planning inventory's already-known
observation ("only four `controller/*ControllerTest.java` classes exist against eight
controllers") and is not, on inspection, an uncovered gap; see F-09 for the disposition.

## Section 4 — Findings register

| ID | Failure mode | file:line | Evidence | Severity | Disposition | Actual outcome / artifact |
|---|---|---|---|---|---|---|
| F-01 | validation-asymmetry | `subtask_dto/SaveSubtaskRequestDTO.java:16` | `title` is `@SubtaskTitle` only (no `@NotBlank`), unlike every sibling `Save*RequestDTO` (Board/Task/Column all carry explicit `@NotBlank` alongside their length constraint). Already filed as `.planning/todos/pending/2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md`. | major (pre-existing) | **CONFIRMED-EXISTING** | Re-confirmed by this audit's independent field-by-field pass; left untouched, per the scoping instruction. Existing todo: `.planning/todos/pending/2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md` (unchanged by this plan). |
| F-02 | coverage / test-quality | `controller/TaskControllerTest.java:244` | `UpdateById.testWithAuthenticatedUser_shouldReturnBadRequest_whenDataIsInvalid` builds its PUT body from `SaveTaskRequestDTO.builder().title("").build()` — a DTO with no `version` field — instead of `UpdateTaskRequestDTO`. The observed 400 is driven by `UpdateTaskRequestDTO.version`'s `@NotNull` firing on the field the wrong-typed builder never populates (Jackson serializes `SaveTaskRequestDTO` with no `version` key at all), not by title-blank validation as the test name implies. | minor | **FILE-TODO** | Filed: `.planning/todos/pending/2026-08-11-taskcontrollertest-updateby-blank-title-test-uses-wrong-dt.md`. |
| F-03 | coverage / test-quality | `controller/SubtaskControllerTest.java:255` | Identical pattern: `UpdateById.testWithAuthenticatedUser_shouldReturnBadRequest_whenDataIsInvalid` builds its PUT body from `SaveSubtaskRequestDTO.builder().title("").build()` instead of `UpdateSubtaskRequestDTO`, again masking the intended title-validation assertion behind the version-`@NotNull` failure. | minor | **FILE-TODO** | Filed: `.planning/todos/pending/2026-08-11-subtaskcontrollertest-updateby-blank-title-test-uses-wrong.md`. |
| F-04 | validation-asymmetry (confirmed live bug) | `column_dto/SaveColumnRequestDTO.java:18-22` | The `@Size` constraint on `name` passed the wrong message constant: `message = ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE` (the board-name message, literally "Board name cannot be less than 1 character and more than 64 characters") instead of `ValidationConstants.COLUMN_NAME_LENGTH_VALIDATION_MESSAGE` ("Column name cannot be less than 3 character and more than 32 characters"). **Empirically confirmed live** (not composed via `@ReportAsSingleViolation`, so nothing intercepts the message): a throwaway `Validator` run against `SaveColumnRequestDTO.builder().name("a").build()` returned `MESSAGE: [Board name cannot be less than 1 character and more than 64 characters]`. Sibling `UpdateColumnRequestDTO.java:24` already used the correct `COLUMN_NAME_LENGTH_VALIDATION_MESSAGE` for the identical constraint. | medium — a real, user-facing wrong-entity-name and wrong-numeric-bounds error message on every too-short/too-long column name at creation | **FIX-NOW** | **Fixed.** `SaveColumnRequestDTO.name`'s `@Size` now uses `COLUMN_NAME_LENGTH_VALIDATION_MESSAGE`. Regression test `BoardControllerTest.AddColumnByBoardId.testWithAuthenticatedUser_shouldReturnColumnSpecificMessage_whenNameIsTooShort` added, watched RED (asserted the correct message, observed the wrong board-flavored one) before the fix and GREEN after. Commit `46753da`. |
| F-05 | validation-asymmetry (confirmed dead code, not a live bug) | `dto/annotation/SubtaskTitle.java:16-24` | The composed annotation's inner `@Size` also passed `NAME_LENGTH_VALIDATION_MESSAGE` instead of the correct `ValidationConstants.SUBTASK_TITLE_LENGTH_VALIDATION_MESSAGE`, affecting both `SaveSubtaskRequestDTO.title` and `UpdateSubtaskRequestDTO.title` (both use `@SubtaskTitle`). **Empirically verified this is dead, not live**: `SubtaskTitle` carries `@ReportAsSingleViolation`, so any composing-constraint failure is reported using the *composed* annotation's own message, not the inner constraint's — a throwaway `Validator` run against a too-long title on both DTOs returned `MESSAGE: [Subtask title cannot be empty]` in both cases, never the mismatched text. | low — confirmed inert/misleading source, not a client-visible defect | **FILE-TODO** | Filed: `.planning/todos/pending/2026-08-11-subtasktitle-composed-annotation-carries-wrong-message-cons.md`. Not FIX-NOW because the mismatch has no observable API effect, so no controller-tier RED-then-GREEN regression test can meaningfully demonstrate the change (Task 2 eligibility criterion (c)). |
| F-06 | validation-asymmetry (confirmed live gap + cross-DTO design fork) | `board_dto/UpdateBoardRequestDTO.java:21`, `task_dto/UpdateTaskRequestDTO.java:23`, `subtask_dto/UpdateSubtaskRequestDTO.java:22`, `user_dto/SignupRequestDTO.java:17`, vs. `column_dto/UpdateColumnRequestDTO.java:21-25` | None of `UpdateBoardRequestDTO.name`, `UpdateTaskRequestDTO.title`, `UpdateSubtaskRequestDTO.title`, or `SignupRequestDTO.displayName` carries an explicit `@NotBlank` — each relies solely on its composed annotation's `@Size(min>=1)`, which only rejects a fully-empty string, not a whitespace-only one at/above the minimum length. `UpdateColumnRequestDTO.name` is the sole exception, and its `@NotBlank` also makes the field non-optional, a second, related asymmetry the planning inventory already flagged. **Empirically confirmed** with a throwaway `Validator` run, all four DTOs built with a 3-space value: `UpdateBoardRequestDTO name='   '` → 0 violations; `UpdateTaskRequestDTO title='   '` → 0 violations; `UpdateSubtaskRequestDTO title='   '` → 0 violations; `SignupRequestDTO displayName='   '` → 0 violations; `UpdateColumnRequestDTO name='   '` → 1 violation (correctly rejected). | medium — this is exactly the class of defect (a field silently unvalidated relative to a sibling) that motivated this audit, now confirmed on 4 of 5 examined fields | **FILE-TODO** | Filed: `.planning/todos/pending/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md`. Genuine design fork (per plan `<approach_analysis>`) — no existing sibling pattern implements "reject blank only when the field is actually provided" without breaking the documented optional/omission semantics. |
| F-07 | other (structural/DRY, not a validation gap) | `column_dto/SaveColumnRequestDTO.java:18-21`, `task_dto/SaveTaskRequestDTO.java:19-22` | Both declare `@NotBlank` + `@Size` inline rather than delegating to a shared composed annotation the way `SaveBoardRequestDTO` delegates to `@BoardName`. The min/max values used are correct and match `ValidationConstants` (message text aside — see F-04) — a duplication-of-definition concern, not a behavior gap. | low | **NO-ACTION** | No artifact — left as-is. Behaviorally correct (post-F-04); extracting shared composed annotations is a refactor with no user-facing effect, outside this audit's two target failure modes. |
| F-08 | other (design question, not a defect) | `dto/annotation/BoardName.java:22-24` vs. `column_dto/SaveColumnRequestDTO.java` / `task_dto/SaveTaskRequestDTO.java` / `dto/annotation/SubtaskTitle.java` | `BoardName` composes a `@Pattern("^[a-zA-Z0-9 ]*$")` restricting board names to alphanumerics and spaces. No equivalent `@Pattern` exists on column names, task titles, or subtask titles — those accept any character. | low | **NO-ACTION** | No artifact — left as-is. A legitimate cross-entity design question, not itself a defect; no source todo or CODE_STYLE rule mandates charset parity across entities. |
| F-09 | coverage (documented, not a gap) | see Section 3 table | Four endpoints (`POST /boards`, `PATCH /tasks/{id}/move`, `PUT /users/me/theme`, `POST /signin`/`POST /signup`) have real JSON-body HTTP coverage only outside `controller/*ControllerTest.java` (in `BoardCreationE2ETest`, `TaskMoveTest`, `ThemePersistenceTest`, `AuthenticationTest` respectively). | informational | **NO-ACTION** | No artifact — left as-is. Each class is a documented tracer test proving a cross-cutting concern CODE_STYLE rule 4 explicitly carves out as legitimate e2e/tracer-tier coverage. |
| F-10 | other (dead code) | `board_dto/DeleteBoardByIdRequestDTO.java:14` | `grep -rn "DeleteBoardByIdRequestDTO" src/main src/test` matched only the class's own declaration — zero references anywhere else in the codebase. No controller method takes it as a parameter. Matches the planning inventory's "possibly dead" flag, now confirmed. | low | **FILE-TODO** | Filed: `.planning/todos/pending/2026-08-11-delete-dead-deleteboardbyidrequestdto-class.md`. Deleting an entire file is out of scope for Task 2's FIX-NOW eligibility test (criterion (a) requires a single annotation change to an existing declaration). |

**Summary:** 10 findings, all disposed and closed out. 1 `FIX-NOW`, fixed with a regression test
(F-04). 1 `CONFIRMED-EXISTING`, re-confirmed and left untouched (F-01). 5 `FILE-TODO`, each filed as
a self-contained pending todo (F-02, F-03, F-05, F-06, F-10). 3 `NO-ACTION`, each with a written
reason above, left as-is (F-07, F-08, F-09). Zero findings in the binding failure mode (Section 1)
— the specific defect class that motivated this audit (260811-me4) does not recur anywhere else in
the codebase, and the new ArchUnit rule (`LayeringArchTest.mutating_handlers_must_bind_request_dto_parameters_from_the_body`)
now makes that failure mode structurally unreopenable, observed red-then-green under a deliberate
teeth-check.
