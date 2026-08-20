---
created: 2026-08-20T09:10:00.000Z
title: No CI check for stale Gradle dependency-verification metadata
area: tooling
severity: minor
files:
  - gradle/verification-metadata.xml
  - .github/workflows/deploy.yml
  - .github/workflows/security-scan.yml
---

## Problem

`gradle/verification-metadata.xml` (added by Phase 10) records per-artifact checksums that
Gradle's dependency-verification feature checks on every resolution. Discovered live
(2026-08-20): the file was missing the `.pom` checksum for `guava-33.5.0-jre` — present for the
`.jar` and `.module` artifacts of that same version, but not the `.pom` — even though this
version's `.jar`/`.module` entries date from the original Phase 10 generation. A
`testAnnotationProcessor`-configuration resolution path (traced to `error_prone_core:2.50.0`'s
own transitive graph) needs the `.pom` specifically; that path apparently was not exercised by
whatever invocation originally generated the metadata (a bare `./gradlew help
--write-verification-metadata` run does not reproduce or fix it — only targeting the actual
failing task, `compileTestJava`, does).

This went completely undetected through every Phase 10 CI run because no workflow ever runs
`--write-verification-metadata ... --dry-run` (or equivalent) to check the committed file against
what current dependency resolution actually needs — confirmed by `grep -r
"verification-metadata\|write-verification-metadata" .github/workflows/` returning nothing. The
gap was found only because a local recompile happened to hit a resolution path CI's own task
graph apparently does not exercise the same way.

## Solution

Wire a CI check that fails when the committed metadata is stale relative to what a real build
resolves — the second half of the original todo
(`.planning/todos/completed/2026-08-18-add-gradle-dependency-verification-metadata.md`) that was
never actually delivered. Candidates:

1. A dedicated step (in `run-tests` or a new job) that runs
   `./gradlew --write-verification-metadata sha256 <the real build/test tasks> --dry-run` (or the
   non-dry-run form against a scratch copy) and diffs the result against the committed file,
   failing on any difference.
2. At minimum, ensure whatever task set is used to (re)generate the metadata during maintenance
   actually covers `compileTestJava`/`testAnnotationProcessor` and any other configuration a real
   `./gradlew test` resolves — a `help`-only invocation is not sufficient, as this todo's own
   discovery proved.

**Trigger:** any time after this todo is picked up; not gating any current phase. Low priority —
the underlying hole (one missing checksum) is already fixed; this is about preventing the next
one from going unnoticed the same way.
