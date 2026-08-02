---
created: 2026-08-02T13:15:00.000Z
title: Auto-configure git core.hooksPath so the pre-commit hook needs no manual per-clone step
area: tooling
severity: minor
files:
  - .githooks/pre-commit
  - build.gradle
---

## Problem

`.githooks/pre-commit` (spotlessApply + re-stage on commit) is now committed and wired up for this checkout via `git config core.hooksPath .githooks`, but that `git config` command is still a manual, undocumented one-time step every future clone/contributor has to know to run themselves. Per docs/CODE_STYLE.md rule 8 (test/dev setup must be fully automated, no manual developer action), this should self-configure instead.

## Solution

Once `build.gradle` is unlocked again (locked for the rest of Phase 3), add a small Gradle mechanism that runs `git config core.hooksPath .githooks` automatically the first time any `./gradlew` command is invoked in a fresh checkout (e.g. top-level build-script code that checks the current value and sets it if unset/wrong) — no README instruction, no manual step for a new contributor.
