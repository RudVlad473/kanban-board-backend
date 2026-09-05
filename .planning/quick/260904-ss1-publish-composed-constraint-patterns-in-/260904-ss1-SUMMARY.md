---
quick_id: 260904-ss1
status: complete
subsystem: openapi-docs
tags: [springdoc, openapi, bean-validation, composed-annotations, propertycustomizer, globalopenapicustomizer]
dependency-graph:
  requires: []
  provides: [composed-constraint-openapi-publishing]
  affects:
    - ColumnColor
    - BoardName
    - DisplayName
    - Password
    - SaveBoardRequestDTO
    - UpdateBoardRequestDTO
    - SaveColumnRequestDTO
    - SaveTaskRequestDTO
    - UpdateTaskRequestDTO
    - SaveSubtaskRequestDTO
    - UpdateSubtaskRequestDTO
    - SignupRequestDTO
    - SigninRequestDTO
tech-stack:
  added: []
  patterns:
    - springdoc-property-customizer
    - dual-extension-point-compute-then-reassert (PropertyCustomizer + GlobalOpenApiCustomizer on one bean)
    - path-scoped-visited-set-for-annotation-recursion
key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizer.java
    - src/test/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizerTest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/dto/annotation/ColumnColor.java
    - src/main/java/com/vrudenko/kanban_board/dto/annotation/BoardName.java
    - src/main/java/com/vrudenko/kanban_board/dto/annotation/DisplayName.java
    - src/main/java/com/vrudenko/kanban_board/dto/annotation/Password.java
    - docs/ARCHITECTURE.md
decisions:
  - "D-1: one systemic bean (a PropertyCustomizer), not per-field @Schema annotations -- locked by the user, not re-litigated."
  - "D-3: @Password's regex is published (a user decision), but with a prose description instead of a literal example -- a password-shaped string is exactly gitleaks's pre-commit target."
  - "D-4: the example-satisfies-own-constraint invariant is asserted document-wide over every schema/property, with a non-vacuity floor of 3, not a checked-in list of four fields."
  - "D-5: no validation behavior changed -- the only src/main edits are the new customizer class and four documentation-only @Schema meta-annotations on existing custom constraint types."
  - "X-1: the two-regex conjunction (UpdateBoardRequestDTO.name, SignupRequestDTO.displayName) is built from zero-width lookaheads and proven load-bearing by disabling it and watching the whitespace-only case go RED against the real Validator."
  - "X-2: SaveSubtaskRequestDTO.title's published minLength rises from 1 (looser than the enforcer) to 3 (matching @SubtaskTitle's @Size(min=3)) -- the one direction that actively misleads a client, now fixed."
  - "X-3: SignupRequestDTO.email / SigninRequestDTO.email publish format: email, unwrapped from @AppEmail's composed @Email."
  - "Deviation (Rule 1, bug fix): the plan's own visited-set design (global Set<Class<? extends Annotation>>) silently drops a second @Pattern occurrence with different regexp() values on the same field. Fixed by scoping visited state to the current recursion path (add on descent, remove on return) instead of accumulating it globally."
  - "Deviation (Rule 1, bug fix): swagger-core's ModelResolver calls applyBeanValidatorAnnotations a second time internally, after the PropertyCustomizer has already run, unconditionally re-setting minLength(1) for a direct @NotBlank with no already-higher guard -- stomping this bean's correctly-computed value. Fixed by also registering the bean as a GlobalOpenApiCustomizer that reasserts its recorded values as the document's final word, since springdoc guarantees that phase runs last."
metrics:
  duration: "~2.5 hours (tasks 1-3) + round-4 review fixes"
  completed: "2026-09-05"
actuals:
  tokens: 13012
  tasks: 3
  commits: 5
---

# Quick Task 260904-ss1: Publish composed constraint patterns in the OpenAPI document Summary

A springdoc `PropertyCustomizer` (`ComposedConstraintPropertyCustomizer`) now walks every field's
composed custom validation annotations (`@ColumnColor`, `@BoardName`, `@DisplayName`, `@Password`,
`@TaskTitle`, `@SubtaskTitle`, `@Description`, `@AppEmail`, `@OptionalNotBlank`) and publishes their
`pattern`/`minLength`/`maxLength`/`format`/`example`/`description` on the generated OpenAPI
document -- values swagger-core's own reflection-based generation reads only from a field's
*directly declared* annotations and never sees. The live document went from zero `pattern` keys to
at least eight, one existing documentation defect (`SaveSubtaskRequestDTO.title` publishing a
looser bound than the server enforces) is fixed as a side effect, and two real bugs found during
verification -- not anticipated by the plan -- are fixed and covered by regression tests.

## What Was Built

**Task 1 (`a58eeff`)** -- The customizer, end to end:
- `ComposedConstraintPropertyCustomizer`, a `@Component` registered as both a springdoc
  `PropertyCustomizer` and a `GlobalOpenApiCustomizer` (see Deviations below for why both are
  needed). Recursively walks a field's ctx annotations, gated on `jakarta.validation.Constraint`
  presence, contributing `Pattern`/`Size`/`NotBlank`/`NotEmpty`/`Email` values into an `Accumulator`
  seeded from whatever swagger-core already published directly -- so the merge can only tighten,
  never loosen, an existing value.
- `ComposedConstraintPropertyCustomizerTest`: asserts all 13 rows of the plan's before/after table
  (pattern presence/exact value, minLength/maxLength, format), plus a document-wide "at least 8
  pattern keys" count.

**Task 2 (`7bfd92e`)** -- Equivalence against the real `Validator`:
- `EquivalenceWithRealValidator` `@Nested` group: 14 cases (7 fields x accept/reject) drive the
  autowired `jakarta.validation.Validator` and compare its verdict against the published
  `pattern`/`minLength`/`maxLength`, using `Pattern.compile(...).matcher(value).find()` (a SEARCH,
  matching JSON Schema semantics) rather than `matches()`.

**Task 3 (`c879ddb`)** -- Examples and the invariant that keeps them honest:
- The customizer's walk also collects a meta `io.swagger.v3.oas.annotations.media.Schema`'s
  `example()`/`description()` off any visited annotation type, applied only when the property
  doesn't already carry one.
- `ColumnColor` -> `#1AB2C3`, `BoardName` -> `Platform Launch`, `DisplayName` -> `Ada Lovelace`.
  `Password` gets a prose `description` naming the four character-class rules and the 8-64 bound,
  deliberately no `example` (see D-3 above).
- `ExampleInvariant`: asserts every published `example`, across every schema/property, satisfies
  that same property's own published constraints, with a non-vacuity floor of `>= 3` examples.
- `docs/ARCHITECTURE.md`: one bullet alongside the existing error-envelope entry.

## Verification Requirements -- Evidence

**1. Document-level test proven RED, then GREEN (`@Component` removed).**

Removed `@Component` from the class declaration, re-ran
`./gradlew test --tests '*ComposedConstraintPropertyCustomizerTest*' -x jacocoTestCoverageVerification`.

```
12 tests completed, 6 failed
```

Actual failure messages (first line of each, from the JUnit HTML report):
```
shouldPublishAtLeastEightPatternKeys_whenDocumentIsGenerated:
  java.lang.AssertionError: Expecting actual: <0> to be greater than or equal to: <8>
shouldPublishExactPattern_whenFieldCarriesExactlyOneComposedPattern:
  Expecting empty but was: ["SaveBoardRequestDTO.name -> expected pattern '^[a-zA-Z0-9 ]*$', got 'null'", ...]
shouldPublishMostRestrictiveLength_whenComposedOrDirectAnnotationsPresent:
  Expecting empty but was: ["SaveBoardRequestDTO.name -> expected minLength=1 maxLength=64, got minLength=1 maxLength=null", ...]
shouldPublishNonEmptyConjunctionPattern_whenFieldCarriesTwoComposedPatterns:
  Expecting empty but was: ["UpdateBoardRequestDTO.name -> expected a two-regex conjunction, got 'null'", ...]
shouldRaiseMinLengthToThree_whenSaveSubtaskTitleComposesNotBlankAndSubtaskTitle:
  expected: 3 but was: 1
shouldPublishEmailFormat_whenComposedAppEmailPresent:
  expected: "email" but was: null
```

Restored `@Component`, re-ran: `BUILD SUCCESSFUL`, all 12 pass.

**Second direction (the `getTypes()` branch):** temporarily reduced `isStringSchema` to
`"string".equals(property.getType())` only (dropping the `getTypes()` fallback), re-ran the same
filtered test. **Result: the SAME 6 tests went RED, identical failure set to the fully-disabled
bean.** This document is `openapi: 3.1.0`; `property.getType()` is genuinely `null` here and only
`getTypes()` carries `"string"` -- the branch is load-bearing, not dead code. Restored, confirmed
green.

**2. The lookahead conjunction proven load-bearing.**

Temporarily made `applyTo`'s pattern branch always take `patterns.iterator().next()` regardless of
set size (collapsing any conjunction to its first constituent). Re-ran the full filtered test:

```
13 tests completed, 2 failed
ComposedConstraintPropertyCustomizerTest > EquivalenceWithRealValidator >
  shouldMatchRealValidatorVerdict_whenPublishedConstraintsAreEvaluated() FAILED
ComposedConstraintPropertyCustomizerTest > PublishedConstraints >
  shouldPublishNonEmptyConjunctionPattern_whenFieldCarriesTwoComposedPatterns() FAILED
```

Actual failure message:
```
Expecting empty but was: ["UpdateBoardRequestDTO.name='   ' -> validator accepts=false, document accepts=true"]
```
The real `Validator` rejects three whitespace characters for `UpdateBoardRequestDTO.name`; with
only `@BoardName`'s own pattern (`^[a-zA-Z0-9 ]*$`) published, the document wrongly accepts it --
exactly the case the conjunction exists to close. Restored, confirmed green.

**3. F1 confirmed and fixed independently.** `ValidationConstants.MIN_SUBTASK_TITLE_LENGTH = 3`;
the live document previously published `SaveSubtaskRequestDTO.title -> {"minLength": 1}` (looser
than the enforcer). `shouldRaiseMinLengthToThree_whenSaveSubtaskTitleComposesNotBlankAndSubtaskTitle`
asserts `minLength == 3` specifically (and `!= 1`), and the second Task 2 direction check
(temporarily made the merge take the FIRST-seen minLength contribution instead of the max, letting
the direct `@NotBlank`'s `1` win over the composed `@SubtaskTitle`'s `@Size(min=3)`) reproduced the
original defect:
```
Expecting empty but was: ["SaveSubtaskRequestDTO.title='ab' -> validator accepts=false, document accepts=true"]
```
Restored, confirmed green.

**4. Every published example satisfies its own pattern.** `ExampleInvariant` asserts this
document-wide. Direction check: changed `ColumnColor`'s example to `"1AB2C3"` (missing the leading
`#`, which its own pattern rejects). Re-ran:
```
Expecting empty but was: ["SaveColumnRequestDTO.color -> example '1AB2C3' does not satisfy its own published constraints"]
```
Restored, confirmed green. Second direction: deleted all four `@Schema` meta-annotations
(`ColumnColor`, `BoardName`, `DisplayName`, `Password`), re-ran:
```
Expecting actual: <0> to be greater than or equal to: <3>
```
Confirms the non-vacuity guard is real, not trivially satisfied by an empty loop. Restored,
confirmed green.

**5. Full gates, both after Task 1 and after Task 3:**
```
$ ./gradlew spotlessCheck test
...
> Task :jacocoTestCoverageVerification
BUILD SUCCESSFUL in 4m 39s
10 actionable tasks: 5 executed, 5 up-to-date
```
Zero failures, jacoco's 0.90/0.90/0.75 gate green both times. `git commit` (which runs the
`.githooks/pre-commit` gitleaks scan + `fastTest` gate) succeeded cleanly on all three commits --
no secret-scanning refusal on the `Password` prose description or anything else in the diff.

**6. No validation behavior changed.** The only `src/main` edits beyond the new customizer class
are four `@Schema` meta-annotations on existing custom constraint TYPES (`ColumnColor`, `BoardName`,
`DisplayName`, `Password`) -- none of which is itself a Jakarta validation constraint, so none of
them changes what the real `Validator` accepts or rejects. Every pre-existing test in the full
suite passed unchanged in both full-gate runs above.

**7. `@Password` published with a description, not an example.** Confirmed in
`Password.java` and by `ExampleInvariant`'s document-wide scan, which found examples only on
`ColumnColor`/`BoardName`/`DisplayName` (3 in the fixed set, satisfying the `>= 3` floor with room
to spare since `BoardName`'s example appears on two properties: `SaveBoardRequestDTO.name` and
`UpdateBoardRequestDTO.name`).

## Deviations from Plan

### Auto-fixed Issues (Rule 1 -- real bugs found during verification, not anticipated by the plan)

**1. [Rule 1 -- bug] The plan's own "global visited-set" design silently drops a second
`@Pattern` occurrence carrying different attribute values.**
- **Found during:** Task 1/2 verification -- `UpdateBoardRequestDTO.name` and
  `SignupRequestDTO.displayName` published only ONE constituent regex, not the required
  conjunction, even though the walk logic looked correct on inspection.
- **Root cause:** the plan's Task 1 action text specifies "Carry a `Set<Class<? extends
  Annotation>>` of already-visited annotation types and skip anything already in it." Since
  `jakarta.validation.constraints.Pattern` is the SAME `Class` object for both `@BoardName`'s own
  meta-`@Pattern` and `@OptionalNotBlank`'s own meta-`@Pattern` (different `regexp()` values, same
  annotation type), a set keyed purely by `Class` and never cleared treats the second occurrence as
  "already handled" and drops its contribution.
- **Fix:** scoped the visited set to the CURRENT recursion path only -- add the type on descent,
  remove it on return (classic DFS cycle guard), rather than accumulating it for the whole
  `customize()` call. This still terminates on a true cycle (the reason the guard exists at all)
  while correctly allowing the same annotation TYPE to be visited via two independent sibling
  branches.
- **Files modified:** `ComposedConstraintPropertyCustomizer.java` (`walk` method).
- **Verified:** direction check #2 above (RED with the old collapse-to-first-regex behavior,
  GREEN restored); full suite green.

**2. [Rule 1 -- bug] swagger-core internally re-applies a direct `@NotBlank`'s naive rule AFTER
the `PropertyCustomizer` has already run, silently reverting a correctly-raised `minLength`.**
- **Found during:** Task 1 verification -- `SaveSubtaskRequestDTO.title` still published
  `minLength: 1` in the live served document even though instrumentation confirmed the customizer
  itself computed and set `minLength: 3` on the exact `Schema` object just before returning it.
- **Root cause, read from `ModelResolver.java` (swagger-core-jakarta 2.2.30) lines ~780-905:**
  after `property = context.resolve(aType)` (which runs the full converter chain, including this
  bean), `ctxProperty` is set equal to `property` (same reference, on the non-`allOf` resolution
  path this application uses), and `applyBeanValidatorAnnotations(propDef, ctxProperty,
  annotations, model, applyNotNullAnnotations)` is called a SECOND time, directly, bypassing the
  converter chain entirely. That method's `NotBlank` branch unconditionally calls
  `property.setMinLength(1)` whenever `@NotBlank` is directly present, with no "already higher,
  leave it" guard -- confirmed by instrumenting both the bean (temporary `System.err` prints,
  removed before commit) and a live `./gradlew bootRun` instance hit with `curl`.
- **Fix:** the bean is now registered as BOTH a `PropertyCustomizer` (applies values immediately,
  correct for every property this internal second pass never touches) AND a
  `GlobalOpenApiCustomizer` (springdoc's guaranteed-last, whole-document post-processing phase).
  `customize()` records its computed `Accumulator` into `computedBySchema`, keyed by the schema
  name `AnnotatedType.getParent().getName()` already assigns (no reflective class-name guessing
  needed -- the SAME string is used to write and to read, so this does not reintroduce the
  weakness the plan's own Approach C rejection called out). `customise(OpenAPI)` re-applies those
  recorded values as the document's final word.
- **Files modified:** `ComposedConstraintPropertyCustomizer.java` (new `GlobalOpenApiCustomizer`
  interface, `computedBySchema` field, `record`/`customise` methods).
- **Verified:** live `bootRun` + `curl http://localhost:8080/api/docs` before and after the fix;
  `shouldRaiseMinLengthToThree_...` test; full suite green.
- **Local debugging artifacts cleaned up:** the `docker compose up -d postgres redpanda` /
  `bootRun` session used to reproduce this against a real running instance was torn down
  (`docker compose down` + `docker volume rm` for the two project-scoped volumes) before
  continuing; `docker ps -a` confirmed no leftover containers at the end of the task.

No other deviations. The plan's `<tasks>` were executed as written otherwise; the customizer's
"one systemic bean" shape (D-1) and locked file list were followed exactly -- the dual
`PropertyCustomizer`/`GlobalOpenApiCustomizer` registration is still ONE `@Component`, not a second
bean, so it does not reopen D-1.

## Findings Carried From the Plan (not fixed, reported per its own instruction)

- **F2 -- composed `@NotBlank` also fails to mark a property `required`.** Out of scope (a
  parent-schema key, not one of `pattern`/`minLength`/`maxLength`/`format`); not touched.
- **F3 -- `@OptionalNotBlank`'s `Pattern.Flag.DOTALL` has no OpenAPI equivalent.** ~~Confirmed
  costing nothing today by Task 2's equivalence test, which would catch a future site where it
  matters.~~ **This assessment was WRONG, and round 4 (below) disproved it.** Task 2's equivalence
  test did not catch it because that test drove only ASCII single-line values; the divergence
  needs a multi-line or a U+00A0-only value to surface. F3 was a live defect in the
  document-stricter-than-enforcer direction, now fixed by D1.
- **F4 -- no contradiction found between any enforced and any documentable constraint,** beyond
  the two bugs found and fixed above (which were bugs in THIS bean's own construction, not in the
  underlying annotation set).

## Round 4 -- Cross-AI Review Fixes (2026-09-05)

A cross-AI review after task 3 found two further defects in this bean, both in the
document-stricter-than-enforcer direction the bean exists to prevent, and raised one question
answered as a decision rather than a fix.

**D1 -- Java regex republished verbatim into an ECMA-262 field.** `contribute()` copied a
`@Pattern`'s `regexp()` straight onto the schema and dropped `flags()` entirely. JSON Schema's
`pattern` is ECMA-262; two divergences bit at once for `@OptionalNotBlank` (`.*\S.*`, `DOTALL`):
ECMA has no `DOTALL` equivalent so `.` stopped matching newlines, and ECMA's `\S` is
Unicode-aware where Java's is ASCII-only. Both proven live (node v24.19.0) to make the published
pattern REJECT a value the real `Validator` ACCEPTS -- a multi-line title, and a value made
solely of U+00A0. Fixed by `ecmaEquivalentOf`, which rewrites `.` to `[\s\S]` under `DOTALL`
and `\s`/`\S` to explicit ASCII classes, and returns empty (publishing nothing) for anything it
cannot prove equivalent.

This is the defect **F3 above wrongly cleared** -- see the strikethrough there.

**D2 -- phase 2 could overwrite a stricter value with an older, looser one.** `customise(OpenAPI)`
called the unconditional `applyTo`, replaying a phase-1 snapshot over whatever was on the schema
by then. Confirmed by triple-boot: a field-level `@Schema(minLength = 10, maxLength = 20, pattern
= "^Sprint .*$")` on `SaveBoardRequestDTO.name` survived with the bean disabled and was REPLACED
by the looser `1 / 64 / ^[a-zA-Z0-9 ]*$` with it enabled. Latent today (no DTO field carries its
own `@Schema` constraint) but this very task introduced `@Schema` to the codebase's vocabulary.
Fixed by `reassertOn`, a tighten-only counterpart to `applyTo`. The class-level Javadoc's old
claim that phase 2 "only ever restates values it already computed" was retired with it -- true of
phase 2's own recorded values, never of what else might be on the schema by then.

**D3 -- `@Size` (UTF-16 code units) vs. JSON Schema `maxLength` (code points): decided, not
fixed.** The `minLength` half is provably safe (the published check can only be stricter). The
`maxLength` half genuinely diverges -- 17 astral characters are 17 code points but 34 UTF-16
units, so the document accepts what `@SubtaskTitle(max=32)` rejects. Published unchanged anyway;
the full argument, the two rejected alternatives, and the condition that would flip the decision
are recorded at the `@Size` branch in the source.

### Evidence

RED-then-GREEN on the same two tests, `ecmaEquivalentOf` written but deliberately unwired:

```
ComposedConstraintPropertyCustomizerTest > PublishedConstraints >
  shouldPublishExactPattern_whenFieldCarriesExactlyOneComposedPattern() FAILED
  Expecting empty but was: ["UpdateTaskRequestDTO.title -> expected pattern
    '[\s\S]*[^ \t\n\x0B\f\r][\s\S]*', got '.*\S.*'", ...]
ComposedConstraintPropertyCustomizerTest > PublishedConstraints >
  shouldMatchRealValidatorOnMultilineAndNbspOnlyValues_whenPatternIsOptionalNotBlank() FAILED
  [published pattern vs. real validator: multiline] expected: true but was: false
```

Wiring `ecmaEquivalentOf` into `contribute()`: 15 ComposedConstraint tests, 0 failed.

## Round 5 -- Re-review of the round-4 commit (2026-09-05)

Round 4's own commit was re-reviewed by Claude and Codex (Gemini failed a third time on
`Error: timeout waiting for response` and is recorded as a FAILED run, not a clean one). Both
independently found that round 4's D3 decision record argued from a correct inequality to the
wrong conclusion.

**The minLength half was a live document-stricter-than-enforcer defect, not a safe trade-off.**
`@Size` counts UTF-16 code units; JSON Schema `minLength` counts code points. A two-emoji title is
4 units, so `@Size(min = 3)` accepts it, but only 2 code points -- so a spec-compliant generated
client refuses to send a request the server would have taken. Round 4 verified only that the
published bound never ACCEPTS what the server REJECTS (the tolerated direction) and called that
"provably safe". Live on seven properties.

Fixed by publishing `ceil(n / 2)`, the largest bound no server-accepted value can fail. The first
attempt converted inside `contribute()` and was **incomplete** -- fields carrying a DIRECT `@Size`
(`SaveColumnRequestDTO.name`, `SaveTaskRequestDTO.title`) are published by swagger-core itself, and
the accumulator could only raise, so the unconverted `3` won. The bound is now carried in
`minLengthUnits` and converted once at publish time, and phase 2 may lower a `minLength` it can
identify as swagger-core's raw unit bound -- recognisable because it equals the unit value the
accumulator holds, which a field-level `@Schema` would not.

**The DOTALL/`\S` translation had no test that could catch it breaking.** `shouldPublishExactPattern_*`
derived its expected value by calling `ecmaEquivalentOf`, the method under test, so mutating the
translation mutated the expectation in lockstep. Proven 2026-09-05: reverting the `\S` branch to
emit Java's `\S` verbatim left all 15 tests green. Fixed with
`shouldPublishThisExactLiteralTranslation_whenPatternIsOptionalNotBlank`, a hand-written literal
that is deliberately independent of production; it fails on that mutation.

Also carried over: the equivalence test keeps its exact-agreement comparison (which is what proved
the conjunction load-bearing in round 3) rather than relaxing to a one-way implication. The
stricter-than-enforcer direction now fails unconditionally, and the one deliberate divergence is
enumerated in `TOLERATED_LOOSER_THAN_ENFORCER`.

### Evidence

| Check | Result |
|-------|--------|
| Revert `ceil(n/2)` to verbatim | 4 FAILED, incl. `shouldAcceptAstralValueTheValidatorAccepts_*` |
| Restore, mutate the `\S` branch | 3 FAILED, incl. `shouldPublishThisExactLiteralTranslation_*` |
| Full suite + jacoco | `BUILD SUCCESSFUL in 5m 10s`, 521 tests, 0 failures |

### Still open (filed, not fixed)

`ecmaEquivalentOf`'s Javadoc claims it returns empty for anything it cannot prove equivalent, but
only guards `flags()` and `(`-constructs. `\Q...\E`, `\p{L}`, `\v`, `\h`, `\A`/`\z`, class
intersection, `[^]]` and possessive quantifiers all pass through verbatim, several producing a
stricter ECMA pattern and one an outright `SyntaxError`. Latent -- all four `@Pattern`s in this
codebase translate correctly, verified head-to-head in Java and node -- but the Javadoc promises a
guarantee the code does not provide. Needs a stricter escape whitelist.

## Known Stubs

None.

## Threat Flags

None -- no new network endpoint, auth path, or trust-boundary schema change. The `@Password`
regex becomes publicly documented (an explicit, approved user decision, D-3/trade-off 5 in the
plan) but discloses nothing not already recoverable from a single failed signup's 400 body.

## Self-Check: PASSED

- FOUND: src/main/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizer.java
- FOUND: src/test/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizerTest.java
- FOUND: src/main/java/com/vrudenko/kanban_board/dto/annotation/ColumnColor.java
- FOUND: src/main/java/com/vrudenko/kanban_board/dto/annotation/BoardName.java
- FOUND: src/main/java/com/vrudenko/kanban_board/dto/annotation/DisplayName.java
- FOUND: src/main/java/com/vrudenko/kanban_board/dto/annotation/Password.java
- FOUND: docs/ARCHITECTURE.md
- FOUND commit: a58eeff88da0dd0b2c275faaf65c4aa31c1f71c6 (task 1 -- feat: the customizer, RED-then-green proven)
- FOUND commit: 7bfd92e524aeddb229de1806932b3d76371c2826 (task 2 -- test: equivalence with the real Validator)
- FOUND commit: c879ddbacd8643e2653aac09dc6c9aa7fc5081ec (task 3 -- feat: examples and the invariant)
