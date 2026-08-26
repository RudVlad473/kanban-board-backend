---
created: 2026-08-20T00:00:00.000Z
title: "No documented backup / restore runbook for the production database"
area: infra
severity: minor
files:

  - docs/INFRA_RUNBOOK.md

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V14.1.4).

A grep of `docs/INFRA_RUNBOOK.md` for `backup|restore|recovery|PITR` returns only unrelated hits
(network/Redpanda consumer-group recovery, an iptables note). Neon likely offers point-in-time
recovery by default as a platform feature, but this has never been confirmed or written down as a
tested procedure.

## Solution

Confirm Neon's actual PITR/backup retention window for this project's plan tier directly
(dashboard/docs, don't assume), then write a backup/restore runbook section into
`docs/INFRA_RUNBOOK.md` documenting the confirmed retention window, the exact restore procedure,
and — ideally — a once-executed test restore to a scratch branch proving the procedure actually
works.

## Resolution

Superseded by phase 11 (plan 11-06, 2026-08-26): Neon was replaced by a self-hosted PostgreSQL
instance and its project subsequently deleted, so there is no longer a managed-provider PITR window
to confirm. The underlying concern — no documented backup/restore procedure for the production
database — is now **documented, not resolved**: `docs/INFRA_RUNBOOK.md`'s new "Backups and restore
— current coverage and the documented gap (D-12)" section states plainly that no backup exists at
all, and writes down the manual `pg_dump`/`pg_restore` procedure that would be used, explicitly
marked as written-but-never-executed.

What remains genuinely open, deliberately deferred under D-12 rather than closed here: a scheduled
dump job, off-host storage for the dumps, a retention policy, and at least one executed test
restore. This todo is left in `pending/`, re-scoped in place rather than moved to `completed/` —
the concern it names is not closed, only honestly documented instead of silently unaddressed. A
future todo/plan should pick up the deferred automation work named in that runbook section.
