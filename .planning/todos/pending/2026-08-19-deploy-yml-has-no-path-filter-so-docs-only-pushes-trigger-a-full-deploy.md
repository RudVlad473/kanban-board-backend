---
created: 2026-08-19T19:40:00.000Z
title: deploy.yml has no path filter, so docs-only pushes trigger a full production+nonprod deploy
area: ci
severity: minor
files:

  - .github/workflows/deploy.yml

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

`deploy.yml`'s trigger is:

```yaml
on:
  push:
    branches:

      - master

```

No `paths:`/`paths-ignore:` filter. Every push to `master` runs the full job graph — `run-tests`,
`build-and-push-docker-image`, `flyway-verify`(+nonprod), `deploy-to-netcup` (production),
`register-schemas-production`, `deploy-to-nonprod`, `health-check-nonprod`, cleanup jobs — even
when the push touches only documentation or planning files.

**Observed live** during Phase 10's execution (2026-08-19): a docs-only README restructure
(commit `00dd644`, only `README.md` + `.planning/` changed) triggered a complete, successful
production and nonprod redeploy of a byte-identical Docker image. Harmless (green every time
observed across several such pushes this session) but wasteful — real deploy time, Docker Hub
push bandwidth, and VM restart churn for a change with zero runtime effect.

## Recommended approach

Add a `paths-ignore:` filter to `deploy.yml`'s `push:` trigger, excluding pure-documentation
paths (e.g. `docs/**`, `**/*.md`, `.planning/**`). Needs care: `.github/workflows/**` and
`.github/dependabot.yml` should almost certainly stay INCLUDED (a workflow change genuinely
needs a deploy-pipeline run to validate), so a blanket `.github/**` exclusion would be wrong —
scope the ignore list to genuinely inert paths only. Verify by pushing a docs-only change after
the fix and confirming `deploy.yml` does not trigger (or only a lightweight subset does).
