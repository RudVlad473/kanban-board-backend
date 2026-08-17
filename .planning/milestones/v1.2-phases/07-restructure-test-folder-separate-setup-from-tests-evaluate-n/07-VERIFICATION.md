---
phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n
verified: 2026-08-09T14:16:27Z
status: passed
score: 9/9 must-haves verified
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: none
  previous_score: n/a
  gaps_closed: []
  gaps_remaining: []
  regressions: []
---

# Phase 7: Restructure test folder Verification Report

**Phase Goal:** Execute (not merely evaluate) three test-suite organization improvements: relocate
all five shared setup/fixture infrastructure files into a three-way-split `support/` package; merge
the three closely-related authentication/session test classes into one `@Nested`-grouped file; and
drop the 13 `E2ETest`-suffixed classes that need neither a real socket nor Kafka to the cheaper
in-process `@SpringBootTest` + MockMvc tier, keeping the 9 that genuinely earn the expensive tier.
Two empty test classes deleted as a byproduct. Zero production-code changes.

**Verified:** 2026-08-09T14:16:27Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (checklist items, each independently re-verified against the live tree/build, not SUMMARY claims)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | All 5 shared fixture files live under `support/{containers,fixtures,listeners}/`, plus `AbstractAppMockMvcTest` exists; nothing remains at root package or in `activitylog/` for these | ✓ VERIFIED | `find src/test/java/.../support -type f` shows exactly `support/containers/{AbstractPostgresContainerTest,AbstractKafkaContainerTest}.java`, `support/fixtures/{AbstractAppTest,AbstractAppE2ETest,AbstractAppMockMvcTest}.java`, `support/listeners/RecordingActivityEventListener.java`. Root-package listing and `activitylog/` listing show none of these 5 names remaining there. |
| 2 | Auth/session trio merged into `security/AuthenticationE2ETest.java` with `@Nested` groups; the three source files no longer exist | ✓ VERIFIED | `security/` directory contains only `AuthenticationE2ETest.java`. `find` for `AuthenticationControllerTest.java`, `SessionPersistenceE2ETest.java`, `UserPersistenceE2ETest.java` anywhere under `src/test/java` returns nothing. |
| 3 | Exactly 13 DOWNGRADE-verdict classes converted tiers: 11 concrete files extending `AbstractAppMockMvcTest` (10 standalone + 1 merged `AuthenticationE2ETest` representing 2 verdicts), 1 trivial deletion; 9 KEEP Kafka classes + `BoardCreationE2ETest` untouched | ✓ VERIFIED | `grep -rl "extends AbstractAppMockMvcTest" src/test/java` returns exactly 11 files matching RESEARCH.md's verdict-table rows 10,11,12,13,14,15,16,18,19,20 plus the merged `AuthenticationE2ETest` (rows 21-22). `grep -rl "extends AbstractKafkaContainerTest"` returns exactly the 9 `activitylog/*E2ETest.java` files (verdict rows 1-8 + `HistoricalActivityEventReconstructorTest`). `git diff e19978d5 HEAD` on 3 sampled Kafka-tier files and `BoardCreationE2ETest.java` shows only a single added import line each (`support.containers`/`support.fixtures` path) — no behavioral change. `BoardCreationE2ETest` still `extends AbstractAppE2ETest`. |
| 4 | Two empty test files (`e2e/board/BoardE2ETest.java`, `service/SubtaskServiceTest.java`) are actually deleted | ✓ VERIFIED | `find` for both filenames anywhere under `src/test/java` returns nothing. `git diff --name-status e19978d5 HEAD` shows both as `D` (deleted). |
| 5 | Zero production-code changes anywhere in the phase | ✓ VERIFIED | `git diff --stat e19978d5 HEAD -- src/main/` and `-- build.gradle` both return empty output (`e19978d5` = the commit immediately before 07-01-PLAN.md was created, i.e. the pre-phase baseline). |
| 6 | Full test suite passes: `./gradlew spotlessCheck && ./gradlew test` | ✓ VERIFIED | Ran both myself, not from SUMMARY claims. `./gradlew spotlessCheck` → `BUILD SUCCESSFUL in 1s`. `./gradlew clean test` → `BUILD SUCCESSFUL in 5m 30s`; `build/reports/tests/test/index.html` shows **278 tests, 0 failures**; independently confirmed by counting `<testcase` elements across all 140 `build/test-results/test/*.xml` files = 278, with 0 files containing a nonzero `failures=`/`errors=` attribute. |
| 7 | Column/Task/Subtask/Board conditional `@Nested` merges (Candidates 2-4) were explicitly NOT executed — `controller/{Column,Task,Subtask,Board}ControllerTest.java` unchanged/still separate from their sibling downgraded E2E files | ✓ VERIFIED | All 4 controller test classes still declare their own class (`ColumnControllerTest`, `TaskControllerTest`, `SubtaskControllerTest`, `BoardControllerTest`), each still `extends AbstractAppTest` (not merged with any E2E sibling). The 12 downgraded E2E classes remain in their own separate files (e.g. `ColumnDeletionE2ETest.java`, `ColumnOrderingE2ETest.java`, `e2e/column/ColumnLockingE2ETest.java` are 3 files distinct from `controller/ColumnControllerTest.java`). |
| 8 | `docs/CODE_STYLE.md` rule 4 documents both a which-package (purpose) and which-base-class (support/ three-way split) decision rule | ✓ VERIFIED | Read `docs/CODE_STYLE.md` rule 4 in full: first paragraph is the which-package rule (`service/*ServiceTest.java` vs `controller/*ControllerTest.java` vs `*E2ETest`, with worked examples for each), second paragraph is the which-base-class rule (`AbstractAppTest`/`AbstractAppMockMvcTest`/`AbstractAppE2ETest` under `support/fixtures/`, with `AbstractPostgresContainerTest` under `support/containers/` as the shared container ancestor). Both present and clearly distinguished. |
| 9 | Follow-up todo for the E2ETest-suffix/`fastTest`-filter coupling exists in `.planning/todos/pending/`; source todo closed (moved to `.planning/todos/completed/`) | ✓ VERIFIED | `.planning/todos/pending/2026-08-09-decide-e2etest-suffix-vs-fasttest-filter-coupling.md` exists and was read in full — documents the coupling problem, the stale build.gradle comment, the stale `UserAuthenticationProvider.java:38` comment, and 3 framed resolution options. `.planning/todos/completed/2026-08-08-restructure-test-folder-separate-setup-from-tests-evaluate-n.md` exists; the corresponding `pending/` copy is confirmed absent. |

**Score:** 9/9 truths verified (0 present-but-behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `support/containers/AbstractPostgresContainerTest.java` | Relocated container base | ✓ VERIFIED | Present, `git diff --stat` shows rename (R094) from root package |
| `support/containers/AbstractKafkaContainerTest.java` | Relocated container base | ✓ VERIFIED | Present, rename (R092) from `activitylog/` |
| `support/fixtures/AbstractAppTest.java` | Relocated fixture base | ✓ VERIFIED | Present, rename (R098) from root package |
| `support/fixtures/AbstractAppE2ETest.java` | Relocated fixture base | ✓ VERIFIED | Present, rename (R097) from root package; now has exactly one subclass (`BoardCreationE2ETest`) |
| `support/fixtures/AbstractAppMockMvcTest.java` | New in-process fixture base | ✓ VERIFIED | Present, new file (added in 07-01), 11 subclasses |
| `support/listeners/RecordingActivityEventListener.java` | Relocated listener | ✓ VERIFIED | Present, rename (R095) from flat `support/` |
| `security/AuthenticationE2ETest.java` | Merged auth/session file | ✓ VERIFIED | Present, new file (added in 07-02), 11 `@Test` methods across `@Nested` groups |
| `docs/CODE_STYLE.md` rule 4 | Extended decision rule | ✓ VERIFIED | Present, both which-package and which-base-class content confirmed by direct read |
| `.planning/todos/pending/2026-08-09-decide-e2etest-suffix-vs-fasttest-filter-coupling.md` | Follow-up todo | ✓ VERIFIED | Present, full content read |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| 12 downgraded E2E classes | `AbstractAppMockMvcTest.signinCookie()` | Real `POST /signin`/`POST /signup` + cookie relay, never `.with(user())` | ✓ WIRED | Spot-checked `AuthenticationE2ETest`'s falsification proof (07-02-SUMMARY): neutralizing `sessionAuthenticationStrategy.onAuthentication` in production code made `ConcurrentSessionCeiling`/`SessionFixation` go red, restoring made them green — proves the real auth call site is genuinely exercised post-downgrade, not a shortcut. `grep -rn "io.restassured|LocalServerPort" src/test/java/.../security/` returns zero matches, confirming no real-socket residue. |
| 9 Kafka-tier classes + `BoardCreationE2ETest` | `AbstractKafkaContainerTest`/`AbstractAppE2ETest` | `extends` (unchanged base, only import path updated) | ✓ WIRED | Verified via direct `git diff` on 4 sampled files — single added import line each, zero other changes |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full suite compiles and passes, no shrinkage | `./gradlew spotlessCheck && ./gradlew clean test` (run directly by verifier) | `BUILD SUCCESSFUL in 5m 30s`, 278 tests / 0 failures / 0 errors (`build/reports/tests/test/index.html`, cross-checked against 140 raw XML result files) | ✓ PASS |
| Zero production-code diff across the whole phase | `git diff --stat e19978d5 HEAD -- src/main/ build.gradle` | Empty output for both | ✓ PASS |
| Auth-security-control coverage genuinely preserved post-downgrade | Falsification probe (07-02, re-confirmed by reading the SUMMARY's recorded red/green evidence, not re-run live since it requires editing production code) | Both tests went red when the real call site was neutralized, green when restored | ✓ PASS (evidence-based, not re-executed live by this verifier to avoid touching `src/main/` mid-verification) |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| TEST-01 | 07-01, 07-07 | Shared fixture infra under `support/` three-way split | ✓ SATISFIED | Item 1 above |
| TEST-02 | 07-02, 07-07 | Auth/session trio merged into one `@Nested` file, 4 conditional merges deferred | ✓ SATISFIED | Items 2, 7 above |
| TEST-03 | 07-01..07-07 | 13 of 22 `E2ETest` classes downgraded to MockMvc tier, real-auth preserved | ✓ SATISFIED | Items 3, 6 above |
| TEST-04 | 07-01, 07-07 | No zero-`@Test` class remains | ✓ SATISFIED | Item 4 above |

Note: `.planning/REQUIREMENTS.md`'s Traceability table still shows TEST-01..04 as "Pending" and their checkboxes as `[ ]` — this is expected at this stage (that table/checkbox set is conventionally updated at ship/milestone-close time, not at phase verification), not a gap in the phase's own delivery.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `controller/TaskControllerTest.java` | 80 | `// TODO: add tests for cascade deletion...` | ℹ️ Info | Pre-existing (confirmed via `git diff` showing zero change to this line across the phase) — not introduced by Phase 7, file only received an import-path edit |
| `service/OwnershipVerifierServiceTest.java` | 15 | `// TODO: rewrite these repetative tests...` | ℹ️ Info | Pre-existing (same confirmation as above) — not introduced by Phase 7 |

No blocker or warning-level anti-patterns introduced by this phase's diff. No stub implementations, no hardcoded empty returns, no debt markers added.

### Human Verification Required

None. All 9 checklist items and both explicit success-criteria concerns (production-code isolation, full-suite green) were independently verifiable via direct grep/find/diff/build execution.

### Gaps Summary

No gaps found. Every checklist item was independently re-verified against the live codebase and a self-run build (not trusted from SUMMARY.md text):

- File relocation (D-01/TEST-01): confirmed by direct filesystem enumeration.
- Auth/session merge (D-02 Candidate 1/TEST-02): confirmed by file absence/presence plus the falsification evidence recorded in 07-02-SUMMARY.md.
- Tier downgrade count and untouched KEEP set (D-03/TEST-03): confirmed by grepping `extends` clauses and diffing the 9 Kafka files + `BoardCreationE2ETest` to prove only import paths changed.
- Empty-file deletion (TEST-04): confirmed by filesystem absence.
- Zero production-code changes: confirmed by an empty `git diff --stat` against the pre-phase baseline commit across the entire phase (all 7 plans combined), not per-plan.
- Full suite green: confirmed by running `./gradlew spotlessCheck` and `./gradlew clean test` directly in this verification session (278 tests, 0 failures — exact match to the SUMMARY's claimed count, independently cross-checked against raw JUnit XML).
- Conditional merges correctly deferred: confirmed the 4 controller test classes remain standalone and unmerged.
- CODE_STYLE.md documentation: confirmed by direct read of rule 4's full text.
- Todo lifecycle: confirmed by filesystem presence/absence in both `pending/` and `completed/`.

---

*Verified: 2026-08-09T14:16:27Z*
*Verifier: Claude (gsd-verifier)*
