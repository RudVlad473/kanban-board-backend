---
created: 2026-08-03T15:00:00.000Z
title: Remove the hash-less UserMapper overloads and tighten passwordHash nullability
area: backend
severity: security
files:
  - src/main/java/com/vrudenko/kanban_board/mapper/UserMapper.java
  - src/main/java/com/vrudenko/kanban_board/entity/UserEntity.java
  - src/test/java/com/vrudenko/kanban_board/security/UserPersistenceE2ETest.java
---

## Problem

Planning for `260803-l6f` (a test proving signup persists a real bcrypt hash) turned up two
latent ways a user could end up persisted with a null `passwordHash`. Neither is active today,
both are invisible to the type system, and together they are the concrete form of the concern
that prompted that quick task.

**Finding 1 -- a dead, hash-less `fromSignupRequestDTO` overload.** `UserMapper` declares
`fromSignupRequestDTO(SignupRequestDTO)` at line 40, which carries the `@Mapping` that encodes
the password, and `fromSignupRequestDTO(SigninRequestDTO)` at line 29, which carries no such
mapping. `SigninRequestDTO` has a `password` field but `UserEntity` has no `password` setter --
only `passwordHash` -- and the mapper is configured `unmappedTargetPolicy =
ReportingPolicy.IGNORE`, so the line-29 overload silently produces an entity with a null hash. It
has zero callers today (verified by grep across `src/`); `UserService.java:51` resolves to the
line-40 overload because it passes a `SignupRequestDTO`. But the two are one identifier apart and
differ only in a parameter type, so a future edit that changes a variable's declared type, or an
IDE completion picking the wrong overload, silently swaps a hashing mapper for a non-hashing one
with no compile error and no test failure outside `UserPersistenceE2ETest`.
`fromSigninRequestDTO(SigninRequestDTO)` at line 27 is likewise uncalled. Recommend deleting both
unused overloads -- this is the same disposition quick task `260802-q6n` took with the dead,
unverified `SubtaskService.findById(String)`: remove rather than exempt.

**Finding 2 -- nothing stops a null hash reaching the database.** `UserEntity.passwordHash` is
`@Column(nullable = true)` by deliberate choice, its comment citing future non-password auth
methods that do not exist yet. That is a defensible forward-looking decision, but combined with
finding 1 it means a null-hash entity would be accepted by the database rather than rejected at
the write. Such a user could then never authenticate: `UserAuthenticationProvider` would call
`passwordEncoder.matches(plaintext, null)`, which returns false forever, so the account is
created successfully and is permanently unusable -- with no error at creation time pointing at
the cause.

## Solution

**Finding 1:** Delete the unused `fromSignupRequestDTO(SigninRequestDTO)` and
`fromSigninRequestDTO(SigninRequestDTO)` overloads from `UserMapper` once confirmed to still have
zero callers. `UserPersistenceE2ETest` is what would now catch this finding if it ever
activated -- its `SignupPasswordHashPersistence` test reads the persisted `PASSWORD_HASH` back
via raw SQL and would go red the moment signup started resolving to a hash-less overload, so a
future fix (or accidental regression) has a named regression guard to point at.

**Finding 2:** Two options, recorded rather than decided here:

1. Make `UserEntity.passwordHash` non-nullable now and revisit when a second auth method
   actually lands.
2. Keep it nullable and add an application-level invariant asserting that a
   password-authenticated user always has a hash (e.g. a service-layer check before save).

Note that changing nullability needs a production DDL migration alongside the annotation change,
matching the existing `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql` pattern,
since `ddl-auto` is unset in production -- which is a large part of why this does not belong in a
quick task.
