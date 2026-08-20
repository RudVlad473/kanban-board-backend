completed: 2026-08-20
---
created: 2026-08-16T19:15:00.000Z
title: Digest-pin GitHub Actions -- mutable tags are currently trusted by tag only, inconsistent with this repo's own scanner precedent
area: tooling
severity: minor
resolves_phase: 10
files:

  - .github/workflows/deploy.yml
  - .github/workflows/security-scan.yml

---

## Problem

Every `uses:` reference in this repo's workflow files (`actions/checkout@v5`,
`actions/setup-java@v5`, `docker/setup-buildx-action@v3`,
`docker/build-push-action@v6`, `appleboy/scp-action@v1.0.0`,
`appleboy/ssh-action@v1.2.5`, `actions/cache@v4`, `actions/upload-artifact@v4`,
etc.) is trusted by a **mutable tag** — the tag can be retargeted to a different
commit by the action's publisher at any time, without this repo's knowledge or
review. This repo is internally inconsistent about that risk: its own pinned
secret scanner (`.githooks/pre-commit`, `.github/workflows/secret-scan.yml`)
pins `gitleaks` by tag **plus digest**, specifically to close this exact gap for
one dependency, while every GitHub Action reference in every workflow file
remains tag-only.

Surfaced during quick task 260816-sv1 (bumping `deploy.yml`'s `run-tests` job off
deprecated action versions) — that task re-affirmed trust in the newly-bumped
tags (`checkout@v5`, `setup-java@v5`) rather than widening scope to fix the
underlying inconsistency, which is a repo-wide policy change well outside a
3-line hygiene fix.

## Solution

Decide whether to digest-pin GitHub Actions repo-wide (e.g.
`actions/checkout@<sha>  # v5.x.x`, following the pattern the secret-scan
workflow's `gitleaks` reference already uses) or to explicitly accept
tag-only trust for GitHub-owned first-party actions as a documented,
deliberate risk acceptance (distinct from third-party actions like
`appleboy/scp-action` and `appleboy/ssh-action`, which carry materially higher
publisher-compromise risk and may warrant digest-pinning regardless of what's
decided for first-party actions).

If digest-pinning is chosen: every `uses:` line across `deploy.yml`,
`security-scan.yml`, and any future workflow needs a digest lookup and a
maintenance plan for future version bumps (a digest pin does not auto-track
new releases the way a `@v5` tag does — a bump becomes a two-step lookup-then-edit
each time, not a one-line edit).

**Trigger:** any time after this todo is picked up; not gating any current phase.

## Resolution (2026-08-20)

Delivered by Phase 10 Plan 10-01, decision D-05 (the "option 2" risk-tiered path this todo
itself named): the two `appleboy/*` third-party actions (the ones holding `NETCUP_SSH_KEY`) are
digest-pinned across all six call sites in `deploy.yml`, and first-party actions
(`actions/checkout`, `actions/setup-java`, `gradle/actions/wrapper-validation`, etc.) remain
tag-trusted with an explicit, reasoned risk-acceptance comment near the top of the file. Found
already satisfied while triaging pending todos after Phase 10 closed; moved straight to
completed without further action.
