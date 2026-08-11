---
phase: quick-260811-me4
verified: 2026-08-11T17:05:00Z
status: gaps_found
score: 4/4 must-haves verified (STATE.md bookkeeping gap found outside frontmatter must_haves — see gaps)
behavior_unverified: 0
overrides_applied: 0
gaps:
  - truth: "Task 3's own declared deliverable: .planning/STATE.md records the 260811-me4 quick task (Quick Tasks Completed row) and reflects the new/closed todo movements"
    status: failed
    reason: "SUMMARY.md claims STATE.md was updated ('Added Quick Tasks Completed row and Pending Todos entry (left uncommitted per orchestrator instruction; docs commit handled in Step 8)'), but STATE.md has zero diff from HEAD (git diff HEAD -- .planning/STATE.md is empty), no uncommitted working-tree changes exist, and the plan's own Task 3 automated verify command (grep -q '260811-me4' .planning/STATE.md) fails today (exit code 1). No Quick Tasks Completed row for 260811-me4 exists, and the new todo (2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md) is absent from the Pending Todos list."
    artifacts:
      - path: ".planning/STATE.md"
        issue: "No mention of 260811-me4 anywhere in the file; Task 3's <done> criterion 'STATE.md records the quick task and both todo movements' is unmet"
    missing:
      - "A Quick Tasks Completed row for 260811-me4 in .planning/STATE.md"
      - "An entry for .planning/todos/pending/2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md in the Pending Todos section"
---

# Quick Task 260811-me4: Fix subtask creation DTO missing @RequestBody Verification Report

**Task Goal:** Give `TaskController.addSubtaskByTaskId`'s DTO parameter `@RequestBody`, matching every sibling creation endpoint, backed by a controller-tier test, with the source todo closed and the surfaced validation gap filed separately.

**Verified:** 2026-08-11T17:05:00Z
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (from PLAN.md frontmatter `must_haves.truths`)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | POST `.../subtasks` with JSON body `{"title":"..."}` returns 201 and persists a subtask with that exact title | ✓ VERIFIED | `TaskController.java:61` carries `@Valid @RequestBody SaveSubtaskRequestDTO dto` (one-line diff from `8ec9355`→`7c75cd8`, confirmed via `git show f182e7d`). `TaskControllerTest.AddSubtaskByTaskId.testWithAuthenticatedUser_shouldAddSubtask_whenJsonBodyIsPosted` posts a serialized `SaveSubtaskRequestDTO`, asserts `status().isCreated()`, asserts `responseBody.getTitle()` equals the posted title, asserts a non-blank `Location` header, and asserts `subtaskService.findAllByTaskId(userId, taskId)` contains the created id (persistence via a second, independent path, not just the response echo). |
| 2 | Bare query-parameter POST with no JSON body is rejected 400 | ✓ VERIFIED | `testWithAuthenticatedUser_shouldReturnBadRequest_whenTitleIsSentAsQueryParamWithNoBody` posts `.param("title", title)` with no body/content-type and asserts `status().isBadRequest()`. The old workaround test and its explanatory comment are gone — confirmed by reading the full `AddSubtaskByTaskId` nested class. |
| 3 | AuthorizationGatingTest, InjectionAttemptTest, SubtaskControllerTest, SubtaskLockingTest, BoardFullReadTest stay green | ✓ VERIFIED | All five classes exist at the paths the plan names them (`src/test/java/com/vrudenko/kanban_board/security/{AuthorizationGatingTest,InjectionAttemptTest}.java`, `src/test/java/com/vrudenko/kanban_board/{SubtaskLockingTest,BoardFullReadTest}.java`, `controller/SubtaskControllerTest.java`) and are named verbatim in Task 2's `<verify>` block and the plan's top-level `<verification>` section. Independently re-ran `./gradlew test --tests TaskControllerTest --tests AuthorizationGatingTest` → `BUILD SUCCESSFUL in 6s`. User independently ran the full `./gradlew spotlessCheck test --rerun` post-merge → `BUILD SUCCESSFUL in 4m43s`, which supersedes a from-scratch re-run of all five for this report. |
| 4 | Source todo closed; surfaced validation gap filed separately, not silently absorbed | ✓ VERIFIED | `.planning/todos/completed/2026-08-09-fix-subtask-creation-dto-missing-requestbody-binds-as-mode.md` exists with a `resolved: 2026-08-11` frontmatter field and a `## Resolution` section. `.planning/todos/pending/2026-08-09-...` no longer exists (moved via presumed `git mv`, confirmed by `git log --follow` history continuity implied by identical filename). New todo `.planning/todos/pending/2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md` exists, well-formed (frontmatter: `created`, `title`, `area: backend`, `severity: minor`, `files:` listing both `SaveSubtaskRequestDTO.java` and `SubtaskTitle.java` as the plan specified). |

**Score:** 4/4 frontmatter-declared truths verified.

### Point 3 — predicted vs. actual failure mode (explicit confirmation requested)

The **plan's own prediction** (inherited from the source todo): a JSON-bodied POST would trip `@SubtaskTitle` validation and return **400**.

**What was actually observed and documented:** the resolution note in the closed todo states the pre-fix status was **409 Conflict**, not 400 — because `@SubtaskTitle` composes only `@Size`, which passes on `null`, so the unbound `title` sailed through Bean Validation and only failed downstream at Postgres's `subtasks.title NOT NULL` constraint, surfacing as `DataIntegrityViolationException`. I independently confirmed this mapping is correct: `GlobalExceptionHandler.handleDataIntegrityViolation` (line 151-158) maps `DataIntegrityViolationException` to `HttpStatus.CONFLICT` (409) via `ProblemDetail`. The closed todo's Resolution section (lines 57-66) documents this correction explicitly and cross-references the new todo — it does **not** repeat the original wrong prediction as fact. The newly-filed todo also records the same 409 finding with the same reasoning, consistently.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/.../TaskController.java` | `@RequestBody` added to `dto` param, nothing else changed | ✓ VERIFIED | `git diff --stat 353e975..d1d83ea -- src/main/` shows exactly `TaskController.java | 2 +-, 1 file changed, 1 insertion(+), 1 deletion(-)`. No other `src/main` file touched — `SaveSubtaskRequestDTO.java` has zero diff (confirmed via `git diff 353e975..d1d83ea -- .../SaveSubtaskRequestDTO.java`, empty output). |
| `src/test/java/.../TaskControllerTest.java` | Two new JSON-driven tests replace the workaround | ✓ VERIFIED | Read in full; both tests present, workaround test and its comment removed. |
| `.planning/todos/completed/2026-08-09-....md` | Closed with resolution note correcting the prediction | ✓ VERIFIED | Present, well-formed, corrects 400→409. |
| `.planning/todos/pending/2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md` | New todo for `@NotBlank` gap | ✓ VERIFIED | Present, well-formed, matches plan's specified frontmatter and content requirements. |
| `.planning/STATE.md` | Quick Tasks Completed row + Pending Todos entry (Task 3's own stated deliverable) | ✗ MISSING | `grep -n "260811-me4" .planning/STATE.md` and `grep -n "save-subtask-request-dto-missing-notblank" .planning/STATE.md` both return no matches. `git diff HEAD -- .planning/STATE.md` is empty (no uncommitted change either). Contradicts SUMMARY.md's claim this was done. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `TaskController.addSubtaskByTaskId`'s `dto` param | `MappingJackson2HttpMessageConverter` | `@RequestBody` annotation | ✓ WIRED | Annotation present; Test 1 proves JSON body is actually parsed (title round-trips), Test 2 proves the old `ServletModelAttributeMethodProcessor` path no longer binds (400 on param-only request). |
| `TaskControllerTest.AddSubtaskByTaskId` | the endpoint over MockMvc | direct MockMvc `.perform(post(...))` calls | ✓ WIRED | Confirmed by reading the test file — both tests drive the real endpoint through `mockMvc`, not the service directly. |
| `AuthorizationGatingTest` route table row for this route | still passes with body now genuinely parsed | pre-existing 403 assertion | ✓ WIRED (per independent full-suite run) | Not re-derived from scratch in this session beyond a scoped re-run (`BUILD SUCCESSFUL in 6s` for `TaskControllerTest` + `AuthorizationGatingTest`); full-suite pass already independently confirmed by the user post-merge. |

### Scope-Discipline Check

`SaveSubtaskRequestDTO.java` — **zero changes**, confirmed via `git diff 353e975..d1d83ea -- src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/SaveSubtaskRequestDTO.java` (empty diff). The missing-`@NotBlank` gap was filed as a new todo only, not fixed in this task, exactly as the plan's Approach A/C trade-off decision required.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| TODO-2026-08-09-SUBTASK-REQUESTBODY | 260811-me4-PLAN.md | Fix subtask creation DTO missing `@RequestBody` | ✓ SATISFIED | See Truths 1-2 above. Not present in `.planning/REQUIREMENTS.md` (todo-driven quick task, not roadmap-tracked — no orphan). |

### Anti-Patterns Found

None in the modified production/test files (`TaskController.java`, `TaskControllerTest.java`) — no `TODO`/`FIXME`/`XXX`/`HACK`/`PLACEHOLDER` markers, no empty implementations, no hardcoded stub returns.

One documentation-integrity anti-pattern found outside the source diff: **SUMMARY.md asserts a change that does not exist in the codebase** (STATE.md update, "left uncommitted per orchestrator instruction"), when in fact no such change exists in any form (committed or uncommitted). This is exactly the class of SUMMARY-vs-codebase discrepancy this verification process exists to catch.

### Human Verification Required

None. All checks above were resolvable programmatically.

### Gaps Summary

The core technical fix is fully and correctly delivered: the one-line `@RequestBody` diff matches the plan exactly, both new controller tests exist and correctly exercise JSON-body success and query-param-rejection paths, the blast-radius test classes are named and independently confirmed green, `SaveSubtaskRequestDTO` is untouched, and both todo-file movements (close + new file) are done with an accurate, corrected resolution note (409, not the originally predicted 400).

The one gap is process bookkeeping, not functional: Task 3's plan explicitly required updating `.planning/STATE.md` with a Quick Tasks Completed row and a Pending Todos entry for the new todo, and its own automated verify step (`grep -q '260811-me4' .planning/STATE.md`) does not pass. SUMMARY.md's claim that this was done ("left uncommitted per orchestrator instruction; docs commit handled in Step 8") does not match the repository state — there is no uncommitted STATE.md diff and no committed one. This should be closed with a small follow-up edit to `.planning/STATE.md` (add the Quick Tasks Completed row for `260811-me4` and the Pending Todos entry for the new `@NotBlank` todo) before this quick task is considered fully closed out.

---

_Verified: 2026-08-11T17:05:00Z_
_Verifier: Claude (gsd-verifier)_
