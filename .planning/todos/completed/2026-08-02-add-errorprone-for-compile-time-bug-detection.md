---
created: 2026-08-02T13:12:32.049Z
title: Add ErrorProne for compile-time bug detection
area: tooling
severity: minor
files:
  - build.gradle
---

## Problem

Spotless (already configured) only handles formatting/style, not correctness. ErrorProne plugs into `javac` and catches real bug classes (null derefs, misused APIs, common Java pitfalls) at compile time, before tests even run — complementary to, not a replacement for, Spotless.

## Solution

Add the `net.ltgt.errorprone` Gradle plugin plus the `com.google.errorprone:error_prone_core` compiler dependency. Must wait until `build.gradle` is unlocked again (locked for the rest of Phase 3 — an explicit acceptance criterion of the active plan). Before enabling as a hard build gate, run it once against the existing codebase to check for a wave of false positives / pre-existing findings that would need triage rather than surprising every future build.
