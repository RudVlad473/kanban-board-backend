---
created: 2026-08-20T00:00:00.000Z
title: "No password-change capability exists anywhere in the API"
area: security
severity: moderate
files:
  - src/main/java/com/vrudenko/kanban_board/controller/UserController.java
  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
  - src/main/java/com/vrudenko/kanban_board/service/UserService.java
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V2.1.5, V2.1.6).

`UserController` exposes only `GET`/`PUT` theme preference. `AuthenticationController` exposes
only signin/signup. `UserService` has no `changePassword`/`updatePassword` method. Once a user's
account is created, its password can never be changed through this API.

## Solution

Add a change-password endpoint requiring current-password re-verification
(`passwordEncoder.matches`) before re-encoding the new one, per V2.1.6's re-auth-before-change
requirement. Add controller and service tests. Note the shared re-auth plumbing this creates is
reusable by the session-revocation/destructive-action-reauth todo
(`2026-08-20-no-session-revocation-or-reauth-before-destructive-act.md`).
