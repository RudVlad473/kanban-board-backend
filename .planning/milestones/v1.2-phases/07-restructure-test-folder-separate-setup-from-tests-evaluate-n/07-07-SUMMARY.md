---
phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n
plan: 07
subsystem: testing
tags: [junit5, gradle, docs, code-style, gsd-todos]

# Dependency graph
requires:
  - phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n
    provides: "Every prior wave's diff (07-01 through 07-06): support/ split, one @Nested merge, 12 tier-downgrade conversions, 2 empty-class deletions"
provides:
  - "Full-repository gate proof: ./gradlew spotlessCheck && ./gradlew clean test green in one JVM lifecycle, 278 tests / 0 failures, matching the pre-phase (Phase 6 close) count exactly — no shrinkage"
  - "Tier accounting reconciled against RESEARCH.md's verdict table: 1 real-HTTP, 9 Kafka, 11 concrete in-process files (representing 13 DOWNGRADE verdicts: 10 standalone + 2 merged + 1 trivial deletion), 4 controller tests unchanged"
  - "docs/CODE_STYLE.md rule 4 extended with both the which-package (service/controller/e2e, by purpose) and which-base-class (three-way support/fixtures/ split) decision rules"
  - "Follow-up todo filed for the E2ETest-suffix/fastTest-filter coupling; source todo closed with its 23/45 premise corrected against the real 22/48"
affects: []

# Actuals (#2632)
actuals:
  tokens: 9700
  tasks: 4
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Which-package (purpose) vs. which-base-class (mechanism) framed as two independent, jointly-read CODE_STYLE.md decision rules rather than one conflated rule"

key-files:
  created:
    - .planning/todos/pending/2026-08-09-decide-e2etest-suffix-vs-fasttest-filter-coupling.md
    - .planning/todos/completed/2026-08-08-restructure-test-folder-separate-setup-from-tests-evaluate-n.md
  modified:
    - .planning/codebase/STRUCTURE.md
    - .planning/codebase/TESTING.md
    - .planning/codebase/CONCERNS.md
    - docs/CODE_STYLE.md

key-decisions:
  - "Task 4's which-package decision rule was folded directly into rule 4 (as a preceding paragraph, before the which-base-class content) rather than added as a new numbered rule at the end of the file — the plan's own text explicitly offered this as 'author's discretion... whichever reads more naturally,' and splitting one coherent rule-4 rewrite across two separate commits (Task 2, then Task 4) would have committed 'half a rule' first, which is harder to review in isolation than one coherent edit. Both tasks' required content therefore landed in Task 2's commit (5c05992); Task 4 made no additional CODE_STYLE.md diff."
  - "Confirmed by reading BoardServiceTest/TaskServiceTest against BoardControllerTest/TaskControllerTest (Task 4's required read_first) that the existing service tests already match the new purpose framing rather than being redundant with their controller-test siblings — see Task 4 Finding below."

requirements-completed: [TEST-01, TEST-02, TEST-03, TEST-04]

coverage:
  - id: D1
    description: "Full-repository gate (spotlessCheck + clean test) proves the whole phase composes in one JVM lifecycle; no production-code or build.gradle change; suite did not shrink"
    requirement: "TEST-03"
    verification:
      - kind: unit
        ref: "./gradlew spotlessCheck -- BUILD SUCCESSFUL in 2s"
        status: pass
      - kind: integration
        ref: "./gradlew clean test -- BUILD SUCCESSFUL in 5m 32s, 278 tests, 0 failures, 0 errors (build/reports/tests/test/index.html)"
        status: pass
      - kind: other
        ref: "git diff --quiet -- src/main/ build.gradle -- clean, zero production-code/build-script change"
        status: pass
    human_judgment: false
  - id: D2
    description: "Every git-tracked document describing the test-tree fixture-base layout matches the support/ split; the two deleted empty classes are no longer cited as existing outside CONCERNS.md's now-resolved finding"
    requirement: "TEST-01"
    verification:
      - kind: other
        ref: "test -z \"$(grep -rln 'e2e/board/BoardE2ETest\\|service/SubtaskServiceTest' .planning/codebase/ docs/ --include='*.md' | grep -v CONCERNS.md)\" -- PASS"
        status: pass
    human_judgment: false
  - id: D3
    description: "docs/CODE_STYLE.md states a purpose-based service/controller/e2e decision rule (which package) and the three-way base-class decision rule (which support/fixtures/ base), each with worked examples, read together"
    requirement: "TEST-03"
    verification:
      - kind: other
        ref: "docs/CODE_STYLE.md rule 4, commit 5c05992 -- manually re-read after edit, contains one worked example per tier (TaskServiceTest ownership-denial edge case, TaskControllerTest stale-version-409 contract check, BoardCreationE2ETest.ConcurrentCreate + ActivityLogIdempotencyE2ETest cross-service/concurrency flows)"
        status: pass
    human_judgment: false
  - id: D4
    description: "E2ETest-suffix/fastTest-filter coupling filed as an explicit follow-up decision; source todo closed with its premise corrected"
    requirement: "TEST-04"
    verification:
      - kind: other
        ref: "test -f .planning/todos/pending/2026-08-09-decide-e2etest-suffix-vs-fasttest-filter-coupling.md && test ! -f .planning/todos/pending/2026-08-08-restructure-test-folder-separate-setup-from-tests-evaluate-n.md -- PASS"
        status: pass
    human_judgment: false

# Metrics
duration: ~55min
completed: 2026-08-09
status: complete
---

# Phase 7 Plan 7: Full-Repository Gate, Doc Reconciliation, and Todo Closure Summary

**Closed Phase 7 with a green full-repository gate (278 tests, 0 failures, no shrinkage from the pre-phase count), reconciled every git-tracked doc that described the pre-Phase-7 test tree, extended `docs/CODE_STYLE.md` rule 4 with both a which-package and a which-base-class decision rule, and filed the one genuine follow-up decision (E2ETest suffix vs. `fastTest` filter coupling) this phase surfaced.**

## Performance

- **Duration:** ~55 min (approximate — no explicit start timestamp was captured at session start; anchored to the background `clean test` run's own log line at 2026-08-09T13:42:29Z through the final commit at 2026-08-09T16:05:08+02:00 / 14:05:08Z)
- **Started:** ~2026-08-09T13:15:00Z (approx, first file reads)
- **Completed:** 2026-08-09T14:05:08Z
- **Tasks:** 4 (2 commits — Task 4's content landed in Task 2's commit, see Deviations)
- **Files modified:** 7 (4 modified, 2 created, 1 deleted)

## Accomplishments

- **Task 1 — full-repository gate:** `./gradlew spotlessCheck` green (2s). `./gradlew clean test` green: **BUILD SUCCESSFUL in 5m 32s**, **278 tests, 0 failures, 0 errors** per `build/reports/tests/test/index.html` and a from-scratch aggregation of all 140 `build/test-results/test/*.xml` files (278 unique `<testcase>` entries, zero duplicates). `git diff --quiet -- src/main/ build.gradle` confirmed clean — zero production-code or build-script changes anywhere across the whole phase.
- **Suite did not shrink, verified two ways:** (1) the JUnit XML test count is exactly 278, identical to the figure `06-07-SUMMARY.md` recorded at Phase 6's close (`Full suite green at 278 tests`) — this is the true pre-Phase-7 baseline, not the older 210-test/Phase-04.2 figure the plan's own text cited as a duration reference point. (2) A line-level count of `^\s*@Test\b`-annotated methods in the git tree at the commit immediately before 07-01 started (`e19978d5`) versus the current tree gives **277 in both cases**, an exact match — confirming every merge and downgrade preserved every test method and the two deleted classes held none, exactly as 07-01/07-02's summaries claimed.
- **Duration delta reported as an observation, not a failure:** the plan's cited baseline (291-295s for 210 tests, Phase 04.2) predates Phase 6's new feature work; this run's 332s (5m32s) for 278 tests is ~13-14% slower in absolute wall-clock but **~14% faster per test** (1.194s/test now vs. 1.386s/test then) — consistent with twelve classes no longer starting an embedded servlet container, even though the suite grew substantially in the interim for unrelated reasons (Phase 6 feature work).
- **Tier accounting reconciled against RESEARCH.md's verdict table** (see table below) — matches the expected one-KEEP-real-HTTP / nine-KEEP-Kafka split exactly; the in-process tier's concrete file count (11) is explained precisely by the 13 DOWNGRADE verdicts (10 standalone conversions + 2 merged into `AuthenticationE2ETest` + 1 trivial deletion).
- **Task 2 — doc reconciliation:** `STRUCTURE.md` and `TESTING.md` directory trees and prose updated to show the `support/{containers,fixtures,listeners}` split instead of the pre-Phase-7 flat/interspersed fixture-base layout; `CONCERNS.md`'s empty-`BoardE2ETest` finding marked **RESOLVED** (pointer to 07-01, not deleted, per the plan's explicit instruction to preserve finding history); `docs/CODE_STYLE.md` rule 4 extended (see Task 4 below — folded into the same commit); `docs/LOCAL_DEV.md` checked and found to need **no edit** — every fixture-class reference there names the class only, never a path.
- **Task 3 — todo filing and closure:** new pending todo `2026-08-09-decide-e2etest-suffix-vs-fasttest-filter-coupling.md` created with all four required evidence points (the class-name-pattern filter, the pre-commit coupling, the stale "Testcontainers-backed" build-script comment, the naming/filter joint-decision requirement) and three framed options; also folds in a small stale-comment finding (`UserAuthenticationProvider.java:38` still names the deleted `SessionPersistenceE2ETest` class) per the orchestrator's additional context, rather than filing it as a fourth separate todo. Source todo moved to `completed/` with a `## Resolution` section: what shipped vs. what was asked (three-way split not the flatter shape sketched, one merge executed with three deferred, 13 DOWNGRADE verdicts precisely accounted), and its own `23 E2ETest-suffixed / 45 test files` premise corrected against `07-RESEARCH.md`'s direct enumeration (`22 / 48` at research time, 2026-08-09).
- **Task 4 — test-tier decision guide:** verified against the actual codebase (not assumed) that `BoardServiceTest`/`TaskServiceTest` already match the new purpose-based framing rather than duplicating their controller-test siblings — see Finding below. The decision rule itself was written into `docs/CODE_STYLE.md` rule 4 as a preceding paragraph (folded in per the plan's author's-discretion clause), with one worked example per tier, drawn from real classes in the post-phase tree.

## Tier Accounting (Task 1)

| Tier | Base class | Concrete files today | Expected (RESEARCH.md / plan) |
|------|-----------|----------------------|-------------------------------|
| Real-HTTP (RestAssured, `RANDOM_PORT`) | `support/fixtures/AbstractAppE2ETest` | 1 (`BoardCreationE2ETest`) | 1 |
| Kafka/Schema-Registry-dependent | `support/containers/AbstractKafkaContainerTest` | 9 (`activitylog/*`) | 9 |
| In-process MockMvc | `support/fixtures/AbstractAppMockMvcTest` | 11 | "thirteen" per the plan's `<verification>` wording — reconciled below |
| Plain service/controller fixture | `support/fixtures/AbstractAppTest` | 13 (4 controller tests + 9 others: service/event/root) | 4 controller tests explicitly called out as unmigrated |

**Reconciling 11 concrete files against the plan's "thirteen" figure:** the plan's own verification text ("thirteen on the in-process MockMvc base") counts *conversions*, not *files on disk*. RESEARCH.md's verdict table marks **13 rows DOWNGRADE** (rows 10-22 excluding row 9's KEEP). Of those 13: **10** became standalone in-process files (`BoardFullReadE2ETest`, `ColumnDeletionE2ETest`, `ColumnOrderingE2ETest`, `SubtaskLockingE2ETest`, `TaskOrderingE2ETest`, `ThemePersistenceE2ETest`, `ActivityReadE2ETest`, `ColumnLockingE2ETest`, `TaskLockingE2ETest`, `TaskMoveE2ETest`); **2** (`SessionPersistenceE2ETest`, `UserPersistenceE2ETest`) were downgraded directly into the merged `security/AuthenticationE2ETest.java` in plan 07-02, alongside the pre-existing (non-`E2ETest`-suffixed) `AuthenticationControllerTest`; **1** (`e2e/board/BoardE2ETest.java`, 0 `@Test` methods) was deleted as trivial rather than converted. `10 + 1 merged file = 11` concrete files on disk — the arithmetic is consistent, just counting a different unit (conversions vs. files). Verified directly via `grep -rl "extends AbstractAppMockMvcTest\b" src/test/java` (11 hits, exact list above) and `grep -rl "extends AbstractAppE2ETest\b"`/`extends AbstractKafkaContainerTest\b` (1 and 9 hits respectively).

## Assumption A2 Verdict (carried forward from 07-02)

**Confirmed true, empirically, twice.** RESEARCH.md flagged as `[ASSUMED]` whether Spring Boot auto-configures the full `springSecurityFilterChain` (including `SessionAuthenticationStrategy`) under `@AutoConfigureMockMvc` with `spring-security-test` on the classpath. Plan 07-01 proved the general case via `ColumnLockingE2ETest`. Plan 07-02 proved it specifically for the two security-control-critical classes (concurrent-session ceiling, session-fixation) by falsification: neutralizing `sessionAuthenticationStrategy.onAuthentication(...)` made both groups go red (`expected: 401 but was: 200` for the ceiling; a null rotated cookie for fixation), and restoring the call site made both green again, with `git status --porcelain src/main/` empty throughout. This plan's own full-suite run reconfirms both groups still pass today. No replan to the 11/11 KEEP/DOWNGRADE split was ever triggered.

## Task 4 Finding: do the existing service tests already match the new framing?

**Yes — confirmed, not assumed, by reading `BoardServiceTest`, `TaskServiceTest`, `BoardControllerTest`, and `TaskControllerTest` in full (Task 4's required `read_first`).** `BoardServiceTest`/`TaskServiceTest` already lean toward exactly the framing this rule states: ownership-denial branches (`testDeleteById_shouldNotDelete_whenBoardDoesntBelongToUser`, `shouldThrow_whenUserDoesntOwnTheTask`), not-found branches, a uniqueness-conflict edge case (`UpdateByIdUniquenessTest`), and query-count invariants (`FindFullByIdQueryCountTest`, `MoveToColumnQueryCountTest`) — none of which any controller test in this codebase separately re-proves; `BoardControllerTest`/`TaskControllerTest` contain **zero** ownership-denial or uniqueness-conflict tests of their own. Where a scenario name overlaps (e.g. both a service test and a controller test exercise "stale version" or "task doesn't exist"), the two tiers assert genuinely different things — the service test asserts an exception type/state, the controller test asserts an HTTP status/JSON shape — which is intentional layering (proving the business rule fires, then proving the HTTP wiring maps it correctly), not redundancy. **No new todo filed** — the finding is that the existing tests already match the framing; there is nothing here to fix.

## Task Commits

Each task was committed atomically, except Task 4 which had no additional diff (see Deviations):

1. **Task 1: Run the full-repository gate and reconcile the phase's tier accounting** - no commit (verification only, `(no files modified — verification only)` per its own `<files>`); results recorded above and in this SUMMARY
2. **Task 2: Reconcile every document that describes the test tree** (also delivers Task 4's rule-4 content) - `5c05992` (docs)
3. **Task 3: File the suffix/filter follow-up decision and close the source todo** - `60168fa` (docs)
4. **Task 4: Write the service/controller/e2e test-tier decision guide** - no additional commit; content delivered inside `5c05992` (see Deviations)

**Plan metadata:** (pending — final docs commit follows this SUMMARY, per the orchestrator's instruction not to touch ROADMAP.md/STATE.md in this plan)

## Files Created/Modified

- `.planning/codebase/STRUCTURE.md` - directory tree and prose updated to the `support/` three-way split; deleted-class/renamed-class references fixed
- `.planning/codebase/TESTING.md` - directory tree, fixture-location prose, and the "E2E Tests" section rewritten to describe the real tier split (1 real-socket / 9 Kafka / rest in-process) instead of implying every `*E2ETest` class uses RestAssured/`RANDOM_PORT`
- `.planning/codebase/CONCERNS.md` - empty-`BoardE2ETest` finding marked RESOLVED with a pointer to 07-01
- `docs/CODE_STYLE.md` - rule 4 extended with the which-package (purpose) decision rule and the which-base-class (three-way `support/fixtures/`) decision rule, plus a note that MockMvc does not apply the servlet context path
- `.planning/todos/pending/2026-08-09-decide-e2etest-suffix-vs-fasttest-filter-coupling.md` - new (created)
- `.planning/todos/completed/2026-08-08-restructure-test-folder-separate-setup-from-tests-evaluate-n.md` - new (created, closed source todo)
- `.planning/todos/pending/2026-08-08-restructure-test-folder-separate-setup-from-tests-evaluate-n.md` - deleted (moved to completed/)

## Decisions Made

- Folded Task 4's which-package decision rule into rule 4 directly (as a preceding paragraph, before the which-base-class content), rather than appending it as a new numbered rule at the file's end — the plan explicitly offered this as author's discretion "if that reads more naturally," and splitting one coherent rewrite of rule 4 across two separate task commits would have produced a confusing intermediate state (half a decision rule, reviewed in isolation) rather than reducing risk. Both tasks' content is therefore in commit `5c05992`.
- Used the JUnit XML aggregation (278 tests, matching Phase 6's own recorded close-of-phase figure exactly) as the authoritative "did the suite shrink" signal, rather than the plan's own cited 210-test/Phase-04.2 baseline — that older figure predates Phase 6's substantial new feature work and would produce a misleading, apples-to-oranges comparison. Reported both: the true pre/post-Phase-7 comparison (278 = 278, exact) and the plan's requested duration-only comparison against the 291-295s/210-test baseline (as an observation, per the plan's own instruction that a slower run is not a failure condition).

## Deviations from Plan

### Discovered but not auto-fixed (documented instead)

**1. [Not a Rule 1-4 case — scope-honoring restructure] Task 4's content delivered via Task 2's commit, not a separate Task 4 commit**
- **Found during:** Task 4
- **Issue:** the plan's own text for Task 4 offers two placement options for the new decision rule — "immediately after rule 4" (implying a separate edit/commit) or "folded into rule 4 as a preceding paragraph... author's call." Choosing the fold-in option means Task 4 has no independent diff to commit once Task 2 has already landed rule 4's other required changes.
- **Resolution:** delivered both tasks' required rule-4 content in one coherent edit, committed under Task 2 (`5c05992`). Task 4's required verification (`./gradlew spotlessCheck`) and `<done>` criteria (worked example per tier, independence from the base-class rule, both stated together) are satisfied by that same commit's content — confirmed by re-reading the final file. No functional gap; purely a commit-boundary choice explicitly sanctioned by the plan's own text.
- **Files affected:** `docs/CODE_STYLE.md` (already listed under Task 2's commit).

---

**Total deviations:** 1, not a Rule 1-4 fix — a plan-sanctioned commit-boundary choice, no scope change, no functionality gap.
**Impact on plan:** None on correctness or completeness; Task 4's `<done>` criteria are fully met, just inside Task 2's commit rather than a fourth commit.

## Issues Encountered

- The pre-commit hook's `fastTest` run hit the same class of Gradle-daemon contention documented throughout this phase's earlier plans (`docs/SESSION_LESSONS.md`): one commit attempt exceeded the shell's 2-minute default timeout mid-hook (retried with an extended timeout), and a second attempt failed with `Unable to delete directory '...\build\test-results\fastTest\binary'` — a stray daemon holding a file lock, resolved with `./gradlew --stop` before retrying. No code change required either time; this run was sequential (Wave 3, no sibling worktrees), so unlike Waves 1-2 the contention here was self-inflicted stray daemons from this same session's own long `clean test` run, not cross-agent interference.
- `EventIdGeneratorTest.GenerateTest.shouldReturnDistinctValues_whenCalledManyTimesRapidly()` surfaced once as a `fastTest` failure during a hook run — the same pre-existing, already-documented probabilistic birthday-paradox flake noted in `07-04-SUMMARY.md` (unrelated to this plan's doc-only changes). Out of scope per the deviation rules' scope boundary; a bare retry passed and the class was not touched by this plan.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 7 is fully closed: all 7 plans executed, full suite green (278 tests, 0 failures), zero net production-code change across the entire phase, every fixture-location claim in git-tracked docs now matches the tree, and the one genuine open decision (E2ETest suffix vs. `fastTest` filter) is filed with its evidence rather than left implicit in a build-script comment.
- The orchestrator should update `ROADMAP.md`/`STATE.md` after this SUMMARY and phase verification, per this plan's explicit instruction not to touch those files.
- Remaining open item, not this plan's to resolve: `.planning/todos/pending/2026-08-09-decide-e2etest-suffix-vs-fasttest-filter-coupling.md` — a human decision on whether to rename the 12 downgraded classes and, if so, how to retarget `fastTest`'s exclusion filter.

---
*Phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n*
*Completed: 2026-08-09*

## Self-Check: PASSED

- All 7 claimed files confirmed present on disk (4 modified docs, 2 new todo files, this SUMMARY.md)
- Confirmed absent: `.planning/todos/pending/2026-08-08-restructure-test-folder-separate-setup-from-tests-evaluate-n.md` (moved to completed/)
- Both task commit hashes (`5c05992`, `60168fa`) confirmed present in `git log`
