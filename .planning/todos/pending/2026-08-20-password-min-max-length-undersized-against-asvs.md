---
created: 2026-08-20T00:00:00.000Z
title: "Password minimum/maximum length undersized against ASVS's bar"
area: security
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/constant/ValidationConstants.java
  - src/main/java/com/vrudenko/kanban_board/dto/annotation/Password.java
  - src/main/java/com/vrudenko/kanban_board/dto/user_dto/SignupRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/user_dto/SigninRequestDTO.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V2.1.1, V2.1.2).

`ValidationConstants.MIN_PASSWORD_LENGTH = 8` (ASVS wants >= 12) and
`ValidationConstants.MAX_PASSWORD_LENGTH = 64` (ASVS wants >= 128 permitted) are both enforced via
`Password.java`'s `@Size` constraint, applied through `SignupRequestDTO` and `SigninRequestDTO`.
Both bounds are undersized against ASVS's stated minimum bar for password length policy.

## Solution

Raise both constants (>=12 min, >=128 max). Note `BCryptPasswordEncoder` silently truncates input
beyond 72 bytes, so raising the max above that requires an explicit pre-hash step (e.g. SHA-256 the
raw password before BCrypt) or an equivalent strategy, so a longer password is not silently
weakened by the truncation. Update/add a validation-bounds test covering the new min/max edges.
