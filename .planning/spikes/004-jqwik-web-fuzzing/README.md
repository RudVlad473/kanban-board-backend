---
spike: 004
idea: web-fuzzing
name: jqwik-web-fuzzing
type: comparison
validates: "Given the existing Spring/Testcontainers test harness, when jqwik property tests drive the HTTP surface through MockMvc, then they run inside `./gradlew test`, find/shrink failures, and need no changes to production code"
verdict: VALIDATED
related: [003]
tags: [fuzzing, jqwik, property-based-testing, junit]
---

# Spike 004: jqwik for web fuzzing (comparison with 003 Schemathesis)

## What This Validates
Given the repo's `AbstractPostgresContainerTest` + `@SpringBootTest` + MockMvc harness, when a jqwik
`@Property` generates request bodies for `POST /signup`, then it runs in the normal test task against
real Postgres, reports a replayable seed, and shrinks a failure to a minimal example.

## Research
| Approach | Pros | Cons | Status |
|----------|------|------|--------|
| jqwik 1.10.1 + jqwik-spring 0.12.0 | Same language and task as the rest of the suite; generators are typed Java; shrinking; seed replay; doubles as the property-based-testing foundation | You write each property by hand; no spec-driven discovery; new test deps | **built, worked** |
| Schemathesis (spike 003) | Zero code, covers every operation in the doc | Quality capped by the OpenAPI doc (11 ops unfuzzable today) | measured in 003 |

Docs checked via context7 (`/jqwik-team/jqwik`): jqwik is its own JUnit Platform engine, so it
coexists with Jupiter under one `useJUnitPlatform`.

## How to Run
Probe source is saved as `SignupFuzzProbe.java.txt` (`.txt` so it does not compile); the build change
is `build.gradle.diff`. To reproduce: apply the diff, copy the probe into `src/test/java/.../spike/`,
then `./gradlew test --tests '*SignupFuzzProbe*' -x jacocoTestCoverageVerification --dependency-verification=lenient`.

## Investigation Trail
1. Added `net.jqwik:jqwik:1.10.1` and `net.jqwik:jqwik-spring:0.12.0` as `testImplementation`.
2. First attempt failed: `gradle/verification-metadata.xml` rejects **14 unpinned artifacts**
   (jqwik, -api, -engine, -time, -web, -spring, plus apiguardian and opentest4j POMs). Real adoption
   cost: generate and review checksums for all of them.
3. Ran with `--dependency-verification=lenient` for the spike only. The metadata file was not edited.
4. Property: random email/password/displayName strings (<=40 chars) posted to `/signup`, asserting
   status < 500. **Result: 200 checks, 0 failures** (report XML shows `tries = 200`, `checks = 200`,
   `edge-cases#total = 27`, `generation = RANDOMIZED`). Wall time 56s, mostly Spring context + Postgres
   container startup (shared with the suite when run together).
5. **Negative direction:** tightened the assertion to status < 400. It failed, shrank in 3 steps to
   `email: "" password: "" displayName: ""`, and printed `seed = 5928181601868785688` for replay. So
   the probe can fail and the 0-failure result is meaningful, for this one property.
6. Side effects: jqwik writes `.jqwik-database` into the repo root (needs a `.gitignore` entry or
   `jqwik.properties` `database` setting); I removed it. No leaked containers from my runs.

## Results
- **Works with the existing harness**: no production changes, no new base class, reuses
  `AbstractPostgresContainerTest`. `@JqwikSpringSupport` + `@SpringBootTest` + `@AutoConfigureMockMvc`
  composed without trouble.
- **Finds nothing on `/signup`** with arbitrary short strings: no 5xx in 200 tries. That is one
  endpoint, one property, short ASCII/Unicode strings; it says little about the other 23 operations
  or about structurally malformed JSON (which Schemathesis exercised and I did not triage).
- **Comparison with Schemathesis:** jqwik is the better *regression guard* (deterministic seed, runs
  in CI with the suite, typed generators for domain rules such as `@Password`). Schemathesis is the
  better *discovery* tool (breadth with no code) but is blocked by spec quality. They complement.

Not measured: run time per property in a shared-context full suite, cost of writing properties for
nested routes (needs created board/column/task fixtures per try), interaction with
`maxParallelForks = 2`, `fastTest` and the JaCoCo ratchet (I excluded coverage verification).

## Signal for the Build
- **Adopting costs** 14 checksum entries in `verification-metadata.xml` plus a `.gitignore` line for
  `.jqwik-database`. Both small, but the checksum review is a deliberate supply-chain step here.
- **Seed-and-shrink are the payoff** over a hand-rolled loop of random strings.
- Property tests that create fixtures per try are slow against Postgres; start with pure properties
  (DTO validation, ID generator, mapper round-trips) before HTTP-level ones.
- Property-based testing later: this spike's setup is already the foundation (same dependency and
  harness), so the fuzzing and PBT goals share one adoption cost.
