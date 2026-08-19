---
created: 2026-08-16T12:13:00.000Z
resolved: 2026-08-19
resolves_phase: 10
title: Add a TruffleHog live-credential verification pass in CI
area: security
severity: minor
files:
  - .github/workflows/secret-scan.yml
---

## Problem

Quick task 260816-hn1 wired `gitleaks` as the secret scanner at both gates (pre-commit hook,
CI full-history scan). Gitleaks is regex/entropy-only — it detects credential *shapes*, not
whether a matched value is a currently-live, exploitable credential. A rotated-and-dead key
still reports as a finding; a real, live key that happens to slip past the regex set would not.

TruffleHog was evaluated as the primary scanner during that task's Decision 1 and rejected for
that role specifically because its verification step makes a network call per candidate finding
to the provider (AWS, GitHub, etc.) to confirm liveness — unacceptable in a pre-commit hook
(latency, and it phones out from a developer machine holding real credentials), and it collapses
the "is this genuinely a leak worth panicking about" question in a way regex-only gitleaks
cannot.

## Recommended approach

Add TruffleHog as a **second, CI-only** pass in `.github/workflows/secret-scan.yml` (or a
sibling workflow), running only in CI where the network-call cost is acceptable and there is no
developer-machine credential exposure concern. Its job: take gitleaks' findings (or run
independently) and report which, if any, are *verified live* — collapsing false positives in a
way regex alone cannot, and giving a clear rotation-priority signal if a real leak is ever found.

Not scoped as part of 260816-hn1 — that task's blast radius was "make the two existing gates
real," not "add a second scanner." This is worth doing as a follow-up, not urgent, since the
primary gate (gitleaks, hard-gated at both hook and CI) already closes the main gap.

## Resolution

Closed by **Phase 10, Plan 02**. A hard-gated `verified-credential-scan` job added to
`secret-scan.yml`, running a digest-pinned TruffleHog pass
(`ghcr.io/trufflesecurity/trufflehog:3.97.0@sha256:ff4c95e9df7d645daf2140e3ca1039031c63106268d5fbb25feb43ceca1bcc33`)
scoped to the pushed/PR'd commit range, never full history. Findings are scrubbed through a jq
field-allowlist before upload (TruffleHog ships no `--redact` equivalent, so the raw stream —
which by construction carries the matched value of any live finding — is never printed or
uploaded).

Verified live, not merely locally: PR #6 (throwaway branch, deleted after) committed a
synthetic, never-issued AWS-style credential pair. Result confirmed the exact asymmetry this
todo exists to deliver — `secret-scan` (gitleaks, pattern-match) failed the check, while
`verified-credential-scan` (TruffleHog, verified-only) passed, because the key was never live.
Confirmed zero leakage of the fake credential in both the job log and both uploaded artifacts
(`trufflehog-report`, `gitleaks-report`).
