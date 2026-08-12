# Quick Task 260812-eg8: Investigate test coverage gap tracking (JaCoCo) and fix test file placement drift into the package root - Context

**Gathered:** 2026-08-12
**Status:** Ready for planning

<domain>
## Task Boundary

Investigate test coverage gap tracking (JaCoCo) and fix test file placement drift into the package root (source todo: `.planning/todos/pending/2026-08-11-investigate-test-coverage-gap-tracking-and-fix-test-file-p.md`).

Two related test-hygiene gaps bundled together: (1) no reliable way today to answer "is this src/main file tested, and how completely?" and (2) 11 test files sit directly in the `com.vrudenko.kanban_board` package root instead of Phase 7's established subpackage structure.

</domain>

<decisions>
## Implementation Decisions

### JaCoCo gating approach
- **Measure first, then pick a rung** — do not guess a threshold or gate level upfront. Add JaCoCo, run it report-only, look at the real coverage numbers/gaps across the codebase, then decide the enforcement level (hard gate on `./gradlew test`, report-only permanently, or something in between) as an evidence-based follow-up decision within this same task.
- Explicitly mirrors this repo's own precedent: the ErrorProne rollout (`.planning/quick/260802-qr8-*`, `260803-v23-*`) measured actual finding counts before choosing a severity rung rather than picking one speculatively. The source todo itself points at this precedent.
- Consider pairing with a lightweight ArchUnit rule for the *zero-coverage* case (no test class exists at all) as a cheaper, coarser complement to JaCoCo's percentage-based gaps — needs an exemption list for classes legitimately untested in isolation (e.g. simple DTOs only exercised via controller tests). This was raised in the source todo; not locked as required, left to investigation.

### Claude's Discretion
- **Fold-vs-relocate for the 11 stray test files** — not discussed with the user (deselected from gray areas — implicitly "you decide"). Investigate case by case per the source todo's own framing: for `TaskOrderingTest` specifically, and check the same duplicate-vs-relocate question for `ColumnOrderingTest`, `ColumnDeletionTest`, `SubtaskLockingTest` against their sibling `*ControllerTest`/`*E2ETest` classes. Files with no sibling duplicate should simply be relocated into the matching subpackage (`e2e/`, `controller/`, `service/`, `security/`, `activitylog/`, `dto/`, `event/`, `handler/`, `config/`, `architecture/`, `support/`) per Phase 7's convention.
- **Test file placement documentation** — where/how to write the placement rule (docs/CODE_STYLE.md addition, following the numbering convention already established through rule 12) so future GSD executors read it before creating a new test file in the wrong location. Left to planner/executor judgment on exact wording and placement, consistent with this repo's existing CODE_STYLE.md rule format.

</decisions>

<specifics>
## Specific Ideas

No specific requirements beyond the source todo's own text — open to standard approaches for JaCoCo configuration (task naming, report format) and file relocation mechanics.

</specifics>

<canonical_refs>
## Canonical References

- Source todo: `.planning/todos/pending/2026-08-11-investigate-test-coverage-gap-tracking-and-fix-test-file-p.md`
- Precedent for measure-first gating: `.planning/quick/260802-qr8-*` (ErrorProne main-source rollout), `.planning/quick/260803-v23-*` (ErrorProne test-source rollout)
- Phase 7 test restructure (established the subpackage convention being violated): `.planning/phases/07-restructure-test-folder-separate-setup-from-tests-evaluate-n/`
- `docs/CODE_STYLE.md` rules 4/5 (which-package / which-base-class decision rules for tests) and rule 12 (most recent addition, for format precedent)

</canonical_refs>
