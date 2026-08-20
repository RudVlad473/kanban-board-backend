completed: 2026-08-20
---
created: 2026-08-16T19:15:00.000Z
title: Add cache 'gradle' to deploy.yml's run-tests Set up Java step
area: tooling
severity: minor
resolves_phase: 10
files:

  - .github/workflows/deploy.yml

---

## Problem

`deploy.yml`'s `run-tests` job's `Set up Java` step does not set
`cache: 'gradle'`, unlike the same-repo precedent in
`security-scan.yml:57-68`, which does. Every `run-tests` invocation currently
re-downloads the Gradle distribution and dependency cache from scratch --
observed live during quick task 260816-sv1's verification: the
`build-and-push-docker-image` job's own (separate, Dockerfile-driven) Gradle
run hit a `java.net.SocketException: Connection reset` fetching
`gradle-8.11.1-bin.zip` from `services.gradle.org`, a transient failure whose
blast radius (a full job retry) would likely shrink with a warm dependency
cache, though `run-tests`'s `Set up Java` step is a distinct, non-Dockerfile
Gradle invocation from the one that actually failed.

Surfaced but deliberately not fixed during 260816-sv1: it is a performance
change with its own cache-key correctness question (Gradle caching can go
subtly stale across dependency-version changes if the cache key isn't scoped
correctly), explicitly out of scope for a task whose action text said "do not
add `cache: 'gradle'` ... that is a performance change with its own cache-key
correctness question, outside this task's scope."

## Solution

Add `cache: 'gradle'` to `run-tests`'s `Set up Java` step, following
`security-scan.yml`'s existing pattern. Verify: (1) a cache-hit run measurably
reduces `run-tests`' wall-clock versus the current ~6 minute baseline
(`31964944867`/`31966148764`), (2) a dependency-version bump still correctly
invalidates the cache rather than silently serving stale artifacts (`setup-java`'s
built-in Gradle cache action keys on `build.gradle`/`settings.gradle`/wrapper
properties by default -- confirm this repo's actual key inputs cover every file
that can change a build's dependency graph before trusting it).

**Trigger:** any time after this todo is picked up; not gating any current phase.
Low priority -- purely a CI wall-clock optimization, not a correctness issue.

## Resolution (2026-08-20)

Delivered by Phase 10 Plan 10-01: `deploy.yml`'s `run-tests` job's `Set up Java` step carries
`cache: 'gradle'` (`.github/workflows/deploy.yml:69`), matching `security-scan.yml`'s existing
pattern. Found already satisfied while triaging pending todos after Phase 10 closed; moved
straight to completed without further action.
