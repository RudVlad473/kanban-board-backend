---
created: 2026-09-04T21:40:00.000Z
title: Audit the codebase for places property-based testing (jqwik) would pay off
area: testing
severity: minor
files:
  - build.gradle
  - src/test/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizerTest.java
  - src/main/java/com/vrudenko/kanban_board/config/ComposedConstraintPropertyCustomizer.java
  - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
---

## Problem

Every test in this repo is example-based: a hand-picked input, a hand-written expectation. That is
the right default, but it has a known blind spot — it only ever checks the cases someone thought of.
Quick task 260904-ss1 hit that blind spot live. `ComposedConstraintPropertyCustomizer` emitted a
multi-regex conjunction of zero-width lookaheads; every example-based assertion passed, because they
all asked "does the document contain the right pattern?" A generated client full-matching that
pattern would have rejected every valid value, and it took a reviewer asking "what does a consumer
do with this string?" to find it. A property — *for any pair of regexes R1, R2 and any string s, s
satisfies conjunction(R1, R2) iff s matches R1 and s matches R2* — is the shape of assertion that
finds this without anyone anticipating the specific failure.

Source suggested by the repo owner: https://www.baeldung.com/java-jqwik-property-based-testing
(returns HTTP 403 to automated fetches; the upstream user guide at
https://jqwik.net/docs/current/user-guide.html carries the same material).

## Audit these candidates first

Ranked by expected payoff, not by ease:

1. **Regex conjunction — `ComposedConstraintPropertyCustomizer.Accumulator.applyTo`.** Generate
   pairs/triples of regexes and arbitrary strings; assert the conjunction accepts exactly the
   intersection, under BOTH `find()` (JSON Schema search semantics) and `matches()` (what a
   generated client may do). This is the site whose defect example-based tests missed today.
2. **Published-constraints-vs-real-Validator equivalence. Promoted to first place by a second
   live defect on 2026-09-05 — see "The case this todo is now built on" below.**
   `ComposedConstraintPropertyCustomizerTest` already has an `EquivalenceWithRealValidator` nested
   class — and its own Javadoc says it checks "a hand-picked sample". That is a property wearing an
   example's clothes.

   Write it one-directional, because the two directions are not equally acceptable:

   ```
   for all s:  realValidator.accepts(s)  implies  publishedSchema.accepts(s)
   ```

   A document STRICTER than the enforcer blocks a generated client from sending a legal request and
   is never tolerable. A document LOOSER costs a 400 the validator was always going to produce, and
   is deliberately allowed for `maxLength` and for the code-point/code-unit gap. The current test
   encodes that asymmetry by hand, in a `TOLERATED_LOOSER_THAN_ENFORCER` set of literal strings; a
   property expresses it structurally and stops the set from silently going stale.
3. **Task position arithmetic — `TaskService` move/reorder.** Classic property-test territory:
   after any sequence of moves, positions within a column should remain a contiguous `0..n-1`
   permutation with no duplicates and no gaps. Example-based tests here can only cover the
   move-shapes someone enumerated; the invariant covers all of them.
4. **`ValidationConstants` boundary pairs.** Lower value — `ColumnColorTest`'s 12-case boundary
   matrix already does this well by hand — but worth checking whether the same matrix repeated
   across annotations would collapse into one parameterized property.

## The case this todo is now built on (2026-09-05)

Quick task 260904-ss1 shipped a SECOND defect that every example-based test missed, in the same
class, and it is the strongest argument here.

`@Size` counts UTF-16 code units; JSON Schema `minLength` counts Unicode code points. `aA1!` plus
two emoji is 8 code units (so `@Size(min = 8)` accepts it) and 6 code points (so a published
`minLength: 8` rejects it) — a generated client would refuse to send a legal password. Live on
seven properties. Found by a reviewer reasoning about Unicode, after FOUR review rounds and three
independent AI reviewers had passed over the code; no test in the suite could have failed.

The property above finds it immediately — **but only if the string generator emits supplementary
plane characters.** For pure ASCII the two counters agree exactly, which is precisely why every
hand-picked fixture, every manual check and every reviewer's intuition agreed too. A property with
a Latin-only alphabet would pass forever and prove nothing, reproducing the original blind spot
with more machinery.

**So: verify jqwik's default `@ForAll String` alphabet against its actual behaviour before trusting
any green run, and configure the generator explicitly if it does not reach beyond the BMP.** Treat
a green property whose alphabet you have not checked exactly as you would a test suite you have not
watched fail.

## Use a property to GUARD a bound, never to DERIVE one

A related trap, worth stating because it was live during the same task. `@DisplayName` carries
`@Pattern("^[a-zA-Z ]*$")`, which admits no astral character, so its code-point and code-unit counts
can never diverge and its exact `@Size` bound would be safe to publish verbatim.

It is tempting to establish that with a property — generate strings, find no counterexample,
publish the exact bound. **That is unsound.** Absence of a counterexample is not proof of BMP
confinement; it is the same shape of error as the round-4 decision record that reasoned correctly
about one direction and declared the result "provably safe". Which bound gets published stays an
explicit, declared decision; the property's job is to fail when the declaration stops holding.

## Will it slow the suite down? Only if the expensive setup sits inside the loop

A property runs its body N times (jqwik's default is 1000 tries — confirm against the installed
version rather than trusting this note). So the cost question is never "does jqwik slow things
down", it is **what is inside the generated loop**:

| Property shape | Per-try cost | At 1000 tries | Verdict |
|---|---|---|---|
| Pure unit — regex conjunction, `ecmaEquivalentOf` translation | regex compile + match | well under a second | free; this is candidate 1 |
| Real `Validator` + a document fetched ONCE in `// arrange` | one `validate()` + a regex match | low single-digit seconds | acceptable |
| `fetchDocument()` called INSIDE the property body | a MockMvc round trip | 1000 HTTP round trips | the one that hurts |

The third row is the realistic mistake, because every `// arrange` block in
`ComposedConstraintPropertyCustomizerTest` calls `fetchDocument()` — copying that idiom into a
property body multiplies it by the try count. Fetch once, generate only the string.

What costs nothing: a property added to the EXISTING `@SpringBootTest` class reuses the same Spring
context and the same Testcontainers Postgres, so it starts no additional container. The
`maxParallelForks = 2` concern above applies to a property in a NEW container-backed class, not to
one added here. The dependency itself is startup noise against a suite that already spends most of
its ~5-6 minutes booting Postgres and Redpanda (523 tests, 2026-09-05).

**Add a tag when adopting, not later.** Putting properties behind their own `@Tag` lets `fastTest`
(the pre-commit gate, which already excludes `kafka` and `realSocket`) skip them while CI runs them
in full. Cheap at adoption, awkward to retrofit once properties are spread across classes.

**The one measurement worth making before adopting:** wall-clock of a single `fetchDocument()` plus
one `validator.validate()`. That turns the middle row from an estimate into a fact, and it decides
whether the equivalence property can run at the default try count or needs a lower one.

## Known integration footgun — do not skip this

jqwik's own Gradle instructions say to configure `useJUnitPlatform { includeEngines 'jqwik' }`.
**Applying that literally here would silently disable every one of the existing tests**
(521 as of 2026-09-05). This build has three
`useJUnitPlatform` blocks (`test` at build.gradle:296, `fastTest` at :349, `rehearsal` at :672) and
none of them declares `includeEngines`, so they currently run every discovered engine. Naming only
`jqwik` would exclude `junit-jupiter`. If engines are named at all, all three blocks need
`includeEngines 'jqwik', 'junit-jupiter'`.

Two further build interactions to check rather than assume:
- All three blocks use tag-based exclusion (`rehearsal`, `kafka`, `realSocket`). Confirm jqwik
  properties honour JUnit 5 `@Tag` the same way, or the pre-commit `fastTest` gate could start
  running container-backed properties it was built to skip.
- `maxParallelForks = 2` is a measured setting (quick task 260811-ixj), and each fork starts its own
  PostgreSQL container. A property with a high `tries` count against a Testcontainers-backed context
  multiplies real database work — prefer pure-unit properties (the regex work above) over
  container-backed ones, at least initially.

## Suggested approach

- Audit first, adopt second. The deliverable of this todo is a written verdict on whether jqwik earns
  its dependency, not a build change. If the answer is "only candidate 1 and 2 are worth it," that is
  a legitimate and probably correct outcome.
- If adopted, scope the first change to ONE pure-unit property (candidate 1) with no Spring context
  and no container, so the engine wiring is proven in isolation before anything container-backed
  depends on it.
- Verify the wiring the way this repo verifies everything else: confirm the full suite count does not
  drop after the build change. `521` is the current number (2026-09-05; it was 514 when this todo was
  filed) — a silent engine exclusion shows up there and nowhere else.
