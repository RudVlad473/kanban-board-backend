---
created: 2026-08-20T00:00:00.000Z
title: "No secret pepper on top of BCrypt's own per-hash salt"
area: security
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java
  - src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V2.4.5).

`BeanConfiguration`'s `passwordEncoder` bean takes only an `int strength` parameter
(`security.bcrypt.strength`, default 10) — no secret/pepper value. `UserMapper` calls
`passwordEncoder.encode()` directly with no additional HMAC/KDF step.

## Solution

Add an application-level pepper (HMAC the raw password with a server-side secret before BCrypt
hashing, sourced from an env var such as `PASSWORD_PEPPER`, never committed), following this
project's existing runtime-secret pattern (`docker-compose.prod.yml --env-file`). Note this pepper
needs the same rotation/storage care flagged in
`2026-08-20-no-secrets-vault-for-runtime-prod-secrets-no-rotation.md`.
