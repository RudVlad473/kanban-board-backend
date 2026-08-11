---
created: 2026-08-11T10:48:04.499Z
title: Revisit Java code comment and JavaDoc verbosity policy
area: docs
severity: minor
files:
  - docs/CODE_STYLE.md
  - .claude/CLAUDE.md
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

## Solution

TBD — needs a review pass across existing JavaDoc-heavy methods (start with
`TaskService.deleteAllByColumn()`, the doc's own cited exemplar, and any other methods flagged
by `docs/CODE_STYLE.md`'s "Extensive multi-line JavaDoc" note) to decide case-by-case whether to
compress, split, or keep each one. Once a policy is settled, update `docs/CODE_STYLE.md`'s
Comments section (and confirm `.claude/CLAUDE.md`'s generated `## Comments` section picks up the
change) so the decision is durable and followed by future sessions, not just applied ad hoc to
the methods reviewed in this pass.
