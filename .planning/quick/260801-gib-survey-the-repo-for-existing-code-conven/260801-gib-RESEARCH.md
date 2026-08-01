# Codebase Convention Survey — Candidates for docs/CODE_STYLE.md

**Researched:** 2026-08-01
**Scope:** `src/main/java/com/vrudenko/kanban_board/**` (74 files), `src/test/java/**` (15 files)
**Method:** Full read of every layer (entity, DTO, mapper, repository, service, controller, exception, handler, config, constants, unit tests, MockMvc controller tests, REST Assured E2E tests) + grep counts to prove consistency.
**Confidence:** HIGH — every candidate below is backed by counted occurrences and file:line citations from this session's reads.

## What Was Excluded

Already covered by `.claude/CLAUDE.md` Conventions, so **not** re-proposed: class/file naming patterns, `App{Type}` exception naming and constructor-based messaging, `@Autowired` field injection, MapStruct `@Mapper` config, `ApiPaths`/`ValidationConstants` centralization, `@PathVariable @NotBlank`, `@Valid` on request bodies, controller param order, Spotless/AOSP formatting, `@Transactional` placement, `entityManager.flush()/clear()` after bulk deletes.
Already covered by `docs/CODE_STYLE.md` Rule 1: enums over magic int/String constants.

---

## Candidate List (ranked)

### C1. AssertJ is always fully qualified, and exceptions are asserted via `Assertions.catchException` — never `assertThrows`

**Consistency: 100%.** `Assertions.assertThat` — **146** occurrences. `import static org.assertj...` — **0**. `Assertions.catchException` — **43**. JUnit `assertThrows` — **0**.

Evidence:
- `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java:13` (`import org.assertj.core.api.Assertions;`), `:89-94`, `:151-154`
- `src/test/java/com/vrudenko/kanban_board/service/ColumnServiceTest.java:11`, `:66`, `:118`, `:132`
- `src/test/java/com/vrudenko/kanban_board/service/OwnershipVerifierServiceTest.java:9`, `:40-42`
- `src/test/java/com/vrudenko/kanban_board/service/UserServiceTest.java:59`, `:85`, `:159`

**Why codify:** an agent's default reflex is `import static org.assertj.core.api.Assertions.assertThat;` plus `assertThrows(X.class, () -> …)`. Both would be brand-new to this repo. The `catchException` form is also deliberately different in shape: it captures the throwable into a `var`, then asserts on it — which lets a test assert *both* the exception and a follow-up state check (see `TaskServiceTest.java:319-322`), something `assertThrows` makes awkward.

> **Bad:** `assertThrows(AppEntityNotFoundException.class, () -> taskService.findById(userId, taskId));`
> **Good:** `var exception = Assertions.catchException(() -> taskService.findById(userId, taskId)); Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);`

---

### C2. Tests are grouped in a `@Nested` class named after the method under test, named `should<Outcome>_when<Condition>`, with `// arrange` / `// act` / `// assert` section comments

**Consistency: very high.** **37** `@Nested` classes across 9 test files; **57** methods matching `should…_when…`; **39** more matching `testWithAuthenticatedUser_should…_when…` (the MockMvc controller-test variant); **123** arrange/act/assert comment lines. `@DisplayName` — **0** occurrences (the method name *is* the display name).

Evidence:
- `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java:54-56` (`class FindAllByColumnIdTest` → `shouldReturn_whenColumnExists`), `:128`, `:184`, `:243`, `:262`; arrange/act/assert at `:60,62,65`
- `src/test/java/com/vrudenko/kanban_board/service/ColumnServiceTest.java:23-27`, `:95-99`, `:139-143`
- `src/test/java/com/vrudenko/kanban_board/service/OwnershipVerifierServiceTest.java:47-49`
- `src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java:44-47`, `:78-81`, `:100-103`, `:176-179`

Note the two sub-dialects: service tests use `should…_when…`; controller tests prefix with the auth context (`testWithAuthenticatedUser_should…_when…`). Worth deciding whether to codify both or normalize.

**Why codify:** an agent will otherwise write flat test classes with `@DisplayName("should return tasks when column exists")` and no section comments — structurally valid but visibly foreign here.

> **Bad:** `@Test @DisplayName("returns tasks") void findAllByColumnId_ok() { … }`
> **Good:** `@Nested class FindAllByColumnIdTest { @Test void shouldReturn_whenColumnExists() { // arrange … // act … // assert … } }`

---

### C3. Domain services never call `repository.findById` — they load through the ownership-verified `findById(userId, id)` and pass the **verified entity's** id downstream

**Consistency: 100% in the four domain services.** Direct `…Repository.findById` in `src/main/.../service/` appears **only** in `OwnershipVerifierService` (the chain root) and `UserService` (the identity root) — **zero** times in `BoardService`, `ColumnService`, `TaskService`, `SubtaskService`. `ownershipVerifierService.verifyOwnershipOf…` — **13** call sites; `pair.getSecond().getId()` — **8**.

Evidence:
- `src/main/java/com/vrudenko/kanban_board/service/TaskService.java:59-63` (the loader), `:77` (update uses it), `:104`, `:47-48` (`taskRepository.findAllByColumnId(pair.getSecond().getId())` — not the raw `columnId` param)
- `src/main/java/com/vrudenko/kanban_board/service/ColumnService.java:80-84`, `:55`, `:59`, `:129`
- `src/main/java/com/vrudenko/kanban_board/service/BoardService.java:59-63`, `:42`, `:68`
- `src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java:44-48`, `:53`, `:72`

Two distinct rules are bundled here and could be split:
(a) every mutating/reading service method begins by resolving the entity through the ownership-verified loader;
(b) once verified, downstream repository calls use `pair.getSecond().getId()`, never the raw path-variable string.

**Why codify:** this is the codebase's entire access-control model, and it is enforced *only by convention* — nothing in the type system stops an agent writing `taskRepository.findById(taskId)` in `TaskService`, which compiles, passes a naive test, and silently removes the ownership check. CLAUDE.md's Anti-Patterns section names "Reusing findById() Without Re-verification" as a heading but the body is empty.

> **Bad:** `var task = taskRepository.findById(taskId).get(); task.setTitle(dto.getTitle());`
> **Good:** `var task = findById(userId, taskId); // delegates to ownershipVerifierService.verifyOwnershipOfTask`

---

### C4. Tests use no mocks: every test is a `@SpringBootTest` extending `AbstractAppTest`, exercising real wiring against H2

**Consistency: 100%.** Mockito / `@Mock` / `@MockBean` / `mock(` — **0** occurrences. `@WebMvcTest` / `@DataJpaTest` slice annotations — **0**. `extends AbstractApp…` — **15**, i.e. every test file except the abstract bases themselves. Fixtures (`owningUser`, `noBoardsUser`, `mockPopulatedBoard`, `mockColumns`, `mockPopulatedTask`, `mockSubtasks`) are built in one shared `@BeforeEach` and torn down by a single cascade delete.

Evidence:
- `src/test/java/com/vrudenko/kanban_board/AbstractAppTest.java:28`, `:36-68` (fixture fields), `:70-135` (`setup()`), `:137-140` (`cleanup()` → `userService.deleteAll()`)
- `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java:19-20`
- `src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java:30-32` (`@SpringBootTest @AutoConfigureMockMvc … extends AbstractAppTest`)
- `src/test/java/com/vrudenko/kanban_board/AbstractAppE2ETest.java:14`, `:38-54` (shared `signin()` helper returning a cookie `Pair`)

Also worth folding in: the `countQueries(Runnable)` helper (`AbstractAppTest.java:172-183`) is the single sanctioned way to assert query counts, and its Javadoc records *why* `getPrepareStatementCount()` and not `getQueryExecutionCount()`.

**Why codify:** asked to "add a unit test for `TaskService`", an agent will reach for `@ExtendWith(MockitoExtension.class)` + `@Mock TaskRepository` by default. That would be the first mock in the repo and would bypass exactly the ownership/JPA behaviour these tests exist to cover. It also needs telling that new fixtures belong in `AbstractAppTest`, not inline in each test class.

> **Bad:** `@ExtendWith(MockitoExtension.class) class TaskServiceTest { @Mock TaskRepository taskRepository; @InjectMocks TaskService taskService; }`
> **Good:** `@SpringBootTest public class TaskServiceTest extends AbstractAppTest { @Autowired TaskService taskService; }`

---

### C5. Null checks are written `Optional.ofNullable(x).isPresent()`, never `x != null`

**Consistency: 100%.** `!= null` in `src/main` — **0** occurrences. `Optional.ofNullable` — **8**.

Evidence:
- `src/main/java/com/vrudenko/kanban_board/service/TaskService.java:84`, `:87`
- `src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java:55`, `:59`
- `src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java:29-30`
- `src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/UpdateSubtaskRequestDTO.java:25-26`

**Why codify:** zero `!= null` across the whole of `src/main` is an unusually clean signal — this is clearly deliberate, and an agent writing partial-update logic will reach for `if (dto.getTitle() != null)` without a second thought. Note the idiom is used for its *readability*, not its return value (`.isPresent()` on a freshly-wrapped nullable is equivalent to a null check), so the rule is about house style, not semantics — worth stating that explicitly if codified.

> **Bad:** `if (dto.getTitle() != null) { task.setTitle(dto.getTitle()); }`
> **Good:** `if (Optional.ofNullable(dto.getTitle()).isPresent()) { task.setTitle(dto.getTitle()); }`

---

### C6. `Update*RequestDTO` has a fixed shape: `@JsonInclude(NON_NULL)`, `@NotNull Long version`, and a private `@AssertTrue atLeastOneFieldPopulated()` when more than one field is optional

**Consistency: 4/4 update DTOs.**

Evidence:
- `src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java:19` (`@JsonInclude`), `:25` (`@NotNull private Long version`), `:27-33` (`@AssertTrue … atLeastOneFieldPopulated()`)
- `src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/UpdateSubtaskRequestDTO.java:17`, `:23-29` — same method name, same message shape (`"Either 'title' or 'isCompleted' (or both) must be provided."`)
- `src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java:18`, `:26` (single field, so no `@AssertTrue`)
- `src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java:13`, and `:15-18` carries a Javadoc reminder: *"If more fields are added, don't forget to add validation so at least one of them are present"*

Note the deliberate asymmetry: `Save*RequestDTO` and `*ResponseDTO` never carry `@JsonInclude` (`SaveTaskRequestDTO.java:13-17`, `TaskResponseDTO.java:10-14`) — it marks a DTO as a *partial* update.

**Why codify:** adding a new updatable entity, an agent will produce an `UpdateXRequestDTO` missing the `@NotNull version` (silently disabling optimistic locking — the whole point of Phase 1), missing `@JsonInclude`, and with a differently named cross-field validator. The repo already left a comment asking future authors to remember this; a style rule is the stronger version of that comment.

> **Bad:** `public class UpdateXRequestDTO { private String name; private Long version; }`
> **Good:** `@JsonInclude(NON_NULL) public class UpdateXRequestDTO { @XName private String name; @NotNull private Long version; @AssertTrue(message="…") private boolean atLeastOneFieldPopulated() { … } }`

---

### C7. Production methods that exist only for tests are marked Guava `@VisibleForTesting` and kept at the narrowest possible visibility

**Consistency: 6/6 such methods.**

Evidence:
- `src/main/java/com/vrudenko/kanban_board/service/TaskService.java:170-173` (`@VisibleForTesting void deleteAll()` — package-private)
- `src/main/java/com/vrudenko/kanban_board/service/BoardService.java:88-94` (package-private) and `:96-99` (public, because tests live in a different package)
- `src/main/java/com/vrudenko/kanban_board/service/ColumnService.java:119-122`
- `src/main/java/com/vrudenko/kanban_board/service/UserService.java:95-98`, `:100-106`

**Why codify:** an agent adding a test helper will make it `public` with no annotation, and the next reader can't tell production API from test scaffolding. The annotation is already a project dependency (Guava, `build.gradle:54`) and is used nowhere else, so it reads unambiguously.

> **Bad:** `public void deleteAll() { taskRepository.deleteAll(); }`
> **Good:** `@VisibleForTesting void deleteAll() { taskRepository.deleteAll(); }`

---

### C8. Local variables are declared with `var`; an explicit type appears only when the generic signature is load-bearing

**Consistency: high, with principled exceptions.** **65** `var` declarations in `src/main`, **491** in `src/test`.

Evidence (rule):
- `src/main/java/com/vrudenko/kanban_board/service/TaskService.java:35`, `:45`, `:77`, `:148`
- `src/main/java/com/vrudenko/kanban_board/service/ColumnService.java:63`, `:99`
- `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java:46`, `:90`, `:96`
- `src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java:49-52`

Evidence (exceptions — the explicit type carries information `var` would hide):
- `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java:66` (`Pair<String, HttpStatusCode>`), `:95` (`Map<String, String> errors`)
- `src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java:33` (`Pair<String, String> cookie = signin();` — the pair's meaning is documented on `AbstractAppE2ETest.signin()` at `:35-37`)

Sub-pattern worth noting separately: `TaskServiceTest` uses `final var` for arrange-phase inputs (41 occurrences in that one file, e.g. `:35`, `:59-60`, `:267-273`) while other test files use plain `var`. Inconsistent across files — codify only if you want `final var` everywhere.

**Why codify:** low risk of an agent getting this catastrophically wrong, but high frequency — writing `TaskEntity task = findById(…)` everywhere would make new code visibly non-native. The interesting half of the rule is the *exception*, which an over-eager "always use var" rule would break.

> **Bad:** `ColumnEntity column = findById(userId, columnId);`
> **Good:** `var column = findById(userId, columnId);` — but keep `Pair<String, HttpStatusCode> x = …` where the generic args are the documentation.

---

### C9. Test data is generated from `dataFactory` bounded by `ValidationConstants`; "does not exist" ids are `UUID.randomUUID().toString()`

**Consistency: high.** `dataFactory.` — **53** occurrences; `UUID.randomUUID()` — **29**, used exclusively to construct ids guaranteed absent from the DB.

Evidence:
- `src/test/java/com/vrudenko/kanban_board/AbstractAppTest.java:143`, `:151-153`, `:162-168` (every fixture string is `dataFactory.getRandomWord/getRandomText(ValidationConstants.MIN_… + n)`)
- `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java:86`, `:115`, `:148` (absent ids) and `:270-273` (valid new values)
- `src/test/java/com/vrudenko/kanban_board/service/UserServiceTest.java:56`, `:135`, `:153-155`
- `src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java:187-188`, `:217`
- `src/test/java/com/vrudenko/kanban_board/dto/SignupRequestDTOTest.java:17-25`, `:91`, `:106` (boundary cases expressed as `MIN_… - 1` / `MAX_… + 1`, never literal lengths)

**Why codify:** an agent will hard-code `"Test Task"` and `"nonexistent-id"`. The constants-derived form is what makes boundary tests survive a change to `ValidationConstants` — `MIN_USER_DISPLAY_NAME_LENGTH - 1` stays correct when the minimum moves; `"ab"` does not. (Caveat: `SignupRequestDTOTest.java:69-74` documents a known flakiness cost of random generation — worth acknowledging in the rule rather than pretending it's free.)

> **Bad:** `var title = "My Task"; var missingId = "does-not-exist";`
> **Good:** `var title = dataFactory.getRandomWord(ValidationConstants.MIN_TASK_TITLE_LENGTH + 2); var missingId = UUID.randomUUID().toString();`

---

### C10. `Optional` from a repository is unwrapped with an `isEmpty()` guard that throws `App…Exception("EntityName")` — `orElseThrow` is never used

**Consistency: 100%, but see the caveat.** `orElseThrow` in `src/main` — **0** occurrences. `isEmpty()` guard-then-`get()` — **11**.

Evidence:
- `src/main/java/com/vrudenko/kanban_board/service/OwnershipVerifierService.java:39-49`, `:63-67`, `:77-81`, `:91-95`
- `src/main/java/com/vrudenko/kanban_board/service/UserService.java:36-43`, `:46-53`, `:86-93`
- `src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java:75-83`

**Why this is ranked last:** it is the most consistent pattern in the survey *and* the one most likely to be an accident of habit rather than a preference. `Optional.orElseThrow(() -> new AppEntityNotFoundException("Task"))` is the more idiomatic modern Java and is strictly shorter. Codify it in whichever direction you prefer — but codify *something*, because an agent will write `orElseThrow` by default and produce a file that reads differently from its five siblings. **This one needs your decision, not just your approval.**

> **Current house style:** `var task = taskRepository.findById(id); if (task.isEmpty()) { throw new AppEntityNotFoundException("Task"); } return task.get();`
> **Idiomatic alternative:** `return taskRepository.findById(id).orElseThrow(() -> new AppEntityNotFoundException("Task"));`

---

## Lower-value candidates (real, but narrow)

| # | Pattern | Evidence | Note |
|---|---------|----------|------|
| L1 | `long` → `int` narrowing goes through Guava `Ints.checkedCast`, never a `(int)` cast | `TaskService.java:55`, `ColumnService.java:121`; repositories return `long` (`TaskRepository.java:10`, `ColumnRepository.java:12`) | Only 2 sites, but an agent writes `(int)` by reflex and silently loses overflow detection |
| L2 | A `@Version` field must be `@EqualsAndHashCode.Exclude` on any entity using Lombok `@Data`/`@EqualsAndHashCode` | `ColumnEntity.java:34-37` (excluded) vs `TaskEntity.java:36-38` (no `@EqualsAndHashCode` at all, so no exclusion needed) | Only one instance, but the failure mode is nasty: entity identity breaks across saves |
| L3 | DTOs carry the Lombok quartet `@Getter @Setter @Builder @EqualsAndHashCode` | 13/13 DTOs have Getter+Setter+EqualsAndHashCode; 11/13 add `@Builder` (`UserResponseDTO.java:9-11` and `DeleteBoardByIdRequestDTO.java:10-12` lack it, and both are never `.builder()`-constructed) | Entities deliberately differ: `@Getter @Setter`, not `@Data` — `ColumnEntity.java:18` is the lone `@Data` outlier |
| L4 | An explicit `@Query`/`@Modifying` repository method carries a `//` comment justifying why the derived method wasn't good enough | `SubtaskRepository.java:14-20` (the only `@Query` in the repo; 8 other methods are all derived) | Really a "prefer derived queries; justify deviations" rule with a single supporting instance |
| L5 | Non-obvious decisions get a `//` block above the method recording the measurement and the rejected alternative | `TaskServiceTest.java:25-29` ("33 queries for 8 tasks, measured before the fix"), `OwnershipVerifierServiceTest.java:20-26`, `SubtaskRepository.java:14-17`, `TaskService.java:93-96`, `AbstractAppTest.java:172-177` | CLAUDE.md covers Javadoc-on-complex-methods; the *un*covered part is "cite the measured number and name the alternative you rejected" |

## Observed but NOT recommended (inconsistent — would be inventing a rule)

- **Mapper method naming.** `TaskMapper`/`ColumnMapper`/`SubtaskMapper` use `to<Entity>ResponseDTO` (`TaskMapper.java:17`, `ColumnMapper.java:21`), while `BoardMapper`/`UserMapper` use bare `toResponseDTO` (`BoardMapper.java:20`, `UserMapper.java:25`). 3 vs 2 — a coin flip, not a convention. Worth *resolving* someday, not codifying today.
- **Request-DTO parameter naming in services.** Controllers consistently use `dto` (`BoardController.java:52`, `TaskController.java:49`, `SubtaskController.java:53`), but services mix `dto` (`TaskService.java:76`), `boardDTO` (`BoardService.java:67`), `columnDTO` (`ColumnService.java:63`), `taskDTO` (`ColumnService.java:73`).
- **Controller class visibility.** `BoardController.java:30` and `ColumnController.java:29` are `public`; `TaskController.java:26` and `SubtaskController.java:32` are package-private. 2 vs 2.
- **`@Validated` on the controller class.** Present on `BoardController.java:28` and `AuthenticationController.java:30`, absent on `ColumnController`, `TaskController`, `SubtaskController`. This one may be a latent bug rather than a style question.

## Incidental finding

The E2E tests assert HTTP status with raw ints — `isEqualTo(200)`, `isEqualTo(409)`, `isEqualTo(400)` at `TaskLockingE2ETest.java:63,78,92,122,150` and `ColumnLockingE2ETest.java:54,69,83,112,139`. That contradicts **existing CODE_STYLE.md Rule 1** (enums over magic int constants; `HttpStatus` named as the canonical case). Not a new rule — an existing-rule violation to fix, or an explicit carve-out to add to Rule 1 if raw ints are acceptable in test assertions.

## Suggested pick order

If you only want two or three rules: **C3** (highest consequence — it's the access-control model), **C1** (highest frequency of agent divergence), **C4** (an agent's very first instinct on "write a test" is wrong here). **C10** needs a decision from you before it can be written either way.
