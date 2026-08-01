---
phase: quick/260801-gib
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - docs/CODE_STYLE.md
  - src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java
autonomous: true
requirements: [QUICK-260801-gib]

estimate:
  tokens: 70000
  raw_tokens: 70000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "docs/CODE_STYLE.md carries seven numbered rules; rules 2-7 codify ownership-verified loading, AssertJ/catchException, no-mocks testing, @Nested/AAA test structure, Update*RequestDTO shape, and Optional isEmpty()-guard unwrapping, in that order."
    - "Every new rule follows Rule 1's shape exactly: a rule statement, a bolded **Why:** line, and a Discouraged/Preferred pair of fenced java blocks."
    - "Every new rule's example names real symbols from this repository (TaskService, OwnershipVerifierService, AbstractAppTest, UpdateTaskRequestDTO) rather than invented placeholders."
    - "Rule 1 and the '## Adding a rule' section survive byte-identical; the new rules sit between them."
    - "Neither locking E2E test expresses an expected HTTP status as a bare numeric literal — both use org.springframework.http.HttpStatus constants."
    - "Both locking E2E tests still pass, and ./gradlew spotlessCheck still passes."
  artifacts:
    - "docs/CODE_STYLE.md (rules 2-7 inserted)"
    - "src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java"
    - "src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java"
  key_links:
    - "Rule 2 prose -> TaskService.findById(userId, taskId) and OwnershipVerifierService.verifyOwnershipOfTask (the real loader it describes)"
    - "Rule 6 prose -> UpdateTaskRequestDTO's actual three markers (@JsonInclude(NON_NULL), @NotNull Long version, atLeastOneFieldPopulated())"
    - "Rule 7 prose -> OwnershipVerifierService.verifyOwnershipOfBoard's isEmpty()-guard sequence"
    - "CODE_STYLE.md Rule 1 (enums over magic constants) -> the two E2E test files, which must now comply with it"
---

<objective>
Append six new rules (2-7) to `docs/CODE_STYLE.md` codifying the conventions the developer selected from the convention survey, and fix the ten raw-int HTTP status assertions in the two locking E2E tests so they comply with the file's existing Rule 1.

Purpose: turn a one-rule style guide into the real house-style reference for this repo, and close the single existing violation of the rule it already states — so the guide is not contradicted by the codebase it governs.
Output: `docs/CODE_STYLE.md` (six inserted `###` sections), `TaskLockingE2ETest.java` and `ColumnLockingE2ETest.java` (int -> `HttpStatus` enum).

No tracer task in this plan: the rule format is already proven end-to-end by the shipped Rule 1, and the test change is a literal substitution inside existing passing tests. A thin slice would add no information.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/quick/260801-gib-survey-the-repo-for-existing-code-conven/260801-gib-CONTEXT.md
@.planning/quick/260801-gib-survey-the-repo-for-existing-code-conven/260801-gib-RESEARCH.md
@docs/CODE_STYLE.md
</context>

<interface_context>
Grounding facts verified during planning against live source — do not re-derive, and do not trust the research doc's line numbers over these:

**docs/CODE_STYLE.md as it stands (43 lines):** line 5 `## Rules`; line 7 `### 1. Prefer enums over magic int/String constants`; line 11 the `**Why:**` line; lines 15-25 the `Discouraged:` fenced java block (uses `HttpStatusCode.valueOf(404)` twice); lines 29-36 the `Preferred:` block (uses `HttpStatus.NOT_FOUND`); line 38 the closing note naming `GlobalExceptionHandler` as the reference; line 40 `## Adding a rule`; line 42 the append convention. **Insertion point: between line 38 and line 40.** The file currently contains exactly two fenced ```java blocks and one line beginning `**Why:**`.

**Rule 1's section shape, to be copied exactly:** `### N. <sentence-case title>` / blank / rule-statement paragraph / blank / `**Why:** <lowercase rationale, semicolon-separated clauses>` / blank / `Discouraged:` / blank / fenced java block / blank / `Preferred:` / blank / fenced java block / blank / one closing sentence naming the real reference site to imitate.

**Rule 2 grounding — `TaskService.java`:** `findById(String userId, String taskId)` at :59-63 is the ownership-verified loader — it calls `ownershipVerifierService.verifyOwnershipOfTask(userId, taskId)` and returns `pair.getSecond()`. `findAllByColumnId` at :44-48 calls `taskRepository.findAllByColumnId(pair.getSecond().getId())` — the verified entity's id, not the raw `columnId` parameter. `updateById` at :76-77 opens with `var task = findById(userId, taskId);`. There is zero direct `taskRepository.findById` in `TaskService`. Direct repository `findById` is sanctioned only in `OwnershipVerifierService` (chain root) and `UserService` (identity root).

**Rule 6 grounding — `UpdateTaskRequestDTO.java` (34 lines, verified verbatim):** class annotations are `@Getter @Setter @Builder @EqualsAndHashCode @JsonInclude(JsonInclude.Include.NON_NULL)`; declares `implements BaseTask`; fields are `@TaskTitle String title;`, `@Description String description;`, `@NotNull private Long version;`; then `@AssertTrue(message = "Either 'title' or 'description' (or both) must be provided.") private boolean atLeastOneFieldPopulated()` whose body uses `Optional.ofNullable(getTitle()).isPresent()` / `Optional.ofNullable(getDescription()).isPresent()` and returns their OR. `UpdateColumnRequestDTO` and `UpdateBoardRequestDTO` are single-field and correctly carry no `@AssertTrue`. `Save*RequestDTO` and `*ResponseDTO` never carry `@JsonInclude`.

**Rule 7 grounding — `OwnershipVerifierService.verifyOwnershipOfBoard` (:32-58, verified verbatim):** `var user = userRepository.findById(userId); if (user.isEmpty()) { throw new AppEntityNotFoundException("User"); }` then the same guard shape for `board`, then `var userOwnsBoard = board.get().getUser().getId().equals(user.get().getId()); if (!userOwnsBoard) { throw new AppAccessDeniedException("Board"); }` then `return Pair.of(user.get(), board.get());`. Three flat guards in one sequence — that flatness is the argument for the rule. `orElseThrow` appears zero times in `src/main`.

**E2E files — exact current state (both verified):** `TaskLockingE2ETest.java` is 152 lines, `ColumnLockingE2ETest.java` is 141 lines. Neither imports `org.springframework.http.HttpStatus`. Both end their import block with `import org.springframework.data.util.Pair;` — the new import belongs immediately after it (alphabetically `http` follows `data`, which is what Google Java Format AOSP will enforce). Both files have exactly three `@Test` methods with identical names: `concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict`, `update_withCurrentVersion_succeedsAndReturnsIncrementedVersion`, `update_withoutVersion_returnsBadRequest`. The only numeric-literal assertions in either file are the five status assertions; every other assertion compares against `startingVersion`. Research line numbers (task 63/78/92/122/150, column 54/69/83/112/139) were re-verified and are still accurate.

**Build:** Gradle wrapper at repo root. Use `./gradlew` from the Bash tool; from PowerShell the equivalent is `.\gradlew.bat`. Formatting is Google Java Format AOSP (4-space indent) enforced by Spotless.
</interface_context>

<tasks>

<task type="auto">
  <name>Task 1: Insert Rules 2-7 into docs/CODE_STYLE.md</name>
  <files>docs/CODE_STYLE.md</files>
  <read_first>
docs/CODE_STYLE.md (all 43 lines — Rule 1 is the format template)
src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java:1-30, 54-70, 85-95, 145-160 (Rules 3, 4, 5 grounding)
src/test/java/com/vrudenko/kanban_board/AbstractAppTest.java:25-40, 165-185 (Rule 4 grounding: the base class and the countQueries helper Javadoc)
src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java:28-55 (Rule 5 grounding: the controller-test naming dialect)
TaskService, UpdateTaskRequestDTO and OwnershipVerifierService facts are already stated verbatim in `<interface_context>` — do not re-read those three files.
  </read_first>
  <action>
Edit `docs/CODE_STYLE.md` with a scoped Edit anchored on the boundary between the end of Rule 1 and the `## Adding a rule` heading. Insert six new `###` sections there. Do NOT use Write on this file, and do not touch Rule 1, the intro, the `## Rules` heading, or the `## Adding a rule` section — the only diff is inserted lines.

Every new section must reproduce Rule 1's shape exactly as described in `<interface_context>`: title line, rule-statement paragraph, a `**Why:**` line, a `Discouraged:` label with one fenced java block, a `Preferred:` label with one fenced java block, and a closing sentence naming the real site to imitate. Exactly two fenced java blocks per rule — no more, no fewer. Format all Java as Google Java Format AOSP would (4-space indent, no tabs).

The research doc's one-line bad/good sketches are drafts, not copy-paste-ready. Refine each into polished, standalone prose: a reader must understand the rule without ever opening the research doc. Do not cite the research doc, the survey, occurrence counts as evidence-for-the-reader, phase numbers, or any GSD artifact — this is a repo doc with an indefinite lifetime. Do not add any rule beyond the six specified; C5, C7, C8, C9 and L1-L5 from the survey were explicitly not selected.

Write the six sections in exactly this order and content (per the locked CONTEXT decisions named in brackets):

**Rule 2 — ownership-verified loading only [CONTEXT: C3].** Title it for loading through the ownership-verified loader. Statement: the four domain services (`BoardService`, `ColumnService`, `TaskService`, `SubtaskService`) must resolve every entity through their own `findById(userId, id)` loader, which delegates to `ownershipVerifierService.verifyOwnershipOf...`, never through a direct `repository.findById`; and once verified, downstream repository calls must pass the verified entity's id (`pair.getSecond().getId()`) rather than the raw path-variable parameter. Name `OwnershipVerifierService` (the chain root) and `UserService` (the identity root) as the only two sanctioned direct-repository callers. Why: this is the codebase's entire access-control model and it is enforced only by convention — nothing in the type system stops a direct repository load, which compiles, passes a naive test, and silently removes the ownership check; re-deriving the id from the verified entity also guarantees the id that was authorised is the id that gets used. Discouraged block: a `TaskService` method that loads via `taskRepository.findById(taskId)`, unwraps it, and mutates the title — plus a downstream repository call passing the raw `columnId` parameter. Preferred block: `var task = findById(userId, taskId);` before any mutation, and `taskRepository.findAllByColumnId(pair.getSecond().getId())` for the downstream call. Close by naming `TaskService.findById` and `TaskService.findAllByColumnId` as the reference implementations.

**Rule 3 — AssertJ fully qualified, exceptions via catchException [CONTEXT: C1].** Statement: assertions are always written `Assertions.assertThat(...)` against `import org.assertj.core.api.Assertions;` — never a static import of `assertThat`; and a thrown exception is always captured with `Assertions.catchException(...)` and then asserted on, never with JUnit's `assertThrows`. Why: capturing the throwable into a local lets one test assert both the exception and a follow-up state check in sequence, which `assertThrows` makes awkward; and the qualified form keeps the assertion library visible at every call site, matching every existing assertion in the suite. Discouraged block: a static import of `assertThat` plus `assertThrows(AppEntityNotFoundException.class, () -> taskService.findById(userId, taskId));`. Preferred block: the plain `Assertions` class import, then capturing into a local with `Assertions.catchException(() -> taskService.findById(userId, taskId));` and asserting `.isInstanceOf(AppEntityNotFoundException.class)` on it. Close by naming `TaskServiceTest` as the reference.

**Rule 4 — no mocks [CONTEXT: C4].** Statement: every test class is a `@SpringBootTest` extending `AbstractAppTest` (or `AbstractAppE2ETest`), exercising real Spring wiring against H2. Mockito, `@Mock`, `@MockBean` and slice annotations (`@WebMvcTest`, `@DataJpaTest`) are not used anywhere in this repository and must not be introduced. New shared fixtures belong in `AbstractAppTest`'s single `@BeforeEach`, not inlined per test class. Add that `AbstractAppTest.countQueries(Runnable)` is the only sanctioned way to assert query counts, and that its Javadoc records why it reads `getPrepareStatementCount()` rather than `getQueryExecutionCount()`. Why: mocking the repository bypasses exactly the ownership chain and JPA behaviour these tests exist to cover, so a fully green mocked test can sit on top of a broken access-control path. Discouraged block: the `@ExtendWith(MockitoExtension.class)` + `@Mock TaskRepository` + `@InjectMocks TaskService` shape. Preferred block: `@SpringBootTest public class TaskServiceTest extends AbstractAppTest` with an `@Autowired` service field. Close by naming `AbstractAppTest` as the reference.

**Rule 5 — @Nested structure, naming, AAA comments [CONTEXT: C2].** Statement: test methods for one method-under-test are grouped in a `@Nested` class named after that method (for example `FindAllByColumnIdTest`); methods are named `should<Outcome>_when<Condition>`; and each body is divided by `// arrange`, `// act`, `// assert` section comments. `@DisplayName` is not used — the method name is the display name. Document both naming dialects as sub-patterns without normalising them: service and unit tests use `should<Outcome>_when<Condition>`, MockMvc controller tests prefix the auth context as `testWithAuthenticatedUser_should<Outcome>_when<Condition>`. Why: the nested grouping makes the method under test the unit of navigation rather than a flat wall of methods, and the name-plus-section-comment convention removes the need for a second, drift-prone description string. Discouraged block: a flat test class with a `@DisplayName` annotation and an undescriptive method name, no section comments. Preferred block: a `@Nested class FindAllByColumnIdTest` containing `void shouldReturn_whenColumnExists()` with the three section comments in place. Close by naming `TaskServiceTest` (service dialect) and `BoardControllerTest` (controller dialect) as references.

**Rule 6 — Update*RequestDTO fixed shape [CONTEXT: C6].** Statement: every `Update*RequestDTO` carries `@JsonInclude(JsonInclude.Include.NON_NULL)` on the class, a `@NotNull private Long version` field, and — whenever more than one field is independently optional — a private `@AssertTrue` method named `atLeastOneFieldPopulated()`. State the deliberate asymmetry: `Save*RequestDTO` and `*ResponseDTO` never carry `@JsonInclude`; its presence is what marks a DTO as a partial update. Why: omitting the `@NotNull Long version` silently disables optimistic locking for that entity — the request still validates and the write still succeeds, it just stops being safe against concurrent edits; and a differently named cross-field validator makes the same check unfindable across DTOs. Discouraged block: a bare `UpdateXRequestDTO` with a plain `String name` and an unannotated `Long version`, no `@JsonInclude` and no cross-field validator. Preferred block: the real `UpdateTaskRequestDTO` shape — the Lombok quartet plus `@JsonInclude(JsonInclude.Include.NON_NULL)`, `@TaskTitle String title`, `@Description String description`, `@NotNull private Long version`, and the `@AssertTrue`-annotated `atLeastOneFieldPopulated()` returning the OR of two `Optional.ofNullable(...).isPresent()` checks, with its real message string. Close by naming `UpdateTaskRequestDTO` as the reference and noting single-field update DTOs such as `UpdateColumnRequestDTO` correctly omit the `@AssertTrue` method.

**Rule 7 — Optional unwrapping via isEmpty() guard [CONTEXT: C10].** Statement: an `Optional` returned by a repository is unwrapped with an `isEmpty()` guard that throws the appropriate `App...Exception`, followed by `.get()`. `orElseThrow` is not used in `src/main` and must not be introduced. Be explicit that this is a deliberate keep-it-as-is house-style choice rather than a claim of technical superiority: `orElseThrow` is shorter and the more idiomatic modern Java, but every existing unwrap site uses the guard form, and consistency across those sites is the point. Why: the guard is a statement rather than an expression, so additional checks — a second entity load, an ownership comparison — slot in beside it as peers in one flat sequence instead of forcing a restructure at the first non-trivial case. Discouraged block: `return taskRepository.findById(id).orElseThrow(() -> new AppEntityNotFoundException("Task"));`. Preferred block: assign the `Optional` to a local, guard with `if (task.isEmpty()) { throw new AppEntityNotFoundException("Task"); }`, then return `task.get()`. Close by naming `OwnershipVerifierService.verifyOwnershipOfBoard` as the reference, and note that it chains exactly this shape three times (user, board, ownership) in one flat sequence.
  </action>
  <verify>
    <automated>[ "$(grep -n '^## Adding a rule$' docs/CODE_STYLE.md | cut -d: -f1)" -gt "$(grep -n '^### 7\. ' docs/CODE_STYLE.md | cut -d: -f1)" ] &amp;&amp; for n in 2 3 4 5 6 7; do grep -q "^### $n\. " docs/CODE_STYLE.md || exit 1; done &amp;&amp; ! grep -q '^### 8\.' docs/CODE_STYLE.md &amp;&amp; [ "$(grep -c '^```java' docs/CODE_STYLE.md)" -eq 14 ] &amp;&amp; [ "$(grep -c '^\*\*Why:\*\*' docs/CODE_STYLE.md)" -eq 7 ] &amp;&amp; [ "$(grep -c '^Discouraged:$' docs/CODE_STYLE.md)" -eq 7 ] &amp;&amp; [ "$(grep -c '^Preferred:$' docs/CODE_STYLE.md)" -eq 7 ] &amp;&amp; grep -q 'HttpStatusCode\.valueOf(404)' docs/CODE_STYLE.md &amp;&amp; grep -q 'verifyOwnershipOfTask' docs/CODE_STYLE.md &amp;&amp; grep -q 'pair\.getSecond()\.getId()' docs/CODE_STYLE.md &amp;&amp; grep -q 'Assertions\.catchException' docs/CODE_STYLE.md &amp;&amp; grep -q 'AbstractAppTest' docs/CODE_STYLE.md &amp;&amp; grep -q 'countQueries' docs/CODE_STYLE.md &amp;&amp; grep -q 'shouldReturn_whenColumnExists' docs/CODE_STYLE.md &amp;&amp; grep -q 'testWithAuthenticatedUser_' docs/CODE_STYLE.md &amp;&amp; grep -q 'atLeastOneFieldPopulated' docs/CODE_STYLE.md &amp;&amp; grep -q 'orElseThrow' docs/CODE_STYLE.md &amp;&amp; grep -q 'verifyOwnershipOfBoard' docs/CODE_STYLE.md &amp;&amp; echo RULES_OK</automated>
  </verify>
  <done>`docs/CODE_STYLE.md` contains seven numbered rule sections in the order 1-7, all before the `## Adding a rule` heading, with no eighth rule. Across the file there are exactly 14 fenced java blocks, 7 lines starting `**Why:**`, 7 `Discouraged:` labels and 7 `Preferred:` labels. Rule 1's discouraged example (`HttpStatusCode.valueOf(404)`) is still present, proving Rule 1 was not rewritten. The real repo symbols `verifyOwnershipOfTask`, `pair.getSecond().getId()`, `Assertions.catchException`, `AbstractAppTest`, `countQueries`, `shouldReturn_whenColumnExists`, `testWithAuthenticatedUser_`, `atLeastOneFieldPopulated`, `orElseThrow` and `verifyOwnershipOfBoard` all appear. The verify command prints `RULES_OK`. `git diff --numstat -- docs/CODE_STYLE.md` reports 0 deleted lines.</done>
</task>

<task type="auto">
  <name>Task 2: Replace numeric status literals with HttpStatus in TaskLockingE2ETest</name>
  <files>src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java</files>
  <action>
Bring this file into compliance with `docs/CODE_STYLE.md` Rule 1 (enums over magic int constants, with `org.springframework.http.HttpStatus` named as the canonical case) — the one existing violation the convention survey surfaced [CONTEXT: "E2E HTTP status fix (Rule 1 conflict)", locked as fix-the-code-not-the-rule].

Add `import org.springframework.http.HttpStatus;` immediately after the existing `import org.springframework.data.util.Pair;` line, which is the alphabetically correct slot and what Spotless will enforce.

Then change all five status assertions so the expected value is supplied as an `HttpStatus` enum constant's `.value()` instead of a bare number. Because `statusCode()` returns `int`, the expected side must be `.value()` on the constant, not the constant itself — a bare `HttpStatus` constant will not compile against an `int` actual. The five sites, by test method:

- `concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict` (~lines 63, 78, 92): the `firstResponse` assertion expects success, mapping to `HttpStatus.OK.value()`; the `secondResponse` and `retryResponse` assertions both expect a version conflict, mapping to `HttpStatus.CONFLICT.value()`.
- `update_withCurrentVersion_succeedsAndReturnsIncrementedVersion` (~line 122): success, mapping to `HttpStatus.OK.value()`.
- `update_withoutVersion_returnsBadRequest` (~line 150): validation failure, mapping to `HttpStatus.BAD_REQUEST.value()`.

Line numbers were verified during planning but re-locate each site by its enclosing method name and surrounding assertion text before editing rather than trusting the offsets.

This is a pure expected-value substitution: preserve every assertion's actual-value expression, the surrounding `// Arrange` / `// Act` / `// Assert` comments, the response-body assertions, the DTO builders, and the method order exactly as they are. Do not renumber, reorder, rename, reformat or add tests. Do not touch the version assertions, which compare against `startingVersion` and carry no numeric literal.
  </action>
  <verify>
    <automated>F=src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java; grep -q '^import org\.springframework\.http\.HttpStatus;$' "$F" &amp;&amp; [ "$(grep -c 'HttpStatus\.OK\.value()' "$F")" -eq 2 ] &amp;&amp; [ "$(grep -c 'HttpStatus\.CONFLICT\.value()' "$F")" -eq 2 ] &amp;&amp; [ "$(grep -c 'HttpStatus\.BAD_REQUEST\.value()' "$F")" -eq 1 ] &amp;&amp; ! grep -Eq 'isEqualTo\([0-9]' "$F" &amp;&amp; [ "$(grep -c '@Test' "$F")" -eq 3 ] &amp;&amp; ./gradlew spotlessCheck &amp;&amp; ./gradlew test --tests "*TaskLockingE2ETest*" &amp;&amp; echo TASK_E2E_OK</automated>
  </verify>
  <done>`TaskLockingE2ETest.java` imports `org.springframework.http.HttpStatus` and asserts status via `HttpStatus.OK.value()` twice, `HttpStatus.CONFLICT.value()` twice and `HttpStatus.BAD_REQUEST.value()` once. No assertion in the file compares against a bare numeric literal. The file still declares exactly 3 tests, `./gradlew spotlessCheck` passes, and `./gradlew test --tests "*TaskLockingE2ETest*"` passes. The verify command prints `TASK_E2E_OK`.</done>
</task>

<task type="auto">
  <name>Task 3: Replace numeric status literals with HttpStatus in ColumnLockingE2ETest</name>
  <files>src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java</files>
  <action>
Apply the identical treatment Task 2 applied to the task-locking test, to the column-locking test [CONTEXT: "E2E HTTP status fix (Rule 1 conflict)"]. The two files are structural mirrors — same three test method names, same assertion sequence, same expected statuses — so mirror the edit rather than re-deriving it.

Add `import org.springframework.http.HttpStatus;` immediately after `import org.springframework.data.util.Pair;`.

Then supply all five expected statuses as `HttpStatus` constants' `.value()` (the actual side is an `int`, so `.value()` is required for the comparison to compile). The five sites, by test method:

- `concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict` (~lines 54, 69, 83): `firstResponse` maps to `HttpStatus.OK.value()`; `secondResponse` and `retryResponse` both map to `HttpStatus.CONFLICT.value()`.
- `update_withCurrentVersion_succeedsAndReturnsIncrementedVersion` (~line 112): `HttpStatus.OK.value()`.
- `update_withoutVersion_returnsBadRequest` (~line 139): `HttpStatus.BAD_REQUEST.value()`.

Re-locate each site by its enclosing method name rather than trusting the offsets. Preserve every actual-value expression, the section comments, the `ColumnResponseDTO` body assertions and the `UpdateColumnRequestDTO` builders untouched. This task changes expected values and adds one import — nothing else.
  </action>
  <verify>
    <automated>F=src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java; grep -q '^import org\.springframework\.http\.HttpStatus;$' "$F" &amp;&amp; [ "$(grep -c 'HttpStatus\.OK\.value()' "$F")" -eq 2 ] &amp;&amp; [ "$(grep -c 'HttpStatus\.CONFLICT\.value()' "$F")" -eq 2 ] &amp;&amp; [ "$(grep -c 'HttpStatus\.BAD_REQUEST\.value()' "$F")" -eq 1 ] &amp;&amp; ! grep -Eq 'isEqualTo\([0-9]' "$F" &amp;&amp; [ "$(grep -c '@Test' "$F")" -eq 3 ] &amp;&amp; ./gradlew spotlessCheck &amp;&amp; ./gradlew test --tests "*ColumnLockingE2ETest*" &amp;&amp; echo COLUMN_E2E_OK</automated>
  </verify>
  <done>`ColumnLockingE2ETest.java` imports `org.springframework.http.HttpStatus` and asserts status via `HttpStatus.OK.value()` twice, `HttpStatus.CONFLICT.value()` twice and `HttpStatus.BAD_REQUEST.value()` once. No assertion in the file compares against a bare numeric literal. The file still declares exactly 3 tests, `./gradlew spotlessCheck` passes, and `./gradlew test --tests "*ColumnLockingE2ETest*"` passes. The verify command prints `COLUMN_E2E_OK`.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| (none introduced) | Documentation plus test-assertion changes. No production code path, no new input parsing, no network surface, no auth logic, no dependency added. `org.springframework.http.HttpStatus` is already on the compile classpath and already imported by `GlobalExceptionHandler`. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-quick-01 | Tampering | `TaskLockingE2ETest` / `ColumnLockingE2ETest` | medium | mitigate | A careless substitution could weaken the optimistic-locking regression net — e.g. mapping the stale-write assertion to a success status, so a future lock regression passes silently. Tasks 2 and 3 pin the exact expected multiset per file (two `OK`, two `CONFLICT`, one `BAD_REQUEST`), assert the test count is still 3, and require the targeted suite to pass, so any semantic drift fails verification. |
| T-quick-02 | Tampering | `docs/CODE_STYLE.md` | low | mitigate | An over-broad edit could silently drop or reword Rule 1, removing the very guardrail Tasks 2-3 enforce. Task 1 forbids `Write` on this file, gates on Rule 1's discouraged example still being present, and requires zero deleted lines in `git diff --numstat`. |
| T-quick-03 | Information disclosure | `docs/CODE_STYLE.md` | low | accept | The new rules quote class names, annotations and validation messages already present in the repository; no credentials, endpoints, schema or infrastructure detail is added. |
| T-quick-SC | Tampering | package installs | low | accept | No package-manager install occurs in this plan — no npm/pip/cargo/Gradle dependency is added or changed. The supply-chain surface is unchanged, so the legitimacy gate does not apply. |
</threat_model>

<verification>
1. Task 1 verify prints `RULES_OK`; Task 2 prints `TASK_E2E_OK`; Task 3 prints `COLUMN_E2E_OK`.
2. `./gradlew spotlessCheck` passes — the two added imports sit in the position Google Java Format AOSP expects.
3. `./gradlew test` passes in full, not just the two targeted classes, confirming nothing else regressed.
4. `git status --porcelain` lists exactly three modified files: `docs/CODE_STYLE.md`, `TaskLockingE2ETest.java`, `ColumnLockingE2ETest.java`. No production source under `src/main/` is touched.
5. `git diff --numstat -- docs/CODE_STYLE.md` reports 0 deletions — the six rules were inserted, nothing was rewritten.
6. `git diff -- src/test` shows only import additions and expected-value substitutions: no assertion's actual-value expression, section comment, DTO builder or method name changed.
</verification>

<success_criteria>
- `docs/CODE_STYLE.md` holds seven rules in the locked order: enums (existing), ownership-verified loading, AssertJ/catchException, no-mocks testing, `@Nested`/AAA test structure, `Update*RequestDTO` shape, `Optional` isEmpty()-guard unwrapping.
- Each new rule stands alone — statement, `**Why:**`, discouraged/preferred pair — and is readable without the research doc, citing real classes from this repo rather than invented examples.
- No rule was added beyond the six selected; C5, C7, C8, C9 and L1-L5 are absent.
- Rule 1, the intro, the `## Rules` heading and the `## Adding a rule` section are unchanged.
- Both locking E2E tests express every expected HTTP status through `org.springframework.http.HttpStatus`, resolving the file's self-contradiction with its own Rule 1.
- `./gradlew spotlessCheck` and `./gradlew test` both pass.
- No file outside the three listed in `files_modified` was touched.
</success_criteria>

<output>
Create `.planning/quick/260801-gib-survey-the-repo-for-existing-code-conven/260801-gib-SUMMARY.md` when done.
</output>
