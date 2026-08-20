completed: 2026-08-20
---
created: 2026-08-18T20:57:50.115Z
title: Add Gradle dependency verification metadata (checksum/signature pinning)
area: tooling
severity: minor
resolves_phase: 10
files:

  - build.gradle
  - gradle/verification-metadata.xml

---

## Problem

`build.gradle` pins every dependency and plugin coordinate to an exact version
(no `+` ranges, no `latest.release`) — confirmed by direct inspection during
Phase 9's supply-chain review — which stops accidental drift onto a different
release. But no `gradle/verification-metadata.xml` exists, so Gradle's own
dependency-verification feature (checksum and/or PGP signature pinning per
resolved artifact) is not enabled. Exact version pins alone do not protect
against a compromised artifact republished under the *same* coordinates and
version at Maven Central or the project's second repository
(`packages.confluent.io/maven/`) — nothing currently would catch that.

This is distinct from `HARDEN-03` (digest-pinning GitHub Actions `uses:`
references) — that covers CI Action supply-chain integrity; this covers Gradle
dependency-artifact integrity, a different trust boundary entirely.

## Solution

Run `./gradlew --write-verification-metadata sha256` (or `pgp,sha256` if
signature verification is also wanted) to generate the metadata file from the
current, already-reviewed dependency set, commit it, and wire a CI check
(`./gradlew help --write-verification-metadata sha256 --dry-run` or equivalent)
that fails when the metadata is stale relative to `build.gradle`'s declared
dependencies — so a future dependency bump can't silently drop verification
coverage. Consider gating this in `run-tests` (fast, deterministic) rather than
the network-bound `security-scan.yml`.

## Resolution (2026-08-20)

Delivered by Phase 10: `gradle/verification-metadata.xml` exists, committed. Found already
satisfied while triaging pending todos after Phase 10 closed; moved straight to completed
without further action. (Not independently re-verified here whether the CI staleness-check half
of the todo's Solution was wired — the file's existence is the load-bearing fact this resolution
confirms.)

## Addendum (2026-08-20, later same day) — the file's existence was not sufficient

The claim above turned out to be incomplete, corrected in place rather than rewritten (this
file's own established convention). While unrelated test-file edits triggered the first real
local `compileTestJava` recompile since this metadata was generated, the build failed:
`guava-33.5.0-jre.pom` had no recorded checksum, even though `guava-33.5.0-jre.jar` and
`.module` both did — a genuine gap in the original generation, not a new drift. Root cause: some
`testAnnotationProcessor`-configuration resolution path (traced to `error_prone_core:2.50.0`'s
own transitive dependency graph) needs the raw `.pom`, a path the original
`--write-verification-metadata` invocation apparently never exercised. Fixed by re-running
`./gradlew --write-verification-metadata sha256 compileTestJava` (targeting the task that
actually failed, since a bare `help` invocation did not reproduce or fix it) — added exactly the
one missing `<artifact name="guava-33.5.0-jre.pom">` entry. `compileTestJava` and `fastTest` both
pass clean afterward.

The todo's Solution's second half — a CI check that fails when the metadata is stale relative to
`build.gradle` — still does not exist (confirmed: no `verification-metadata`/
`write-verification-metadata` string anywhere under `.github/workflows/`). That gap is exactly
why this specific hole went undetected through Phase 10's own CI runs. Filed as a new pending
todo: `.planning/todos/pending/2026-08-20-no-ci-check-for-stale-gradle-verification-metadata.md`.
