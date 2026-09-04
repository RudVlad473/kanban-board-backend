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
2. **Published-constraints-vs-real-Validator equivalence.** `ComposedConstraintPropertyCustomizerTest`
   already has an `EquivalenceWithRealValidator` nested class — and its own Javadoc says it checks
   "a hand-picked sample". That is a property wearing an example's clothes: the real claim is *for
   ANY string, the published constraints and the real `jakarta.validation.Validator` reach the same
   verdict*. Generating the strings instead of picking them is a direct upgrade with no new concept
   to introduce.
3. **Task position arithmetic — `TaskService` move/reorder.** Classic property-test territory:
   after any sequence of moves, positions within a column should remain a contiguous `0..n-1`
   permutation with no duplicates and no gaps. Example-based tests here can only cover the
   move-shapes someone enumerated; the invariant covers all of them.
4. **`ValidationConstants` boundary pairs.** Lower value — `ColumnColorTest`'s 12-case boundary
   matrix already does this well by hand — but worth checking whether the same matrix repeated
   across annotations would collapse into one parameterized property.

## Known integration footgun — do not skip this

jqwik's own Gradle instructions say to configure `useJUnitPlatform { includeEngines 'jqwik' }`.
**Applying that literally here would silently disable all 514 existing tests.** This build has three
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
  drop after the build change. `514` is the current number — a silent engine exclusion shows up there
  and nowhere else.
