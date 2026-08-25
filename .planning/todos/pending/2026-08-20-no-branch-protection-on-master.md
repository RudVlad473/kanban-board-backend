---
created: 2026-08-20T00:00:00.000Z
title: "No branch protection on master"
area: ci
severity: moderate
files:

  - .github/workflows/deploy.yml

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V1.10.1).

`gh api repos/RudVlad473/kanban-board-backend/branches/master/protection` returns live HTTP 404
(confirmed 2026-08-20) — no required reviews, no required status checks, no force-push/
history-rewrite restriction on the branch `deploy.yml` deploys straight from.

## Solution

Enable GitHub branch protection on `master` (require PR review and/or the existing test/build/
security-scan workflows as required status checks at minimum; consider restricting force-push and
branch deletion). This is a GitHub repository setting, not a code change — cite the `deploy.yml`
trust boundary it protects (unreviewed pushes currently flow straight to `deploy-to-netcup`).
