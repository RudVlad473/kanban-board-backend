---
created: 2026-08-01T20:51:52.312Z
title: Bump Java version from 21 to 25 (current LTS)
area: tooling
severity: minor
files:
  - build.gradle
  - Dockerfile
  - .github/workflows/deploy.yml
---

## Problem

The project is pinned to Java 21 in three places: `build.gradle`'s toolchain (`JavaLanguageVersion.of(21)`), both `Dockerfile` stages (`gradle:8.7-jdk21` build stage, `eclipse-temurin:21-jre-jammy` runtime stage), and `.github/workflows/deploy.yml`'s `java-version: '21'`. Java 21 LTS remains fully supported until roughly 2028, so this isn't urgent — but staying on the current LTS (25, as of this writing) is worth doing proactively rather than waiting for another retired-image scare like the `openjdk:21-jdk-slim` Docker Hub removal that broke the production deploy pipeline (fixed separately by repointing to `eclipse-temurin:21-jre-jammy`).

## Research finding (2026-08-02, quick task 260802-rq5) — NOT a quick task as originally scoped

Full research: `.planning/quick/260802-rq5-bump-java-version-from-21-to-25-current-/260802-rq5-RESEARCH.md`.

**Verdict: this is gated behind a Spring Boot major upgrade, not a three-file version bump.**

The blocking chain: Java 25 requires Gradle ≥9.1.0 (both to run Gradle itself and for toolchain support — Gradle 8.11.1, this repo's actual wrapper version, doesn't even reach the Java 24 line). Gradle 9 is in turn blocked by this repo's Spring plugins: Spring Boot 3.5.x's Gradle plugin and `io.spring.dependency-management` both cap at Gradle 8.x. **Spring Boot 4.x is the only supported destination for a Java 25 Gradle build.** On top of that, Lombok 1.18.36 (pinned here) does not support JDK 25 at all (hard blocker, fails in the annotation processor — needs 1.18.40+), and MapStruct 1.5.3 predates JDK 23+ (soft blocker, current stable is 1.6.3).

## Recommended split (from the research)

- **Unit A — dependency modernization, stay on Java 21/Gradle 8.11.1** (genuine quick task, LOW risk): bump Lombok → latest 1.18.4x, MapStruct → 1.6.3, Spring Boot 3.5.0 → 3.5.16 (final 3.5 patch). Verify `./gradlew spotlessCheck test` green and that MapStruct still generates `*Impl.java` under `build/generated/`.
- **Unit B — CI/Docker hygiene, still Java 21** (genuine quick task, LOW-MEDIUM risk — touches the live auto-deploy path): `deploy.yml`'s `distribution: 'adopt'` is dead (AdoptOpenJDK was removed from `actions/setup-java`, no JDK 25 ever) → `'temurin'` + bump `setup-java@v4` → `@v5`. Dockerfile build stage `gradle:8.7-jdk21` → `eclipse-temurin:21-jdk-noble` (also kills a phantom Gradle pin — the build stage's baked-in Gradle is never used since `./gradlew` wins). Runtime stage → `21-jre-noble` (Temurin Jammy variants are the higher long-term retirement risk vs. Noble, per this repo's own prior `openjdk:21-jdk-slim` incident).
- **Unit C — the actual Java 25 jump** (milestone-sized, NOT a quick task — route to `/gsd-phase`): Spring Boot 3.5.16 → 4.x (breaking: package relocations, Spring Framework 7, Spring Security major, Spring Kafka major), Gradle wrapper → 9.1.0+, drop/replace `io.spring.dependency-management`, `JavaLanguageVersion.of(21)` → `of(25)`, Docker images → `25-*-noble`, CI → `java-version: '25'`, re-triage ErrorProne findings under the new JDK+Boot, re-verify Testcontainers/Kafka E2E. Also needs a compatibility pass on Testcontainers/ArchUnit/REST Assured/SpringDoc against Boot 4.x (SpringDoc especially tends to need its own major bump alongside a Spring Framework major).

Units A and B were deliberately deferred (not executed) on 2026-08-02 pending an explicit decision on whether/when to take on Unit C — see STATE.md decisions log.

## Update (2026-08-16, quick task 260816-sv1) — Unit B's CI half is done; Dockerfile half still open

Unit B is **half complete**. `deploy.yml`'s `run-tests` job now declares
`actions/setup-java@v5` with `distribution: 'temurin'` (was `@v4` / `'adopt'`),
`java-version` unchanged at `'21'`. `actions/checkout` was also bumped from `@v3`
to `@v5` (not `@v4`, which would still run on the now-also-deprecated Node 20) in
all three jobs that carried the deprecated tag — `run-tests`,
`build-and-push-docker-image`, `flyway-verify` — widened beyond `run-tests` alone
per an operator decision at this task's blocking checkpoint (`all-recommended`:
A1 `checkout@v5` + B1 fix all three occurrences + C1 leave `security-scan.yml`
untouched). Commit `d0206ed`.

Verified live: master run `31966148764` (rerun of the `build-and-push-docker-image`
job after a one-off Gradle-distribution-download network blip, unrelated to this
change — `java.net.SocketException: Connection reset` fetching
`gradle-8.11.1-bin.zip` from `services.gradle.org` inside the Dockerfile's own
`RUN ./gradlew bootJar` step) concluded `success` end to end, including
`build-and-push-docker-image`, `flyway-verify`, and `deploy-to-netcup`. The
`run-tests` job's log carries **zero** of the CI-action deprecation lines
targeted by this task (Node-20 runtime warnings for `checkout`/`setup-java`, the
`setup-java v4` deprecation, the `'adopt'` alias deprecation), down from 15 in
the `31964944867` baseline. Five lines matching a naive `grep -i deprecat` remain
in both the before and after logs, byte-identical: `javac -Xlint:deprecation`
notes that `AvroSchemaRegistrar.java` uses a deprecated Java API — pre-existing,
unrelated to any CI Action, out of this task's scope, not touched. `Run tests`
step result: `BUILD SUCCESSFUL`, `7 actionable tasks: 7 executed`, no `FAILED`
line — same shape as baseline (Gradle's default console output prints no literal
per-test count in either run). Full before/after evidence in
`.planning/quick/260816-sv1-fix-the-github-actions-deprecation-warni/260816-sv1-SUMMARY.md`.

**Still open — Unit B's Dockerfile half:** `gradle:8.7-jdk21` (build stage) →
`eclipse-temurin:21-jdk-noble`, `21-jre-jammy` (runtime stage) → `21-jre-noble`.
Units A and C remain entirely untouched. This todo stays in `pending/`.

## Original Solution (superseded by the split above — kept for history)

1. Verify Spring Boot 3.5.0 and Gradle 8.7 compatibility with JDK 25 first (check release notes / compatibility matrices before touching anything).
2. Update `build.gradle`'s `JavaLanguageVersion.of(21)` → `of(25)`.
3. Update the `Dockerfile` build stage image (`gradle:8.7-jdk21` → the JDK 25 equivalent, if one exists for Gradle 8.7; may need a Gradle bump too) and runtime stage (`eclipse-temurin:21-jre-jammy` → `eclipse-temurin:25-jre-jammy` or current LTS equivalent).
4. Update `.github/workflows/deploy.yml`'s `java-version: '21'` → `'25'`.
5. Re-run the full test suite (144 tests as of Phase 2 of the v1.1 milestone) and `./gradlew spotlessCheck` to confirm no regressions.

This turned out to be wrong (see research finding above) — treated Java 25 as reachable via a version bump alone, when it's actually gated behind a Spring Boot major upgrade.
