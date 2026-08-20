---
created: 2026-08-20T00:00:00.000Z
title: "No notification on auth-detail changes (credential reset, new-device login)"
area: security
severity: minor
files:
  - build.gradle
  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V2.2.3).

No SMTP client, mail library, or notification dispatch exists anywhere in `src/main` — `build.gradle`
carries no mail-starter dependency. This is downstream of having no email infrastructure at all
yet, not a narrow oversight.

## Solution

Explicitly note this is blocked on building email/notification infrastructure first; do not attempt
a partial implementation now. Once email infra exists (e.g. `spring-boot-starter-mail` plus a
transactional provider), wire notifications on credential-reset and new-session/new-device events.
