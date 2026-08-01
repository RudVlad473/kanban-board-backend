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

## Solution

1. Verify Spring Boot 3.5.0 and Gradle 8.7 compatibility with JDK 25 first (check release notes / compatibility matrices before touching anything).
2. Update `build.gradle`'s `JavaLanguageVersion.of(21)` → `of(25)`.
3. Update the `Dockerfile` build stage image (`gradle:8.7-jdk21` → the JDK 25 equivalent, if one exists for Gradle 8.7; may need a Gradle bump too) and runtime stage (`eclipse-temurin:21-jre-jammy` → `eclipse-temurin:25-jre-jammy` or current LTS equivalent).
4. Update `.github/workflows/deploy.yml`'s `java-version: '21'` → `'25'`.
5. Re-run the full test suite (144 tests as of Phase 2 of the v1.1 milestone) and `./gradlew spotlessCheck` to confirm no regressions.

Scope this as its own quick task or small phase — not a one-liner given the compatibility verification and full-suite re-run required.
