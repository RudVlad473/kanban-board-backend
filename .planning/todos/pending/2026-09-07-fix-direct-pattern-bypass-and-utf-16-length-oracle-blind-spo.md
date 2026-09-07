---
created: 2026-09-07T16:47:32.424Z
title: "Fix direct-@Pattern bypass and UTF-16 length-oracle blind spot in constraint customizer"
area: api
severity: major
files:
  - src/main/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizer.java:156-164
  - src/main/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizer.java:494
  - src/test/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizerTest.java:174-176
  - src/test/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizerTest.java:697
---

## Problem

Two structural gaps found by an arm-A (single-pass) fan-out review of quick task 260904-ss1's
fix commit (`3156968`, "close the four review defects in the constraint customizer"). Both
`CONFIRMED-BY-RUNNING` in `armA-claude.md`; N2 independently re-verified in-session by reading
the cited lines directly in this repo. Sibling to (but distinct from) the existing todo
`2026-09-05-ecmaequivalentof-does-not-fail-closed-on-java-only-regex-constructs.md`, which covers
`ecmaEquivalentOf`'s own translation gaps on the composed-annotation path — these two are about a
different code path and the test suite itself.

**N1 — `seedFrom` never routes a directly-declared `@Pattern` through `ecmaEquivalentOf`, and
`reassertOn` cannot repair it.** `ComposedConstraintPropertyCustomizer.java:156-164` and `:494`.
D1 (260904-ss1) fixed the *composed* custom-annotation path (`@BoardName`, `@ColumnColor`, etc.)
to translate `regexp()`/`flags()` through `ecmaEquivalentOf` before publishing. A DIRECTLY
declared `@Pattern` on a DTO field never enters that path at all — `seedFrom` republishes its
`regexp()` raw, with `flags()` dropped, byte-identical to the pre-D1 defect. D2's `reassertOn`
(the "phase 2" pass meant to catch exactly this class of gap) cannot repair it either: for
`pattern` it is set-if-absent, not tighten — and "set if absent" is not the tightening operation
needed here (conjunction is, which `applyPattern` already implements for the composed path).
Proven by adding a direct `@Pattern` to a DTO field and reading the live generated document.
**Currently latent** — `rg -n '@Pattern' src/main/java/com/vrudenko/kanban_board/dto/` outside
`dto/annotation/` returns nothing today — but the cost of a future direct `@Pattern` being added
is invisible: the suite stays green while the document goes stricter than the real validator (the
exact class of defect this whole bean exists to prevent).

**N2 — the equivalence test suite's own oracle counts UTF-16 units, so it structurally cannot
catch the live GT1 defect (minLength published in UTF-16 units on 7 properties).**
`ComposedConstraintPropertyCustomizerTest.java:174-176`, `valueSatisfiesPublishedConstraints`:
```java
if (minLength != null && value.length() < minLength) { return false; }
if (maxLength != null && value.length() > maxLength) { return false; }
```
`value.length()` is Java's UTF-16 code-unit count — the *enforcer's* unit — not JSON Schema's
(Unicode code points). Since the oracle models the published document using the same counting
rule as what it's compared against, the two can never disagree on length by construction. That is
exactly why the 519-test suite stays green while GT1 is live. The codebase already understands
this hazard for the **maxLength** direction — `shouldAcceptAnAstralHeavyValueThatViolatesTheRealSizeMax_perD3Decision`
(`:697`) deliberately bypasses this helper via `codePointCount()`, with a decision-record comment
explaining why. No equivalent test exists for the **minLength** direction, so that oracle blind
spot is genuinely uncovered by anything in the suite today.

## Solution

- **N1:** route `seedFrom`'s direct-`@Pattern` handling through the same `ecmaEquivalentOf`
  translation the composed path uses, and make `reassertOn` a real tighten (conjunction) for
  `pattern` rather than set-if-absent — matching what D1/D2 already do for the composed path.
  Add a test with a directly-declared `@Pattern` on a scratch/real DTO field asserting the
  published pattern is the ECMA-translated, conjoined form, not raw passthrough.
- **N2:** rewrite `valueSatisfiesPublishedConstraints`'s length checks to use
  `value.codePointCount(0, value.length())` instead of `value.length()`, for both minLength and
  maxLength. Add a minLength-direction astral counter-test mirroring the existing maxLength one at
  `:697`, so the oracle can no longer structurally agree with a broken document in either
  direction. Confirm the existing suite's green result doesn't silently flip to red for reasons
  unrelated to the fix (i.e. this rewrite should only change verdicts for astral-heavy values).
