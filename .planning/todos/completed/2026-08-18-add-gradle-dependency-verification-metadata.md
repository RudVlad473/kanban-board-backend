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
