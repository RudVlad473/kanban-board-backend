---
created: 2026-08-20T00:00:00.000Z
title: "No breached-password check or strength meter on signup"
area: security
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/dto/annotation/Password.java
  - src/main/java/com/vrudenko/kanban_board/dto/user_dto/SignupRequestDTO.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V2.1.7).

A case-insensitive grep for `breach|pwned|haveibeenpwned|zxcvbn|strength` across `src/main` matches
only unrelated BCrypt-strength-parameter references (`BeanConfiguration`'s
`security.bcrypt.strength`) — no breached-password check or strength meter exists anywhere in the
signup path.

## Solution

Integrate a k-anonymity HaveIBeenPwned range-query check at signup (no full password ever leaves
the server) or a local strength estimator if an offline approach is preferred for this VPS's
traffic profile. Reject known-breached passwords with a clear validation message. Add a test using
a known-breached test password asserting rejection, and a strong/unique one asserting acceptance.
