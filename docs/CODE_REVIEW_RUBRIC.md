# Code review rubric

Reviewer instructions: when a diff adds or changes Java, tests included, grade the changed lines
against the rules below. Report each violation as a finding that names the rule number, the file
and line, and the deciding test it fails. Do not report a passing line, or anything a build gate
already enforces: [CODE_STYLE.md](CODE_STYLE.md) marks those rules Enforced. Grade only lines
the diff touches. Much existing code predates these rules (measured counts sit under the rules
they affect), and rewriting untouched code belongs in a change of its own.

Numbers and headings match CODE_STYLE.md. Rules 3, 6, 7, 10 and 11 are fully enforced by tools
and have no entry here.

### 1. Prefer enums over magic int/String constants

When a value comes from a fixed, known-at-compile-time set, model it as an enum (a JDK/framework-provided one where it exists, otherwise a project enum under `com.vrudenko.kanban_board`) rather than as bare `int` or `String` literals scattered across call sites. HTTP status codes are the canonical case: use `org.springframework.http.HttpStatus`.

**Why:** the compiler enforces the closed set, so a typo or an out-of-range value fails at build time instead of runtime; switch statements can be checked for exhaustiveness; the value carries a self-documenting name at every call site; and the set has one authoritative definition to change instead of N literal sites to grep for.

`GlobalExceptionHandler` already follows this rule and is the reference to imitate. The rule generalises beyond HTTP status — any closed value set (roles, states, sort directions) should be an enum.

**Deciding test:** Is the value drawn from a closed set known at compile time (a status, role, state or direction)? A bare `int` or `String` literal for it is a finding. An enum constant passes, and so does an `int` read from one, such as `problem.getStatus()`.

### 2. Load entities through the ownership-verified loader, never `repository.findById` directly

The four domain services (`BoardService`, `ColumnService`, `TaskService`, `SubtaskService`) must resolve every entity through their own `findById(userId, id)` method, which delegates to `ownershipVerifierService.verifyOwnershipOf...` — never through a direct call to `repository.findById(id)`. For example, `TaskService.findById(userId, taskId)` calls `ownershipVerifierService.verifyOwnershipOfTask(userId, taskId)` and returns `pair.getSecond()`; every other method that needs a task goes through it instead of touching `taskRepository.findById` itself. Once an entity has been verified this way, any downstream repository call made later in the same method must be built from the verified entity's own id (`pair.getSecond().getId()`), not from the raw path-variable parameter that was passed in. `OwnershipVerifierService` (the root of the ownership chain) and `UserService` (the identity root, with no owner above it) are the only two places a direct repository `findById` is sanctioned.

**Why:** this is the entire access-control model of the application, and nothing in the type system enforces it — a direct repository load compiles cleanly, passes a naive test, and silently removes the ownership check it was supposed to go through; re-deriving the downstream id from the verified entity, rather than reusing the raw parameter, also guarantees that the id which was actually authorised is the id that gets used.

`TaskService.findById` and `TaskService.findAllByColumnId` are the reference implementations of this pattern.

**Deciding test:** After the ownership check, does a later repository call take the raw path-variable id instead of the verified entity's id? Does the method load an entity through a repository query other than `findById` (a hand-written `findByX`) without the ownership chain? Either is a finding; LayeringArchTest sees neither.

### 4. No mocks — test against real Spring wiring

**Deciding test:** Does the test swap a real collaborator for a hand-written fake, stub or subclass that skips the real wiring? That is a finding. A wrapper that forwards every call to the real bean and only observes passes; `CountingPasswordEncoder` in `SigninTimingEqualizationTest` is an example. Does the test class build users, boards, columns, tasks or subtasks that `AbstractAppTest`'s `@BeforeEach` already provides? That is a finding.

**Reference:** Package, tier and base class: [ARCHITECTURE.md, "Writing a new test"](ARCHITECTURE.md#writing-a-new-test-package-tier-and-base-class).

### 5. Group by method under test with `@Nested`; name `should<Outcome>_when<Condition>`; mark sections with AAA comments

Test methods that exercise one method under test are grouped inside a `@Nested` class named after that method (for example `FindAllByColumnIdTest`). Test methods are named `should<Outcome>_when<Condition>`, and each method body is divided into `// arrange`, `// act`, `// assert` section comments. `@DisplayName` is not used — the method name is the display name. Two naming dialects both exist and neither should be normalised into the other: service and unit tests use the plain `should<Outcome>_when<Condition>` form; MockMvc controller tests prefix the auth context, as `testWithAuthenticatedUser_should<Outcome>_when<Condition>`.

**Why:** nesting by method under test makes the method itself the unit of navigation, rather than scrolling a flat wall of unrelated test methods; the name-plus-section-comment convention removes the need for a second, separately-maintained `@DisplayName` string that can drift out of sync with what the method actually asserts.

`TaskServiceTest` is the reference for the service dialect; `BoardControllerTest` is the reference for the controller dialect (`testWithAuthenticatedUser_...`).

**Deciding test:** For each new test method, check three things. Is it inside a `@Nested` class named after the method under test? Is it named `should<Outcome>_when<Condition>`, with the `testWithAuthenticatedUser_` prefix in MockMvc controller tests? Is its body split by `// arrange`, `// act`, `// assert`? Each miss is a finding. Measured on 2026-10-06: 87 of 488 existing test methods, in 23 classes, use other name shapes. Grade new methods only, and leave renaming to a change of its own.

### 8. Test setup must be fully automated — never a manual step for the developer

Running the test suite must never depend on a developer performing a manual, host-level setup step first (flipping an application GUI setting, hand-editing a config file outside version control, running a one-off command before `./gradlew test` will work). If a test needs specific environment/tooling behavior to run correctly, that behavior must be configured from within the codebase itself — a system property set in test code, a project-local config file that ships in version control, a Gradle task — so that `./gradlew test` (or the equivalent single command) is sufficient on a clean checkout. When a failure turns out to be caused by a missing environment quirk (a client/tooling version incompatibility, a platform-specific default), fix it by encoding the workaround in the codebase, not by writing runbook instructions for a human to follow by hand.

**Why:** a manual setup step is a step every new environment forgets — a fresh clone, a new contributor's machine, a CI runner — and it turns "run the tests" into "run the tests, but first go read the docs and remember to do this one fiddly thing," which reliably doesn't happen. A one-time automated fix in the codebase benefits every future run and every future machine; a documented manual workaround has to be rediscovered and repeated by everyone who hits it.

**Deciding test:** Does the change ask a person to do anything by hand before `./gradlew test` works? Flipping a GUI setting, editing an untracked file, exporting a variable nothing in the repository sets, or running a one-off command all count. Any of them is a finding.

**Reference:** [LOCAL_DEV.md, "Testcontainers-based tests on Windows"](LOCAL_DEV.md#testcontainers-based-tests-on-windows).

### 9. Use `var` only when the RHS already makes the type obvious

For local variable declarations, `var` is preferred only when the right-hand side already makes the type visually obvious at the call site — a constructor call (`var user = new UserEntity();`), a well-known factory/builder call (`var id = UlidCreator.getUlid();`, `var dto = TaskResponseDTO.builder()...build();`), or a loop variable over a collection whose element type was just declared. Keep the explicit type when the RHS is a call whose return type isn't obvious without checking the method signature — a service/repository/mapper method call, a generic collection assembled through a stream or factory method, or any expression a reader would have to look up elsewhere to resolve. `var` is a Java local-variable-only feature — it cannot appear on fields, method parameters, or return types — so this rule only ever applies to local variable declarations, never anywhere else.

**Why:** this codebase (and an AI agent editing it) is read top-to-bottom without an IDE's inline type hints, so the type has to come from either the keyword or the RHS; when the RHS already spells out the type, `var` removes true redundancy, but when it doesn't, `var` forces a detour to a method signature just to know what a variable supports — which is friction the explicit-type form never asks for.

**Deciding test:** For each new or changed `var` declaration: could a reader name the type from the right-hand side alone, without opening another file? If not, that is a finding. `var user = new UserEntity();` passes; `var tasks = taskRepository.findAllByColumnId(columnId);` does not. Measured on 2026-10-06: 127 of 154 `var` declarations in src/main have a method-call right side, so grade changed lines only.

### 12. An optional String field that rejects blank carries `@OptionalNotBlank`, not `@NotBlank`

Validation alone does not make such a field safe: the service that consumes it must treat a null
value as "leave this field unchanged", as `TaskService.updateById` and `SubtaskService.updateById`
do with a presence guard. `BoardService.updateById` lacked that guard until quick task 261006-guz,
and a version-only board `PUT` returned 500.

**Deciding test:** Is the field optional (null means "leave unchanged") and blank meaningless for it? Then `@NotBlank` on it is a finding, and it needs `@OptionalNotBlank` beside its composed annotation. A field where blank is legitimate, such as `UpdateTaskRequestDTO.description`, needs neither. Does the consuming service treat a null field as unchanged? If not, that is a finding.

### 13. A new test class belongs in a named subpackage of `com.vrudenko.kanban_board`, never directly in the root package

The `e2e/` subtree is itself entity-subfoldered: a new flow test that spans board, column, task or
subtask concerns lives under `e2e/<entity>/`, not loosely under `e2e/` itself. The single named
exception is `KanbanBoardApplicationTests`, Spring Initializr's conventional root-package
context-load smoke test, which stays beside `KanbanBoardApplication` by idiomatic Spring Boot
convention rather than by any technical requirement -- `@SpringBootTest` with no `classes`
attribute walks *up* the package hierarchy for `@SpringBootConfiguration`, so every subpackage
below the root can still reach it.

It does not catch a test class that lands in the *wrong* subpackage for what it tests (e.g. a
column concern filed under `e2e/task/`) -- that judgement call is what this rule's prose, and rule
4's purpose test, are for.

**Deciding test:** Does the new test's subpackage match the purpose test in ARCHITECTURE.md, "Writing a new test"? A flow test left loose under `e2e/` is a finding, and so is a column test under `e2e/task/`.

### 14. Treat code comments as a reviewable contract, not a transcript

- Delete a restatement of code.
- Prefer a test, type, lint rule, or ADR when it can enforce the claim.
- Name another identifier only when the name carries a contract or deliberate absence.

**Why:** comments otherwise duplicate implementation and silently rot. `scripts/verify-comments.py`
enforces the mechanically checkable parts of this rule; whether a comment is useful, at the right
abstraction level, and imperative remains review-only judgement.

The functional marker is consumed by repository tooling; do not rewrite or
remove such markers without updating that tooling.

**Deciding test:** Decide in one pass; the first yes wins. Does it restate the code? Delete it. Is it a task? Make it a `TODO:` with a link, or a test. Is it a settled decision a reader would otherwise undo? Keep it as a decision record. Does it name another identifier only to say "same as that one"? Cut the name. Otherwise it is one line, at a different abstraction level than the code.
