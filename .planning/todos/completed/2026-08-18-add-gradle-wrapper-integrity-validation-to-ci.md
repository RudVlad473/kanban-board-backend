completed: 2026-08-20
---
created: 2026-08-18T20:57:50.115Z
title: Add Gradle wrapper integrity validation to CI
area: tooling
severity: minor
resolves_phase: 10
files:

  - gradle/wrapper/gradle-wrapper.properties
  - .github/workflows/deploy.yml
  - .github/workflows/security-scan.yml

---

## Problem

`gradle/wrapper/gradle-wrapper.properties` sets `validateDistributionUrl=true`,
which confirms the Gradle distribution download URL is on Gradle's own
`services.gradle.org` domain — but there is no `distributionSha256Sum` pinned,
so a compromised or MITM'd download at that domain would not be caught. More
importantly, no CI workflow runs `gradle/actions/wrapper-validation` (or
equivalent) to confirm `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` themselves
haven't been tampered with in a PR — a modified wrapper script or jar could run
arbitrary code on any CI runner or contributor machine that executes it, before
Gradle itself is even invoked.

Found during Phase 9's supply-chain review alongside the two gaps that became
`HARDEN-01`/`HARDEN-03`; this one and dependency-verification-metadata (see the
companion todo) are the remainder, not currently covered by any existing
`HARDEN-*` requirement.

## Solution

1. Add `distributionSha256Sum` to `gradle-wrapper.properties`, pinning the
   exact Gradle 8.11.1 distribution checksum (obtainable from
   `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256`).

2. Add a `gradle/actions/wrapper-validation@v4` (or current version) step to
   both `deploy.yml`'s `run-tests` job and `security-scan.yml`, running before
   any `./gradlew` invocation, so a tampered wrapper fails the build loudly
   rather than executing.

## Resolution (2026-08-20)

Delivered by Phase 10: `gradle-wrapper.properties` carries `distributionSha256Sum`, and both
`deploy.yml` and `security-scan.yml` run `gradle/actions/wrapper-validation@v6` before any
`./gradlew` invocation. Found already satisfied while triaging pending todos after Phase 10
closed; moved straight to completed without further action.
