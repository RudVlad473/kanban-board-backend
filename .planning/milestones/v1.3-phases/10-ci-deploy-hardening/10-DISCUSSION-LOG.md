# Phase 10: CI & Deploy Hardening - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-19
**Phase:** 10-ci-deploy-hardening
**Areas discussed:** Todo fold-in, README shape, Digest-pinning scope, TruffleHog gating philosophy, Gitleaks worktree fix scope

---

## Todo fold-in

| Item | Selected |
|------|----------|
| NVD_API_KEY CI bug | ✓ folded |
| Gradle dependency verification metadata | ✓ folded |
| Gradle wrapper integrity validation in CI | ✓ folded |
| None (keep to 8 HARDEN-* only) | |

**User's choice:** Folded all three.

---

## README shape (HARDEN-08)

| Question | Options | Selected |
|----------|---------|----------|
| README approach | Prominent highlights + links / Reverse task 21 (expand README itself) / You decide | Free text: "somewhere in the middle, readme must be very descriptive, but also structured and if there are diagrams - it must link to them in the docs" |
| Diagrams | Embed 1 top-level diagram / Keep all linked / You decide | Embed 1 top-level diagram |
| Priority lead | Production reality + CI/CD / Testing & quality-gate depth / You decide | Production reality + CI/CD pipeline |
| Rationale inline | Short inline callouts only / Skip rationale / You decide | Short inline callouts only |

**Notes:** User's free-text answer to the first question landed as a middle ground between the
two structured options — more descriptive/structured than the current trim, not a full reversal
of task 21's split, with diagrams linked to docs rather than embedded en masse. Reconciled with
the diagrams answer (embed exactly one) as: one top-level diagram embedded, rest linked.

---

## Digest-pinning scope (HARDEN-03)

| Question | Options | Selected |
|----------|---------|----------|
| Pin scope | Third-party only / Repo-wide / You decide | Third-party only |
| Pin format | SHA + version comment / SHA only | SHA + version comment |
| Dependabot fit | Verify during planning / Not a concern | Verify during planning |

**User's choice:** Third-party only (`appleboy/scp-action`, `appleboy/ssh-action`), documented
risk acceptance for first-party actions; pin format matches `secret-scan.yml`'s existing gitleaks
precedent; planning must confirm Dependabot's `github-actions` ecosystem composes correctly with
digest pins.

---

## TruffleHog gating philosophy (HARDEN-02)

| Question | Options | Selected |
|----------|---------|----------|
| Gating | Hard-gate / Report-only / You decide | Hard-gate |
| Cadence | Every push/PR / Scheduled (weekly) | Every push/PR |
| Network risk (CI-only) | No — CI-only is the safe context / Flag for research | No — CI-only is the safe context |

**User's choice:** Hard-gate, same trigger as gitleaks, CI-only placement confirmed safe with no
further research needed.

---

## Gitleaks worktree fix scope (HARDEN-05)

**Note:** The user rejected the AskUserQuestion tool call for this area and said "let's go with
recommended solution" instead of answering interactively. Recommended options were taken directly:

| Question | Recommended option taken |
|----------|---------------------------|
| Fix scope | Real code fix this phase |
| Fix approach | Detect + stdin-mode fallback |

**User's choice:** Real code fix in `.githooks/pre-commit` this phase, using detect-and-fall-back-
to-stdin-mode when the worktree's common ancestor isn't a clean mountable subtree.

---

## Claude's Discretion

- Exact README section ordering/wording beyond "lead with production reality"
- Which single diagram from `docs/diagrams/` is the embedded top-level one
- Whether the digest-pinning risk-acceptance comment is inline per-line or one block comment
- Exact CI job/step placement for the two folded Gradle supply-chain todos

## Deferred Ideas

None — discussion stayed within phase scope.
