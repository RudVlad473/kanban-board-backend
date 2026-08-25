---
created: 2026-08-11T10:48:04.499Z
title: Revisit Java code comment and JavaDoc verbosity policy
area: docs
severity: minor
files:

  - docs/CODE_STYLE.md
  - .claude/CLAUDE.md
  - build.gradle

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

`docs/CODE_STYLE.md` and the generated `## Comments` section of `.claude/CLAUDE.md` currently
document "extensive multi-line JavaDoc on complex methods" as a deliberate convention (citing
`TaskService.deleteAllByColumn()` as the exemplar), alongside guidance to document performance
implications, persistence-context behavior, and design decisions affecting callers.

Flagged 2026-08-11: it's unclear whether this convention, as currently practiced, is still the
right calibration. Several comments/method-level docs in the codebase have grown into large
walls of text, and it's an open question whether that's genuinely useful (non-obvious WHY,
hidden constraints, subtle invariants) or has drifted into restating WHAT the code does, which
well-named identifiers already convey. Not yet clear whether the fix is to:

- Compress existing verbose comments down to only the non-obvious WHY;
- Split long-form rationale out of inline JavaDoc into a linked doc file (e.g.
  `docs/ARCHITECTURE.md` or a dedicated design-notes file), keeping the inline comment as a
  short pointer;

- Or leave the convention as-is if a review concludes the verbosity is warranted for this
  codebase's specific complexity (JPA/Hibernate persistence-context timing, batch-delete
  ordering, etc. — areas where "what surprised me while writing this" genuinely needs more than
  one line).

**Added 2026-08-16 — a distinct, second failure mode in the same files: GSD provenance baked
into source comments, not just length.** Many comments across `src/main` and especially
`build.gradle` cite GSD-internal artifact IDs directly — quick task numbers (e.g. "quick task
260813-q1i", "260812-eg8"), phase/plan numbers (e.g. "Phase 4 Plan 02", "Phase 04.1"), and epic
names (e.g. "Epic 2") — as the justification or provenance for a design decision, sometimes as
the *only* stated justification. This is a different problem from raw verbosity: even a
short, well-written comment that says "see quick task 260813-q1i" is opaque to anyone (a future
maintainer, an external contributor, a different AI coding tool) who isn't working inside this
specific GSD-tracked `.planning/` history. It also doesn't age well — a `.planning/quick/`
directory can be archived/pruned independently of the source tree the comment lives in
(`docs/SESSION_LESSONS.md`/`gsd-cleanup` already describe phase-directory archival), silently
turning a "see X" comment into a dead reference.

## Solution

TBD — needs a review pass across existing JavaDoc-heavy methods (start with
`TaskService.deleteAllByColumn()`, the doc's own cited exemplar, and any other methods flagged
by `docs/CODE_STYLE.md`'s "Extensive multi-line JavaDoc" note) to decide case-by-case whether to
compress, split, or keep each one. Once a policy is settled, update `docs/CODE_STYLE.md`'s
Comments section (and confirm `.claude/CLAUDE.md`'s generated `## Comments` section picks up the
change) so the decision is durable and followed by future sessions, not just applied ad hoc to
the methods reviewed in this pass.

For the GSD-provenance angle specifically: audit `src/main` and `build.gradle` for comments that
cite a quick-task ID, phase/plan number, or epic name as their *sole* justification, then for
each one either (a) restate the actual technical reasoning in the comment itself so it stands on
its own without the GSD reference, or (b) if the reasoning genuinely doesn't belong in source
(e.g. "chosen 12.2.2 over 13.0.0 for field-exposure reasons" is fine inline; "see quick task
260813-q1i for the full decision ladder" is not) move it to commit messages or a durable doc like
`docs/ARCHITECTURE.md`, leaving the inline comment self-contained. Should land as the same review
pass as the verbosity-calibration work above, not a separate pass over the same files.
