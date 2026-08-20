---
quick_id: 260820-iwo
status: complete
completed: 2026-08-20
subsystem: docs/todos
tags: [security, asvs, todos, state]
dependency-graph:
  requires: [.planning/todos/completed/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md]
  provides: [19 new .planning/todos/pending/*.md, ASVS 4.0.3 cross-references on 6 existing todos]
  affects: [.planning/STATE.md Pending Todos section]
key-files:
  created:
    - .planning/todos/pending/2026-08-20-password-min-max-length-undersized-against-asvs.md
    - .planning/todos/pending/2026-08-20-password-composition-regex-blocks-unicode-only-pass.md
    - .planning/todos/pending/2026-08-20-no-password-change-capability-exists-anywhere-in-api.md
    - .planning/todos/pending/2026-08-20-no-breached-password-check-or-strength-meter-on-signup.md
    - .planning/todos/pending/2026-08-20-no-mfa-second-factor-enrollment-path.md
    - .planning/todos/pending/2026-08-20-no-notification-on-auth-detail-changes-credential-rese.md
    - .planning/todos/pending/2026-08-20-no-secret-pepper-on-top-of-bcrypt-salt.md
    - .planning/todos/pending/2026-08-20-no-security-event-logging-on-auth-and-access-control.md
    - .planning/todos/pending/2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md
    - .planning/todos/pending/2026-08-20-no-session-revocation-or-reauth-before-destructive-act.md
    - .planning/todos/pending/2026-08-20-dockerfile-runs-as-root-no-user-directive.md
    - .planning/todos/pending/2026-08-20-no-branch-protection-on-master.md
    - .planning/todos/pending/2026-08-20-no-prod-post-deploy-health-verification.md
    - .planning/todos/pending/2026-08-20-internal-kafka-hop-has-no-sasl-auth-or-tls.md
    - .planning/todos/pending/2026-08-20-no-secrets-vault-for-runtime-prod-secrets-no-rotation.md
    - .planning/todos/pending/2026-08-20-swagger-openapi-docs-reachable-in-prod-no-profile-gate.md
    - .planning/todos/pending/2026-08-20-no-content-type-validation-on-rest-endpoints.md
    - .planning/todos/pending/2026-08-20-no-data-classification-or-self-service-export-delete.md
    - .planning/todos/pending/2026-08-20-no-documented-backup-restore-runbook-for-prod-db.md
  modified:
    - .planning/todos/pending/2026-08-20-add-rate-limiting-to-signin-to-bound-brute-force-volume.md
    - .planning/todos/pending/2026-08-20-idor-same-user-chain-consistency-boardid-columnid-not-c.md
    - .planning/todos/pending/2026-08-20-verify-csrf-defense-with-a-real-cross-origin-rejection-t.md
    - .planning/todos/pending/2026-08-20-security-response-headers-csp-and-unreliable-hsts-behind.md
    - .planning/todos/pending/2026-08-18-500-problemdetail-detail-carries-raw-exception-message.md
    - .planning/todos/pending/2026-08-13-ratchet-failbuildoncvss-after-a-real-dependency-check-baseline.md
    - .planning/STATE.md
decisions:
  - Split file-19 work into two theme-coherent batches (auth/session/logging vs. infra/deploy/config) rather than one bulk task, keeping each task's file count bounded and independently verifiable.
  - Bundled the 7 minor-severity new todos into one summary bullet in STATE.md, mirroring the file's existing bundling convention, rather than letting the representative list grow unbounded per audit round.
actuals:
  tokens: 10948
  tasks: 4
  commits: 4
status: complete
---

# Quick Task 260820-iwo: File 19 new pending todos and enrich 6 already-filed todos from the ASVS 4.0.3 Level 2 audit

**Documentation-only follow-up to a 33-agent ASVS 4.0.3 Level 2 audit — 6 existing pending todos got an "ASVS 4.0.3 cross-reference" section (two of them also got in-place content corrections/broadening), 19 new pending todos were filed one-per-finding, and STATE.md's Pending Todos section was updated to reflect the new count and representative entries.**

## What was done

1. **Enriched 6 already-filed pending todos** (commit `70b04b0`) with a new `## ASVS 4.0.3
   cross-reference` section inserted between frontmatter and `## Problem`:
   - `add-rate-limiting-to-signin` — cross-reference note (V2.2.1, V8.1.4, V11.1.4) plus an
     in-place scope broadening of the existing `## Problem`/`## Solution` sections: the guard now
     targets general request-volume abuse across authenticated business endpoints, not only
     `POST /signin`.
   - `idor-same-user-chain-consistency`, `verify-csrf-defense`,
     `500-problemdetail-detail-carries-raw-exception-message`, `ratchet-failbuildoncvss` —
     cross-reference notes only, no scope change (all four independently corroborated by the
     audit, no new information).
   - `security-response-headers-csp-and-unreliable-hsts-behind` — cross-reference note plus two
     content changes to the existing `## Problem` section: a new confirmed finding
     (`Referrer-Policy` unset, V14.4.6) and a clearly-labeled correction (`X-Frame-Options` DOES
     fire by default via Spring Security, V14.4.7 — narrower gap than the title might imply).
2. **Filed 10 new todos** (commit `74f91a0`) covering password policy, MFA, session, and
   auth-visibility gaps: undersized password length bounds (V2.1.1/2), ASCII-only composition
   regex blocking Unicode passwords (V2.1.4/9), no password-change endpoint (V2.1.5/6), no
   breached-password check (V2.1.7), no MFA enrollment (V2.3.2), no auth-detail-change
   notifications (V2.2.3, explicitly blocked on missing email infra), no secret pepper on BCrypt
   (V2.4.5), no security-event logging (V1.2.3, V7.1.x, V7.2.x), no remote log shipping/alerting
   (V1.7.x, V7.3.x, V11.1.x — pointed at the existing unimplemented Prometheus+Grafana backlog
   item rather than proposing a redundant initiative), and no session revocation / re-auth before
   destructive actions (V3.3.4, V3.7.1).
3. **Filed 9 new todos** (commit `7d8adac`) covering infra, deployment, config, and API-hygiene
   gaps: Dockerfile runs as root (V1.2.1/14.5), no branch protection on master (V1.10.1, confirmed
   live via `gh api` returning 404), no prod post-deploy health check (V1.14.4), internal Kafka hop
   has no SASL/TLS (V1.2.2/9.2.2/1.9.1, Docker-internal-only networking cited as compensating
   control), no secrets vault for runtime prod secrets (V1.6.x/6.4.1), Swagger/OpenAPI docs
   reachable in prod (V14.1.3/14.2.2), no Content-Type validation on REST endpoints (V13.1.5/2.5),
   no data classification or self-service export/delete (V1.8.x/8.3.x), and no documented
   backup/restore runbook (V14.1.4).
4. **Updated STATE.md's Pending Todos section** (commit `cdaf0b9`): item-count pointer refreshed
   from `~26 items` to `~45 items`; 12 representative bullets appended individually for the
   moderate-severity new todos; the 7 minor-severity new todos bundled into one summary bullet
   matching the section's existing bundling convention; a new
   `**Update (2026-08-20, later still — ASVS 4.0.3 Level 2 audit):**` paragraph appended
   summarizing the audit's cross-reference and new-filing outcome.

All content referencing credentials-adjacent concepts (BCrypt pepper, secrets vault) used only
concept/env-var-name language (`PASSWORD_PEPPER`, `DB_PASS`) per this repo's gitleaks pre-commit
gate — no literal secret-shaped value was written anywhere.

## Verification

- All 6 enriched files: `## ASVS 4.0.3 cross-reference` appears exactly once, positioned before
  `## Problem` — confirmed via grep across all 6 files.
- `add-rate-limiting-to-signin`: broadened Problem/Solution sections confirmed present (scope no
  longer reads as `/signin`-only without an accompanying broader-scope mention).
- `security-response-headers-csp-and-unreliable-hsts-behind`: both `V14.4.6` and `V14.4.7` present,
  text distinguishes "confirmed absent" (Referrer-Policy) from "fires by default" (X-Frame-Options).
- All 19 new files exist under `.planning/todos/pending/` with the exact filenames specified in the
  plan; each has non-empty `## Problem` and `## Solution` sections and a `files:` frontmatter list.
- Severity values verified via grep against the plan's spec: 12 `moderate` / 7 `minor` across the
  19 new files, matching exactly (no item inflated or deflated).
- `git diff .planning/STATE.md` confined entirely to the `### Pending Todos` section body; `~45
  items` string present; all 19 new filenames appear at least once (12 individually-cited plus the
  bundled minor-summary line).
- `ls .planning/todos/pending | wc -l` = 45 (26 pre-existing + 19 new), consistent with the updated
  STATE.md count.
- gitleaks pre-commit scan and `spotlessCheck`/`fastTest` passed clean on every one of the 4 commits
  (docs-only diffs, `src/main`/`src/test` untouched).

## Commits

Four atomic commits, one per plan task:
`70b04b0` (enrich 6 existing todos), `74f91a0` (file 10 new todos — auth/session/logging),
`7d8adac` (file 9 new todos — infra/deploy/config), `cdaf0b9` (STATE.md Pending Todos update).

## Deviations from Plan

None — plan executed exactly as written. Two pre-commit hook runs hit transient Windows-local
issues unrelated to the content changes (a Gradle worker OOM on a cold daemon, and a stale
`fastTest` output-file lock from an earlier timed-out attempt); both were resolved by stopping and
restarting the Gradle daemons (`./gradlew --stop`), not by modifying any task content, and every
commit's `spotlessCheck`/`fastTest` ultimately passed clean.

## Next Phase Readiness

No blockers. This is documentation-only work under `.planning/todos/` and `.planning/STATE.md` —
no `src/main`/`src/test` changes were made or expected. The 19 new todos and 6 enriched
cross-references are now available for future triage/pickup sessions.

## Self-Check: PASSED

- All 4 commit hashes (`70b04b0`, `74f91a0`, `7d8adac`, `cdaf0b9`) confirmed present in `git log`.
- All 19 new todo files confirmed on disk under `.planning/todos/pending/`.
- This SUMMARY.md file confirmed on disk.
