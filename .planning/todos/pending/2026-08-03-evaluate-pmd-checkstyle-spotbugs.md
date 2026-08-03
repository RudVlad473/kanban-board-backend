---
created: 2026-08-03T15:55:00.346Z
title: Evaluate PMD/Checkstyle/SpotBugs
area: tooling
severity: minor
files:
  - build.gradle
---

## Problem

While reviewing the repo's lint/style stack, PMD, Checkstyle, and SpotBugs were considered as additional static analysis tools. This is Tier 3 (low value, explicitly deprioritized) of that backlog review: these tools likely overlap heavily with what's already enforced — Error Prone covers bug detection, ArchUnit's `LayeringArchTest` covers architecture/layering rules, and `docs/CODE_STYLE.md` covers judgement-level style Spotless can't check. Adding any of the three on top would likely produce redundant noise rather than new coverage.

## Solution

Do not add these proactively. Only revisit if a concrete gap surfaces in the Error Prone / ArchUnit / CODE_STYLE.md trio that one of these tools would specifically close — and if so, scope it to that gap rather than adopting the tool wholesale.
