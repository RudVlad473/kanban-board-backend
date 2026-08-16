---
created: 2026-08-16T19:15:00.000Z
title: security-scan.yml's Set up Java comment is now stale, and it still carries checkout@v3/setup-java@v4
area: tooling
severity: minor
files:
  - .github/workflows/security-scan.yml
---

## Problem

`.github/workflows/security-scan.yml:60-62` carries this comment on its
`Set up Java` step:

```yaml
# temurin, not deploy.yml's adopt: adopt is a deprecated distribution alias
# (pending todo 260802-rq5, Unit B already tracks fixing deploy.yml's own use of it).
# Diverging here rather than repeating the deprecated value in a brand-new file.
```

Quick task 260816-sv1 (2026-08-16) closed the CI half of Unit B: `deploy.yml`'s
`run-tests` job no longer uses `'adopt'`, it now matches `security-scan.yml`'s
own `'temurin'` choice. The comment's premise -- that this file diverges from
`deploy.yml` -- is no longer true, and its own cross-reference is now inaccurate.

Separately, `security-scan.yml` still uses `actions/checkout@v3` (line 58) and
`actions/setup-java@v4` (line 64), both bumped in `deploy.yml` by the same task
(`checkout@v5`, `setup-java@v5`) but deliberately left untouched here per an
operator decision at 260816-sv1's Task 2 checkpoint (option C1: "leave
`security-scan.yml` untouched, file a todo" -- chosen specifically to keep that
task's blast radius to one workflow file, since `security-scan.yml` is an
NVD-throttled, long-running job whose own failure modes the operator did not
want entangled with the deprecation-warning fix).

## Solution

1. Correct or remove the stale comment at lines 60-62 (the divergence it
   describes no longer exists).
2. Bump `actions/checkout@v3` -> `@v5` and `actions/setup-java@v4` -> `@v5` in
   this file, matching the versions `deploy.yml` now uses (verify live via a
   `workflow_dispatch` run rather than waiting for the weekly schedule, since
   this workflow only fires on `workflow_dispatch` or a Monday cron).
3. Re-verify `NVD_API_KEY`-gated `dependencyCheckAnalyze` still runs clean
   after the bump (should be unaffected -- the bump touches only `checkout`/
   `setup-java`, not the dependency-check plugin or its cache step).

**Trigger:** any time after this todo is picked up; not gating any current phase.
