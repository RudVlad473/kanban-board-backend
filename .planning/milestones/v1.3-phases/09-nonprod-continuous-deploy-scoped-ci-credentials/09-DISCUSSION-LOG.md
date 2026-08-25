# Phase 9: Nonprod Continuous Deploy & Scoped CI Credentials - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-18
**Phase:** 9-Nonprod Continuous Deploy & Scoped CI Credentials
**Areas discussed:** SSH credential isolation, Environment approval gates, Nonprod image retention

---

## SSH Credential Isolation

| Option | Description | Selected |
|--------|-------------|----------|
| Separate restricted deploy identity | New Linux user + SSH key on the VM, confined to `/opt/deploy/kanban-board-nonprod/` only. Matches Phase 8's "provably isolated at every layer" bar. | ✓ |
| New SSH key, same deploy user | A second secret but still the same unrestricted `deploy` Linux account — isolation only at the GitHub-secrets layer. | |
| Reuse existing NETCUP_SSH_KEY | Zero new VM setup; grants full access to both directories regardless of which job reads the secret. | |

**User's choice:** Separate restricted deploy identity.

| Option | Description | Selected |
|--------|-------------|----------|
| New Unix user, standard file permissions | Own home/authorized_keys, no read/write on prod's directory — mirrors how `deploy` itself was set up. | ✓ |
| Hardened forced-command SSH key | Restricts the key to a fixed command set on top of the separate user — stronger, but adds real complexity and needs research into the exact commands required. | |

**User's choice:** New Unix user, standard file permissions.
**Notes:** CI-05's schema-registration step for nonprod uses this same new nonprod-scoped identity, since it reaches the broker over the same SSH path as the deploy job.

---

## Environment Approval Gates

| Option | Description | Selected |
|--------|-------------|----------|
| Both fully unattended | No required reviewers or wait timer on either environment — matches the project's existing always-on continuous deploy posture. | ✓ |
| Production requires approval, staging doesn't | Nonprod deploys automatically; production pauses for a manual click. | |
| Both require approval | Turns CI/CD into a manually-triggered pipeline. | |

**User's choice:** Both fully unattended.
**Notes:** GitHub Environments exist here purely as CI-02's secret-scoping boundary, not as a release gate.

---

## Nonprod Image Retention

| Option | Description | Selected |
|--------|-------------|----------|
| Add an equivalent cleanup job now | Mirror production's `cleanup-old-images` job against nonprod's own repo. | ✓ |
| Defer — no cleanup job this phase | Nonprod's repo accumulates tags indefinitely for now. | |

**User's choice:** Add an equivalent cleanup job now.

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, mirror both cleanup jobs | Full parity with production's retention behavior (both success-path and failure-path sweeps). | ✓ |
| Success-path sweep only | Skips the failure-path `cleanup-unused-image` equivalent. | |

**User's choice:** Yes, mirror both cleanup jobs.

---

## Claude's Discretion

- Exact naming for the new nonprod-scoped Linux user, its SSH secret name(s) in GitHub, and the nonprod Docker Hub repository name.
- Exact health-check retry/timeout parameters for CI-04 (mirrors the existing production pattern).
- Job/step ordering details for CI-05 — already fully specified by the requirement text itself.
- Whether the new GitHub Environments are created via `gh` CLI or the GitHub web UI.

## Deferred Ideas

None raised beyond what ROADMAP.md already scopes to Phase 10. `cross_reference_todos` matched 24 pending todos against Phase 9's keywords; the highest-scoring group (dependabot, TruffleHog, digest-pinning, gradle cache, gitleaks worktree fix, security-scan.yml cleanup, cookie Secure flag, README expansion) are real in-scope v1.3 work but already traceability-mapped to Phase 10 in REQUIREMENTS.md — none were presented as fold candidates for this phase.
