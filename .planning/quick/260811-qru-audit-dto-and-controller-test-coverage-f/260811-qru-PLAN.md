---
phase: quick-260811-qru
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - .planning/quick/260811-qru-audit-dto-and-controller-test-coverage-f/260811-qru-FINDINGS.md
  - src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java
  - .planning/todos/pending/
  - .planning/todos/completed/
  - .planning/STATE.md
autonomous: false
requirements: [QRU-01, QRU-02, QRU-03, QRU-04]
user_setup: []

estimate:
  tokens: 90000
  raw_tokens: 45000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "Every @PostMapping/@PutMapping/@PatchMapping handler across all eight @RestController classes is recorded in FINDINGS.md with its DTO-parameter binding annotations verified at file:line (QRU-01)"
    - "Every Save*RequestDTO / Update*RequestDTO field appears in a side-by-side effective-constraint table, with composed custom annotations resolved to their underlying constraints (QRU-02)"
    - "Every create/update endpoint is classified as having or lacking controller-tier JSON-body coverage per docs/CODE_STYLE.md rule 4 (QRU-03)"
    - "Every finding carries exactly one disposition — FIX-NOW, FILE-TODO, CONFIRMED-EXISTING, or NO-ACTION with a written reason; no finding is noted and dropped (QRU-04)"
    - "A mutating handler whose DTO parameter is missing @RequestBody or @Valid fails ./gradlew test"
    - "The source audit todo is closed with a resolution note naming what was found and how each finding was disposed of"
  artifacts:
    - .planning/quick/260811-qru-audit-dto-and-controller-test-coverage-f/260811-qru-FINDINGS.md
    - src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java
    - .planning/todos/completed/2026-08-11-audit-dto-and-controller-test-coverage-for-validation-bindin.md
  key_links:
    - "New ArchUnit rule lives inside the existing LayeringArchTest @AnalyzeClasses class so it reuses the already-imported class graph (a new @AnalyzeClasses class would re-import it)"
    - "Each FIX-NOW change is linked to a controller-tier regression test that drives a real JSON body, not a query param and not a direct service call"
    - "Each FILE-TODO finding ID in FINDINGS.md maps to exactly one new file under .planning/todos/pending/"
---

<objective>
Systematically audit all eight `@RestController` classes and all fourteen `*RequestDTO` classes for the two failure modes that silently shipped two real defects on 2026-08-11 — a mutating handler binding its DTO from query/form params instead of the JSON body, and a validation-annotation asymmetry between sibling DTOs — then dispose of every finding explicitly.

Purpose: the two defects found on 2026-08-11 (`TaskController.addSubtaskByTaskId` missing `@RequestBody`; `SaveSubtaskRequestDTO.title` missing `@NotBlank`) were both found by accident while fixing something else. Two hits from one small area (subtask creation alone) is enough signal to check the other seven controllers systematically rather than assume they are clean.

Output: a written FINDINGS.md with file:line evidence and a disposition per finding; a permanent ArchUnit guard making failure mode 1 structurally unreopenable; fixes-with-tests for mechanically trivial findings; a filed todo for every finding requiring judgment; the source todo closed.

**Scoping constraint, quoted verbatim from the source todo — this governs the whole plan:** "Whoever picks this up should produce a findings list (which endpoints/DTOs have gaps) before deciding whether to fix everything in one pass or file individual follow-up todos per finding — this todo is the audit, not a blank check to fix everything found under its own scope."
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.claude/CLAUDE.md
@docs/CODE_STYLE.md
@.planning/todos/pending/2026-08-11-audit-dto-and-controller-test-coverage-for-validation-bindin.md
@src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java
@src/test/java/com/vrudenko/kanban_board/handler/ErrorEnvelopeConsistencyTest.java
</context>

<planning_inventory>
Collected during planning by direct grep over `src/main`. **Verify each row rather than trusting it** — this exists so the audit starts from a checklist instead of rediscovering the surface, and so a row that turns out wrong is itself a finding about this inventory.

**Mutating handlers with a DTO parameter (14 across 8 controllers).** All 14 appeared to carry both `@Valid` and `@RequestBody` at planning time:

| Controller | Handler | Line | DTO |
|---|---|---|---|
| BoardController | save | 50-52 | SaveBoardRequestDTO |
| BoardController | updateById | 67-70 | UpdateBoardRequestDTO |
| BoardController | addColumnByBoardId | 75-78 | SaveColumnRequestDTO |
| ColumnController | addTaskByColumnId | 46-49 | SaveTaskRequestDTO |
| ColumnController | updateById | 56-59 | UpdateColumnRequestDTO |
| ColumnController | reorder | 74-77 | ReorderColumnRequestDTO |
| TaskController | updateById | 52-55 | UpdateTaskRequestDTO |
| TaskController | addSubtaskByTaskId | 60-63 | SaveSubtaskRequestDTO (fixed by 260811-me4) |
| SubtaskController | updateById | 54-57 | UpdateSubtaskRequestDTO |
| TaskMoveController | moveToColumn | 34-37 | MoveTaskRequestDTO |
| UserController | updateTheme | 45-46 | UpdateThemeRequestDTO |
| AuthenticationController | signin | 71-72 | SigninRequestDTO |
| AuthenticationController | signup | 110-111 | SignupRequestDTO |
| ActivityController | — | — | no mutating handler (GET only) |

**Composed custom annotations — resolve these before comparing DTOs, because the asymmetry is invisible at the field declaration:**

| Annotation | Composes | Implies non-blank? |
|---|---|---|
| `@AppEmail` | `@NotBlank` + `@Email` | yes |
| `@Password` | `@NotBlank` + `@Size` + `@Pattern` | yes |
| `@BoardName` | `@Size` + `@Pattern` | **no** |
| `@DisplayName` | `@Size` + `@Pattern` (`^[a-zA-Z ]*$` — `*` admits empty) | **no** |
| `@TaskTitle` | `@Size` | **no** |
| `@SubtaskTitle` | `@Size` | **no** |
| `@Description` | `@Size` | **no** (correct — description is optional) |

**Candidate asymmetries visible at planning time (confirm or refute each; do not assume any is real):**
- `SaveSubtaskRequestDTO.title` is `@SubtaskTitle` only — **already filed** as `.planning/todos/pending/2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md`. Confirm it, do NOT fix it here, do NOT re-file it.
- `SignupRequestDTO.displayName` is `@DisplayName` only, whose pattern admits the empty string — unlike its `@AppEmail`/`@Password` siblings on the same DTO.
- `UpdateColumnRequestDTO.name` carries `@NotBlank`; `UpdateBoardRequestDTO.name` does not. Two single-optional-field update DTOs disagreeing on whether the field may be omitted.
- `SaveTaskRequestDTO` / `SaveColumnRequestDTO` declare `@Size` inline; `SaveBoardRequestDTO` delegates to `@BoardName`. Constraint definitions duplicated rather than shared.
- `DeleteBoardByIdRequestDTO` matched no `@RequestBody` parameter in any controller — possibly dead.

**Test-tier surface:** only four `controller/*ControllerTest.java` classes exist (Board, Column, Subtask, Task) against eight controllers. Create/update endpoints on `TaskMoveController`, `UserController` and `AuthenticationController` are covered — if at all — from `e2e/`, `security/`, `ThemePersistenceTest`, `ColumnOrderingTest`, `TaskOrderingTest`, `SubtaskLockingTest`. Part 3 is the mapping exercise that settles this.
</planning_inventory>

<approach_analysis>
Required by CLAUDE.md before any PLAN.md is created or approved.

**Approach A (chosen) — audit, gate on review, then dispose each finding, plus one permanent mechanical guard.**
Produce FINDINGS.md read-only; pause for operator approval of the proposed dispositions; then add an ArchUnit rule that makes failure mode 1 impossible to reintroduce, apply only mechanically-trivial fixes with regression tests, and file a todo for everything else.

**Approach B — pure documentation audit.** Produce FINDINGS.md, file every finding as a todo, change zero source files.

**Approach C — audit and fix everything found in one pass.** One task, no review gate, every gap closed immediately.

| Approach | Pros / Cons | Why picked |
|---|---|---|
| **A — audit + guard + selective fix** | **Pros:** honors the todo's explicit scoping sentence; the ArchUnit rule converts a one-time pass into a standing invariant, so failure mode 1 cannot recur silently even after this todo is closed; trivial fixes ship with tests instead of aging in a backlog. **Cons:** non-autonomous (one approval pause); the fix/file boundary needs a written rule or it becomes a judgment call the executor makes inconsistently. | Chosen. The ArchUnit rule is the decisive advantage: it is the only option that leaves the codebase structurally unable to reopen the bug that motivated the todo. The fix/file boundary is made deterministic by the four-part eligibility test in Task 2, and the approval gate is exactly the pause the source todo asks for. Precedent: quick task 260811-p9c handled an identical "audit found a cross-controller split" situation by adding an ArchUnit rule plus CODE_STYLE rule 11. |
| **B — documentation only** | **Pros:** smallest blast radius; zero behavior change; strictly inside the todo's letter. **Cons:** ships nothing that prevents recurrence — the next controller added reintroduces failure mode 1 with nothing to catch it; converts a one-annotation fix into backlog overhead that costs more to re-context than to fix; leaves the audit's value entirely dependent on someone reading a markdown file later. | Rejected. The todo forbids a blank check to fix everything; it does not forbid fixing anything. Filing a todo to add one missing annotation is worse than adding it, and B specifically forgoes the permanent guard. |
| **C — fix everything found** | **Pros:** one pass, backlog stays empty, no pause. **Cons:** directly contradicts the todo's quoted scoping sentence; the likely findings include genuine design forks (should `UpdateBoardRequestDTO.name` become required, or should `UpdateColumnRequestDTO.name` become optional?) where picking silently is worse than not picking; tightening validation changes an endpoint's accepted request shape, which a frontend may depend on; unbounded scope in a quick task. | Rejected on the todo's own instruction, and independently on the design-fork problem — at least one likely finding has two defensible answers and no data here to choose between them. |

**Non-obvious trade-offs**

- **Performance (test wall-clock).** The new rule must be added to the existing `LayeringArchTest` class, not a new one. Its Javadoc records that all rules are declared on one `@AnalyzeClasses` class specifically so they share a single class-graph import; a second `@AnalyzeClasses` class re-imports the whole graph and pays that cost again on every `fastTest` run — which the pre-commit hook runs on every commit. Placement here is a measurable cost decision, not a filing preference.
- **Performance (iteration cost).** Full `./gradlew test` averages ~276s and `fastTest` ~242s on this machine (STATE.md, quick task 260811-ixj). Iterate with `--tests` filters; run the full suite once, in Task 3.
- **Security / behavior change.** Adding `@NotBlank` is defense in depth — it moves rejection from a database `NOT NULL` violation (surfacing as a 409, as 260811-me4 measured) to a clean 400 at the trust boundary. It is still a change to what the endpoint accepts: a request that previously succeeded now fails. Every such change therefore needs a controller-tier regression test asserting the new envelope, and must be listed explicitly in the summary rather than folded in silently.
- **State invalidation risk.** `.githooks/pre-commit` runs `spotlessApply` + `fastTest` and blocks a red commit, so no standalone RED-state commit is possible here. Teeth-checks (deliberately violating a rule to prove it bites) must be performed in the working tree and reverted before committing. CLAUDE.md takes precedence over any TDD instruction that would require committing red.
- **Audit completeness risk.** A grep-driven audit can only find what it greps for. The `<planning_inventory>` above is a starting checklist, not a boundary: Part 1 must enumerate handlers from the source files themselves, so a handler the planning grep missed still surfaces.

**ArchUnit rule data-flow, in three sentences.** ArchUnit imports the compiled `com.vrudenko.kanban_board` classes once per `@AnalyzeClasses` class and hands each declared `ArchRule` the same in-memory `JavaClasses` graph. The new rule filters that graph to methods annotated `@PostMapping`/`@PutMapping`/`@PatchMapping` on `@RestController`-annotated owners, then reads each method's `getParameterAnnotations()` (available in the pinned ArchUnit 1.4.2) to inspect annotations per parameter rather than per method. For any parameter whose type name ends in `RequestDTO`, the condition asserts both `org.springframework.web.bind.annotation.RequestBody` and `jakarta.validation.Valid` are present, failing the build with the offending method's name when either is absent.
</approach_analysis>

<tasks>

<task type="auto">
  <name>Task 1: Run the three-part audit and write FINDINGS.md</name>
  <files>.planning/quick/260811-qru-audit-dto-and-controller-test-coverage-f/260811-qru-FINDINGS.md</files>
  <read_first>
    - src/main/java/com/vrudenko/kanban_board/controller/*.java (all seven)
    - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
    - src/main/java/com/vrudenko/kanban_board/dto/**/*RequestDTO.java (all fourteen)
    - src/main/java/com/vrudenko/kanban_board/dto/annotation/*.java (resolve composed constraints)
    - src/test/java/com/vrudenko/kanban_board/controller/*ControllerTest.java (all four)
    - docs/CODE_STYLE.md rule 4 (which-package and which-base-class decision rules)
  </read_first>
  <action>
This task is read-only with respect to `src/` — it changes no Java file. Produce FINDINGS.md with four sections.

**Section 1 — Binding audit (QRU-01).** Enumerate every `@PostMapping`/`@PutMapping`/`@PatchMapping` handler in all eight `@RestController` classes directly from source (do not just tick off the planning inventory — enumerate, then reconcile against it, and record any discrepancy as its own finding). One row per handler: controller, method, `file:line`, DTO parameter type, `@RequestBody` present y/n, `@Valid` present y/n. Also record any mutating handler that takes no DTO, so the table is provably exhaustive rather than filtered.

**Section 1b — Test-side binding audit.** For each mutating handler, locate its controller-tier test and record how the request body is supplied: a real JSON body, a query/form parameter, or a direct service-layer call. Grep the controller and e2e test trees for form-parameter usage on create/update paths — that is the exact shape that hid the subtask bug. A handler whose only test bypasses the HTTP JSON path is a finding even when its annotations are correct, because the annotations are then unprotected by any test.

**Section 2 — Validation-annotation audit (QRU-02).** One row per field across all fourteen `Save*RequestDTO`/`Update*RequestDTO` classes: DTO, field, declared annotations, and the **effective** constraint set after expanding composed custom annotations (the planning inventory's table gives the expansions; re-verify each against `dto/annotation/`). Then compare within cohorts — creation DTOs against creation DTOs, update DTOs against update DTOs — and flag every asymmetry where an analogous field is constrained on one DTO and unconstrained on another. Check each update DTO against CODE_STYLE rule 6 (`@JsonInclude`, `@NotNull Long version`, `@AssertTrue` when more than one independently optional field). Note that a documented, reasoned deviation is not a finding: `UpdateThemeRequestDTO` carries a Javadoc explaining why it omits rule 6's fields.

**Section 3 — Coverage classification (QRU-03).** For every create/update endpoint, record whether a controller-tier test exists that proves that one endpoint's HTTP contract, per CODE_STYLE rule 4's which-package rule. Where coverage lives only at the service tier or only in an `*E2ETest`, say so and say whether rule 4 considers that correct for that endpoint — an endpoint covered by a genuine cross-service flow test is not automatically a gap, but an endpoint with no HTTP-boundary coverage at all is.

**Section 4 — Findings register.** One row per finding with: ID (`F-01`, `F-02`, …), failure mode (binding / validation-asymmetry / coverage / other), `file:line`, evidence, severity, and a **proposed** disposition drawn from exactly this closed set: `FIX-NOW`, `FILE-TODO`, `CONFIRMED-EXISTING`, `NO-ACTION`. `NO-ACTION` requires a written reason in the same row. Apply the eligibility test from Task 2 when proposing — do not propose `FIX-NOW` for anything that fails it.

Record `SaveSubtaskRequestDTO.title` as `CONFIRMED-EXISTING` citing the already-filed todo path; confirming it validates the audit method, and re-filing or fixing it here is out of scope.

Do not modify any Java file, any todo, or STATE.md in this task.
  </action>
  <verify>
    <automated>cd /c/Dev/Repos/kanban-board-backend && F=.planning/quick/260811-qru-audit-dto-and-controller-test-coverage-f/260811-qru-FINDINGS.md; test -f "$F" || { echo "NO FINDINGS FILE"; exit 1; }; for n in BoardController ColumnController TaskController SubtaskController TaskMoveController UserController ActivityController AuthenticationController SaveBoardRequestDTO SaveColumnRequestDTO SaveTaskRequestDTO SaveSubtaskRequestDTO UpdateBoardRequestDTO UpdateColumnRequestDTO UpdateTaskRequestDTO UpdateSubtaskRequestDTO MoveTaskRequestDTO ReorderColumnRequestDTO UpdateThemeRequestDTO SignupRequestDTO SigninRequestDTO DeleteBoardByIdRequestDTO; do grep -q "$n" "$F" || { echo "MISSING FROM AUDIT: $n"; exit 1; }; done; git diff --quiet -- src/ || { echo "TASK 1 MUST NOT TOUCH src/"; exit 1; }; echo AUDIT_COMPLETE</automated>
  </verify>
  <done>FINDINGS.md exists with all four sections; all eight controllers and all fourteen request DTOs appear in it; every finding row carries an ID, a `file:line`, and one proposed disposition from the closed set; `src/` is unchanged.</done>
</task>

<task type="checkpoint:human-verify" gate="blocking">
  <name>Gate: Approve the proposed disposition of every finding before any source file changes</name>
  <what-built>The three-part audit is complete and written to `260811-qru-FINDINGS.md`, with a proposed disposition for every finding. No source file has been touched yet.</what-built>
  <how-to-verify>
Read `.planning/quick/260811-qru-audit-dto-and-controller-test-coverage-f/260811-qru-FINDINGS.md`, in particular Section 4's findings register.

Confirm for each finding that the proposed disposition is the one you want:
- `FIX-NOW` items will be changed in this task, each with a regression test. Check that none of them is a design fork you would rather decide yourself, and note that tightening validation means a request shape that previously succeeded will start returning 400.
- `FILE-TODO` items will become new pending todos, not fixes.
- `NO-ACTION` items will be left alone — check that each written reason convinces you.

This gate exists because the source todo says the findings list comes first and the fix/file decision second. Overriding a proposed disposition here is expected, not exceptional.
  </how-to-verify>
  <resume-signal>Type "approved" to proceed with the proposed dispositions, or name the finding IDs whose disposition should change and what to change them to.</resume-signal>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Add the permanent binding guard, apply approved fixes with regression tests, file the rest</name>
  <files>src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java, .planning/todos/pending/ (new files), plus any src/main and controller-test files the approved FIX-NOW findings name</files>
  <precondition>`.githooks/pre-commit` runs `spotlessApply` + `fastTest` and blocks a red commit, so no deliberately-red state may be committed; teeth-checks are performed in the working tree and reverted before `git commit`.</precondition>
  <behavior>
    - A `@PostMapping`/`@PutMapping`/`@PatchMapping` handler on a `@RestController` whose `*RequestDTO` parameter carries both `@RequestBody` and `@Valid` passes the new rule.
    - The same handler with `@RequestBody` removed fails the new rule, naming the offending method.
    - The same handler with `@Valid` removed fails the new rule, naming the offending method.
    - `AuthenticationController` (in `security/`, not `controller/`) is inside the rule's scope, not skipped.
    - A handler with no `*RequestDTO` parameter is unaffected.
  </behavior>
  <action>
**Step 1 — the guard.** Add a fourth `@ArchTest ArchRule` to the existing `LayeringArchTest` class (not a new `@AnalyzeClasses` class — see the performance note in `<approach_analysis>`; the class Javadoc records the same reason). Name it in the established style of its three siblings, e.g. `mutating_handlers_must_bind_dto_parameters_from_the_request_body`. Implement it as a custom `ArchCondition<JavaMethod>` over `methods()` annotated with `PostMapping`, `PutMapping` or `PatchMapping`, reading `JavaMethod.getParameterAnnotations()` (ArchUnit 1.4.2, pinned in build.gradle line 182) so annotations are inspected per parameter; for each parameter whose raw type simple name ends with `RequestDTO`, require both `org.springframework.web.bind.annotation.RequestBody` and `jakarta.validation.Valid`. Give it a Javadoc naming quick task 260811-me4 as the defect it exists to prevent, matching how the third rule cites 260811-p9c. If `getParameterAnnotations()` proves unusable in this version, fall back to a Spring test that autowires `RequestMappingHandlerMapping` and iterates the real registered handler methods via `MethodParameter` — same guarantee, different mechanism; record which mechanism shipped and why in the summary.

**Step 2 — teeth-check the guard.** In the working tree only: remove `@RequestBody` from one handler, run `./gradlew test --tests '*LayeringArchTest*'`, confirm it fails and names that method; restore; repeat for `@Valid`; restore; confirm green. Record the observed failure messages in the summary. Commit only the green state.

**Step 3 — approved FIX-NOW findings.** A finding is eligible for FIX-NOW only if all four hold; if any fails, it is FILE-TODO regardless of how small it looks:
  (a) it is a single annotation added to or removed from one declaration;
  (b) the corrected form matches an existing sibling declaration exactly, so no new convention is invented;
  (c) a regression test can be added to an existing controller-tier test class without introducing new fixtures;
  (d) it does not require choosing between two defensible designs.
For each, write the regression test first and watch it fail, then apply the annotation, then watch it pass — all in the working tree, committing only once green. Tests go at the controller tier through MockMvc with a real JSON body, extending `AbstractAppMockMvcTest`, following `ErrorEnvelopeConsistencyTest` for envelope assertions and CODE_STYLE rules 3, 5, 9 and 10 for assertion style, `testWithAuthenticatedUser_should<Outcome>_when<Condition>` naming, `var` usage and import grouping.

**Step 4 — everything else.** File one todo per FILE-TODO finding under `.planning/todos/pending/`, using the source todo's frontmatter shape (`created`, `title`, `area`, `severity`, `files`) and its `## Problem` / `## Solution` body structure. Each must be self-contained: a reader must not need FINDINGS.md to act on it, so restate the evidence and `file:line` inline. Do not file a todo for `SaveSubtaskRequestDTO.title` — it is already tracked.

**Step 5 — close the register.** Update FINDINGS.md so every finding's disposition column reflects what actually happened, including any the operator overrode at the gate, and add the resulting artifact per row (test name, or todo filename). No finding may end this task without a recorded outcome.
  </action>
  <verify>
    <automated>cd /c/Dev/Repos/kanban-board-backend && ./gradlew spotlessCheck --console=plain -q && ./gradlew test --tests '*LayeringArchTest*' --console=plain && F=.planning/quick/260811-qru-audit-dto-and-controller-test-coverage-f/260811-qru-FINDINGS.md && ROWS=$(grep -c '^| F-[0-9]' "$F") && DISP=$(grep '^| F-[0-9]' "$F" | grep -c -E 'FIX-NOW|FILE-TODO|CONFIRMED-EXISTING|NO-ACTION') && echo "findings=$ROWS disposed=$DISP" && test "$ROWS" -eq "$DISP" || { echo "UNDISPOSED FINDINGS"; exit 1; }</automated>
  </verify>
  <done>`LayeringArchTest` carries a fourth rule that was observed failing under a deliberate violation and passing after restore; every approved FIX-NOW finding has an annotation change plus a controller-tier regression test that drives a real JSON body; every FILE-TODO finding has a self-contained file under `.planning/todos/pending/`; the findings-register row count equals the disposed-row count; `spotlessCheck` passes.</done>
</task>

<task type="auto">
  <name>Task 3: Full gate, close the source todo, record state</name>
  <files>.planning/todos/completed/2026-08-11-audit-dto-and-controller-test-coverage-for-validation-bindin.md, .planning/STATE.md, .planning/quick/260811-qru-audit-dto-and-controller-test-coverage-f/260811-qru-SUMMARY.md</files>
  <action>
Run `./gradlew spotlessCheck` then the full `./gradlew test` (budget ~5 minutes — 276s average on this machine per 260811-ixj) and read the output before claiming anything about it. Record the test count against the 396-test figure STATE.md carries after 260811-p9c, and confirm zero shrinkage; a drop in test count is a finding in its own right, not a rounding difference.

Move `.planning/todos/pending/2026-08-11-audit-dto-and-controller-test-coverage-for-validation-bindin.md` to `.planning/todos/completed/`, appending a `## Resolution` section that states how many handlers and DTOs were audited, how many findings were raised, and the disposition breakdown — including, explicitly, that `SaveSubtaskRequestDTO.title` was re-found and left to its existing todo. If the audit found no defects in a section, say so plainly; a clean section is a real result and the ArchUnit guard is what makes it durable.

Update STATE.md: remove this todo from Pending Todos, add any newly filed todos in the same one-line-with-path format, and add a Quick Tasks Completed row. Write SUMMARY.md covering what was audited, what was found, what shipped, what was filed, the teeth-check evidence for the new ArchUnit rule, and any request shape that now behaves differently than before.
  </action>
  <verify>
    <automated>cd /c/Dev/Repos/kanban-board-backend && ./gradlew spotlessCheck test --console=plain && test ! -f .planning/todos/pending/2026-08-11-audit-dto-and-controller-test-coverage-for-validation-bindin.md && test -f .planning/todos/completed/2026-08-11-audit-dto-and-controller-test-coverage-for-validation-bindin.md && grep -q 'Resolution' .planning/todos/completed/2026-08-11-audit-dto-and-controller-test-coverage-for-validation-bindin.md && echo CLOSED</automated>
  </verify>
  <done>`spotlessCheck` and the full `test` suite both pass with no test-count shrinkage against the 396 baseline; the source todo is in `completed/` with a Resolution section; STATE.md reflects the closed todo, any new todos, and a Quick Tasks Completed row; SUMMARY.md is written.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| HTTP client → controller | Untrusted JSON bodies and path variables cross here; DTO validation annotations are the enforcement point this audit is examining |
| Controller → service → JPA | An unvalidated field reaching this layer is rejected only by a database constraint, surfacing as a 409 rather than a 400 |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-qru-01 | Tampering | Mutating handlers missing `@RequestBody` — client-supplied body silently ignored, fields default to null | medium | mitigate | Task 2's ArchUnit rule fails the build on any such handler, permanently |
| T-qru-02 | Tampering | `*RequestDTO` fields with no effective non-blank constraint — malformed input reaches the persistence layer | medium | mitigate | Task 1 Section 2 enumerates every field's effective constraint set; Task 2 fixes the trivial cases and files the rest |
| T-qru-03 | Denial of Service | Unbounded string fields reaching the database | low | accept | Every audited field already carries `@Size` via inline or composed annotation; the gap found so far is non-blankness, not length |
| T-qru-04 | Information disclosure | Validation failures leaking internals via a 500 instead of a clean 400 | low | accept | Already mitigated by quick task 260811-p9c (CODE_STYLE rule 11 plus its ArchUnit guard); re-confirm rather than re-solve |

No package-manager installs are performed by this plan, so no package-legitimacy gate applies.
</threat_model>

<verification>
- FINDINGS.md enumerates all 8 controllers, all 14 mutating handlers, all 14 request DTOs, and every create/update endpoint's controller-tier coverage status.
- Every finding has exactly one recorded disposition and a resulting artifact; none is noted and dropped.
- The new ArchUnit rule was observed red under a deliberate violation and green after restore, with the failure messages recorded.
- `./gradlew spotlessCheck` and `./gradlew test` both pass, with no test-count shrinkage against the 396 baseline.
- The source todo is closed with a Resolution note; `SaveSubtaskRequestDTO.title` is neither fixed nor re-filed here.
</verification>

<success_criteria>
The two failure modes that shipped two defects on 2026-08-11 have been checked exhaustively across every controller and request DTO rather than assumed isolated; the binding failure mode can no longer recur silently because the build now rejects it; and every gap the audit surfaced is either fixed with a test or tracked in its own todo.
</success_criteria>

<output>
Create `.planning/quick/260811-qru-audit-dto-and-controller-test-coverage-f/260811-qru-SUMMARY.md` when done.
</output>
