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
