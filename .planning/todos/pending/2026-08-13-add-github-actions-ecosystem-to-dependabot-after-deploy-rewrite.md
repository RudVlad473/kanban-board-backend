---
created: 2026-08-13T20:05:00.000Z
title: Add package-ecosystem "github-actions" to dependabot.yml once deploy.yml settles
area: tooling
severity: minor
resolves_phase: 10
files:
  - .github/dependabot.yml
---

## Problem

`.github/dependabot.yml` (quick task 260813-q1i, Task 5) configures only
`package-ecosystem: "gradle"`. GitHub Actions workflow actions (`actions/checkout`,
`actions/setup-java`, `docker/build-push-action`, etc., pinned by tag across
`deploy.yml` and the new `security-scan.yml`) are not covered by Dependabot
version updates today.

This was deliberately deferred, not overlooked: Phase 5 (Infra Migration,
tracked by `.planning/todos/pending/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md`)
is actively rewriting `deploy.yml`'s deploy target from AWS EC2 to an Oracle
Cloud VM. Unprompted Dependabot PRs bumping action versions in a file mid-rewrite
would create merge noise and conflicts against that in-flight work rather than
help it.

## Solution

Once Phase 5's `deploy.yml` rewrite lands and settles, add a second `updates`
entry to `.github/dependabot.yml`:

```yaml
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "weekly"
    open-pull-requests-limit: 5
```

**Trigger:** after `.planning/todos/pending/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md`
closes (Phase 5's deploy.yml rewrite is done), not before.
