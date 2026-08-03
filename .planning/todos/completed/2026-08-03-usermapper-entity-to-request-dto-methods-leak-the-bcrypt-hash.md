---
created: 2026-08-03T16:00:00.000Z
title: UserMapper entity-to-request-DTO methods leak the bcrypt hash
area: backend
severity: security
files:
  - src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java
---

## Problem

`UserMapper` declares two entity-to-request-DTO methods, `toSigninRequestDTO(UserEntity)` and
`toSignupRequestDTO(UserEntity)`. Both are uncalled today -- re-verified by grep across `src/`
during quick task `260803-m3i` -- but they are worse than merely dead code.

`UserEntity` implements Spring Security's `UserDetails`, so it exposes `getPassword()`, which
returns the stored bcrypt hash (`UserEntity.getPassword()` is a one-line delegate to
`getPasswordHash()`). Both target DTOs, `SigninRequestDTO` and `SignupRequestDTO`, declare a
`password` property. MapStruct maps by property name and the mapper is configured
`unmappedTargetPolicy = ReportingPolicy.IGNORE`, so these two methods generate
`dto.setPassword(entity.getPassword())` -- placing the stored bcrypt hash into a request DTO
that could be serialized into an HTTP response if either method were ever called from a
controller-facing path.

Latent today: zero callers, so nothing currently leaks. Left in place during `260803-m3i` because
that task's scope was locked to exactly two named methods (the hash-less entity-producing
overloads); this is a distinct finding, discovered while re-verifying that task's callers, not
fixed there.

## Solution

Two options, recorded rather than decided here:

1. Delete both `toSigninRequestDTO(UserEntity)` and `toSignupRequestDTO(UserEntity)` -- same
   disposition quick task `260803-m3i` took with the hash-less entity-producing overloads: remove
   rather than exempt, since neither has a caller.
2. If a caller is ever genuinely wanted (e.g. an admin-facing DTO round-trip), add an explicit
   `@Mapping(target = "password", ignore = true)` on whichever method survives, so the omission is
   a recorded decision rather than left to MapStruct's property-name defaulting.

Whichever is chosen, the decision should be explicit in the mapper (a comment or the
`@Mapping(ignore = true)` itself) rather than silently relying on the methods staying uncalled
forever.
