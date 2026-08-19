---
created: 2026-08-19T08:53:30.000Z
title: security-scan.yml's dependency-check job fails "NVD_API_KEY repository secret is not set" despite the secret existing
area: ci
severity: moderate
resolves_phase: 10
files:
  - .github/workflows/security-scan.yml
---

## Problem

`security-scan.yml`'s `dependency-check` job has failed at its "Verify NVD_API_KEY is
configured" step with `NVD_API_KEY repository secret is not set` on every observed run
since at least the scheduled 2026-08-17T06:25 run (run `32001604789`) — predating Phase 9
entirely — and again on two manually triggered `workflow_dispatch` runs during Phase 9's
09-02 live verification (runs `32234733143`, `32234822829`, both 2026-08-19), all with the
identical error text and step.

`gh secret list --json name,updatedAt` confirms `NVD_API_KEY` exists at repository scope
(`updatedAt: 2026-08-13T17:47:43Z`, unchanged across all these runs, including the Phase 9
repository-secret sweep that deleted 10 unrelated secrets). The job declares no
`environment:` key, so environment-secret-visibility rules do not apply — a repository
secret should resolve unconditionally. `${{ secrets.NVD_API_KEY }}` is nonetheless
rendering as an empty string inside the job.

Confirmed **not** a Phase 9 regression: the identical failure predates the 09-02 secret
sweep by two days, so whatever is wrong was already wrong when the secret was first set on
2026-08-13. Root cause not yet investigated — candidates include a genuine GitHub secret
storage/propagation defect for this specific secret, an invisible whitespace/encoding issue
in the stored value (write-only, cannot be read back to confirm), or an organization-level
secret policy interaction. This project's own `.continue-here.md` anti-pattern table already
documents the mitigation for exactly this class of problem: "Trusting a write-only secret
store without falsifying the claim" — prevention is a temporary `workflow_dispatch` job that
prints `sha256sum` of the secret (never the secret itself) to confirm what is actually
stored, rather than assuming a successful `gh secret set` implies GitHub holds the intended
value.

## Suggested fix

1. Add a temporary diagnostic step (hash-comparison probe, never echo the raw secret) to
   confirm whether `NVD_API_KEY` is actually empty as stored, or resolves empty only inside
   this specific job/workflow context.
2. If the stored value is genuinely empty/corrupted: `gh secret set NVD_API_KEY` again with
   a freshly copied value and re-run to confirm.
3. If the stored value is correct but still resolves empty: investigate whether `dependency-check`'s job-level `permissions: contents: read` (no `secrets: read` equivalent needed under GH's model, but worth double-checking) or an org-level secret policy is the actual blocker.
4. Re-run `security-scan.yml` via `workflow_dispatch` after the fix and confirm a full green
   `dependencyCheckAnalyze` execution, not just the presence check passing.
