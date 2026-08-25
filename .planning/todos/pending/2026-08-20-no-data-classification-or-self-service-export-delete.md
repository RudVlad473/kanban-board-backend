---
created: 2026-08-20T00:00:00.000Z
title: "No formal data classification; no self-service data export/delete/consent capture"
area: security
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/entity/UserEntity.java
  - src/main/java/com/vrudenko/kanban_board/dto/user_dto/SignupRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/service/UserService.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V1.8.1, V1.8.2, V8.3.2, V8.3.3, V8.3.4,
V8.3.5, V8.3.8).

Point protections exist and are individually sound (BCrypt hashing, `@JsonIgnore` on
`UserEntity`'s password hash field, TLS to the DB) but were never traced to a named
classification/retention policy document. `SignupRequestDTO` has no consent/terms-acceptance
field. `UserService.deleteById` already cascades correctly (via
`boardService.deleteAllByUserId`), but no controller route exposes account deletion or data export
to the user themselves.

## Solution

Two independent, both-worth-doing tracks: (a) write a short data-classification/retention policy
naming what's collected (email, display name, password hash, board/task content) and tying it to
the existing point protections already in place; (b) add self-service `DELETE /api/users/me`
(wired to the existing `UserService.deleteById` cascade) and `GET /api/users/me/export` (a JSON
dump of the user's own boards/columns/tasks/subtasks), plus a consent/terms-acceptance field on
`SignupRequestDTO` if this project has terms to accept.
