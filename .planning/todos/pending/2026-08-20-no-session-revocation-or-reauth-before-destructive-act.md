---
created: 2026-08-20T00:00:00.000Z
title: "No self-service session revocation, and no re-authentication before destructive actions"
area: security
severity: moderate
files:

  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
  - src/main/java/com/vrudenko/kanban_board/controller/BoardController.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V3.3.4, V3.7.1).

No controller exposes a list-sessions or revoke-session/revoke-all endpoint despite
`SecurityConfiguration`'s `MAX_CONCURRENT_SESSIONS = 2` explicitly designing for multi-session use.
Separately, `BoardController`'s cascading delete (removes all columns/tasks/subtasks) is gated by
`@PreAuthorize("isAuthenticated()")` only, identical to a read — no step-up/re-auth check for
irreversible actions.

## Solution

Two independent fixes: (a) add a self-service `GET /api/users/me/sessions` (list via the existing
`SpringSessionBackedSessionRegistry`) and a revoke-one/revoke-all endpoint; (b) require a re-auth
step (reusing the change-password verification plumbing from
`2026-08-20-no-password-change-capability-exists-anywhere-in-api.md`) before `BoardController`'s
cascading delete executes.
