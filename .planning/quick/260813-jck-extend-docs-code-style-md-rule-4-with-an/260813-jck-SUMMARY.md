---
phase: quick-260813-jck
plan: 01
subsystem: testing
tags: [docs, code-style, bean-validation, dto, testing-conventions]

# Dependency graph
requires:
  - phase: quick-260813-h2f
    provides: "SaveSubtaskRequestDTO.title's @NotBlank + @SubtaskTitle pairing, worked example's controller-tier origin"
  - phase: quick-260813-i6r
    provides: "The dto/ tier relocation this rule now documents in writing, and the rule-4-as-authority citation it left unbacked"
provides:
  - "docs/CODE_STYLE.md rule 4's fourth paragraph: which tier a Bean Validation boundary case belongs at (dto/*Test.java vs. one controller representative)"
  - "A qualifier on rule 4's base-class paragraph opener, scoping 'every test class' to tests that need a Spring context"
  - "dto/ added to rule 13's subpackage enumeration"
affects: [testing-conventions, dto-validation, controller-tests]

# Actuals (#2632)
actuals:
  tokens: 1000
  tasks: 1
  commits: 1

# Tech tracking
tech-stack:
  added: []
  patterns: ["Bean Validation boundary matrices belong at the dto/*Test.java validator tier; controller tier keeps at most 1-2 representatives"]

key-files:
  created: []
  modified: [docs/CODE_STYLE.md]

key-decisions:
  - "New content lands as a fourth bolded paragraph inside rule 4 (not a new rule 14) — it answers the same 'which tier' question rule 4's first paragraph already asks"
  - "Rule 4's base-class paragraph opener qualified to 'every test class that needs a Spring context' rather than left as an absolute 'every test class' claim, since the new dto-tier paragraph would otherwise contradict it four lines later"
  - "dto/ added to rule 13's subpackage list; TestPlacementArchTest's .because() string deliberately left unedited (source file, out of scope) and flagged below as a follow-up"

patterns-established:
  - "Bean Validation full boundary matrix (null/blank/whitespace/length/collision) -> dto/*Test.java; controller tier keeps 1-2 malformed-body-to-400 representatives"
  - "Message-collision caution: when @ReportAsSingleViolation composition renders identical text for two constraints, DTO-tier tests assert on the set of triggered constraint annotation types for inputs that trip more than one, not on message text"

requirements-completed: [QUICK-260813-JCK-DTOTIERRULE]

coverage:
  - id: D1
    description: "Rule 4 gains a fourth bolded paragraph naming the dto/*Test.java tier, its mechanics, the split rule, the SaveSubtaskRequestDTO.title worked example (260813-h2f/260813-i6r), and the message-collision caution"
    requirement: QUICK-260813-JCK-DTOTIERRULE
    verification:
      - kind: other
        ref: "grep -c 'Which tier a Bean Validation boundary case belongs at' docs/CODE_STYLE.md == 1; grep -q 260813-i6r; grep -q ReportAsSingleViolation; grep -q buildDefaultValidatorFactory"
        status: pass
    human_judgment: false
  - id: D2
    description: "Rule 4's base-class paragraph opener no longer contradicts the new dto-tier paragraph (scoped to Spring-context tests)"
    requirement: QUICK-260813-JCK-DTOTIERRULE
    verification:
      - kind: other
        ref: "Manual re-read of docs/CODE_STYLE.md lines 116-190 confirming the qualified opener reads coherently alongside the new paragraph"
        status: pass
    human_judgment: false
  - id: D3
    description: "Rule 13's subpackage enumeration includes dto/; file still has exactly 13 rule headings; zero .java files in the diff"
    requirement: QUICK-260813-JCK-DTOTIERRULE
    verification:
      - kind: other
        ref: "grep -c '^### ' docs/CODE_STYLE.md == 13; git status --porcelain | grep -cE '\\.java$' == 0; grep -c dto/ docs/CODE_STYLE.md >= 2"
        status: pass
    human_judgment: false

duration: 12min
completed: 2026-08-13
status: complete
---

# Quick Task 260813-jck: Extend docs/CODE_STYLE.md Rule 4 with a DTO Validation-Tier Paragraph Summary

**Documented `docs/CODE_STYLE.md` rule 4's missing DTO-validator-tier split rule — which Bean Validation boundary cases belong at `dto/*Test.java` vs. one controller representative — closing the gap two prior quick tasks (260813-h2f, 260813-i6r) already acted on but never wrote down.**

## Performance

- **Duration:** ~12 min
- **Tasks:** 1
- **Files modified:** 1 (`docs/CODE_STYLE.md`)

## Accomplishments
- Added rule 4's fourth bolded paragraph — "Which tier a Bean Validation boundary case belongs at — `dto/*Test.java` vs. one representative controller test" — positioned between the existing purpose paragraph and the base-class paragraph, covering the dto-tier mechanics (`jakarta.validation.Validator` via `Validation.buildDefaultValidatorFactory()`, no `@SpringBootTest`, no `support/fixtures/` base, no container), the split rule (full boundary matrix at the DTO tier, 1-2 representatives at the controller tier), the `SaveSubtaskRequestDTO.title` worked example naming quick tasks 260813-h2f and 260813-i6r, and the `@ReportAsSingleViolation` message-collision caution.
- Qualified rule 4's base-class paragraph opener from an absolute "every test class is a `@SpringBootTest`..." to "every test class that needs a Spring context is a `@SpringBootTest`...", so the new dto-tier paragraph (which describes tests using neither) does not create an internal contradiction inside the same rule.
- Added `dto/` to rule 13's subpackage enumeration, which previously omitted it despite three classes (`SubtaskTitleMessageTest`, `SignupRequestDTOTest`, `OptionalNotBlankTest`) already living there.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add rule 4's DTO-tier paragraph, qualify its base-class opener, list `dto/` in rule 13** - `f608942` (docs)

**Plan metadata:** not committed by this executor — orchestrator handles the docs commit (PLAN.md, SUMMARY.md, STATE.md) separately per this task's execution constraints.

## Files Created/Modified
- `docs/CODE_STYLE.md` - Rule 4 grows a fourth bolded paragraph plus a bounded qualifier on the base-class paragraph's opening clause; rule 13's subpackage list gains `dto/`. No other rule touched.

## Decisions Made
- Content lands inside rule 4 as a new paragraph rather than a new rule 14 (D-01) — same tier-selection question the rule's first paragraph already asks, keeps every existing "rule 4" cross-reference in the codebase valid, and avoids fragmenting one decision across three rule numbers (rejected alternative B in the plan's trade-off matrix).
- Base-class paragraph's opening clause scoped to "test class that needs a Spring context" (D-05) rather than rewriting or re-exampling the rest of that paragraph — the minimal qualifier that removes the contradiction without disturbing the paragraph's existing, already-correct content (`AbstractPostgresContainerTest` sentence, `AbstractAppMockMvcTest` context-path sentence, Mockito prohibition, shared-fixtures sentence, `countQueries` sentence all left byte-identical).
- `TestPlacementArchTest`'s `.because()` string, which also omits `dto/`, was deliberately left unedited (D-07) — it is a `.java` source file, out of this task's docs-only scope, and the drift is safe because that ArchUnit rule is a root-package prohibition with no allowlist (its enumeration is advisory prose, not enforcement).

## Deviations from Plan

None - plan executed exactly as written. All three scoped edits (new rule-4 paragraph, base-class opener qualifier, rule-13 `dto/` addition) landed as specified; no architectural, bug-fix, or blocking-issue deviations were needed for a docs-only change.

## Issues Encountered

One tooling hiccup, no content impact: the first `Edit` attempt (combining both rule-4 edits into a single call spanning lines ~118-143) failed with a "string not found" error despite the anchor text matching the file byte-for-byte on re-read. Recovered by re-reading the file, then re-issuing the same edit as two smaller, independently-anchored `Edit` calls (insert-paragraph, then separately qualify the base-class opener) — both succeeded on the first retry with identical content. No malformed or partial edit was ever written to disk.

## Known Follow-ups (not fixed here, per plan D-07)

- `src/test/java/com/vrudenko/kanban_board/architecture/TestPlacementArchTest.java`'s `.because()` message string also omits `dto/` from its subpackage enumeration, now diverging from rule 13's prose (which this task updated). Left unfixed deliberately: it is a `.java` source file (out of this docs-only task's scope) and the divergence is cosmetic only — `TestPlacementArchTest`'s rule is a root-package prohibition with no allowlist, so its message text drives no test behavior. If that rule ever gains an allowlist, this drift stops being cosmetic and should be revisited.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- `docs/CODE_STYLE.md` rule 4 now backs both `TaskControllerTest`'s existing "per docs/CODE_STYLE.md rule 4" pointer comment (line ~396) and quick task 260813-i6r's stated authority for its dto/ tier relocation — both citations are now factually true.
- No blockers for the in-progress Phase 5 infra-migration work; this task touched only `docs/CODE_STYLE.md` and left every other in-progress working-tree change (`.env.prod.example`, `docker-compose.prod.yml`, `.planning/HANDOFF.json`, `05-04-PLAN.md`, `05-05-PLAN.md`, `.continue-here.md`, `openapi.json` deletion) untouched, per this task's explicit constraints.

---
*Phase: quick-260813-jck*
*Completed: 2026-08-13*

## Self-Check: PASSED

- FOUND: `docs/CODE_STYLE.md` (modified, verified via automated grep gates above)
- FOUND: commit `f608942` (`git log --oneline --all | grep f608942`)
- FOUND: `.planning/quick/260813-jck-extend-docs-code-style-md-rule-4-with-an/260813-jck-SUMMARY.md`
