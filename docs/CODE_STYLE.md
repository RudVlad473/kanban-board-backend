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
