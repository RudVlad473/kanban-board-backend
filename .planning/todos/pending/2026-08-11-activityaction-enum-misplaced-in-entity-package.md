---
created: 2026-08-11T00:00:00.000Z
title: ActivityAction enum lives in entity/ but is not a JPA entity — relocate to match its actual role
area: backend
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/entity/ActivityAction.java
  - src/main/java/com/vrudenko/kanban_board/entity/ActivityLogEntity.java
  - src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java
  - src/main/java/com/vrudenko/kanban_board/dto/activity_dto/ActivityLogResponseDTO.java
  - src/main/java/com/vrudenko/kanban_board/entity/ThemePreference.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

`ActivityAction.java` is a plain enum (no `@Entity`, no JPA annotations of any kind) sitting in
`src/main/java/com/vrudenko/kanban_board/entity/` — a package CLAUDE.md documents as "Package
structure mirrors domain," dedicated to JPA `@Entity` classes (`BoardEntity`, `ColumnEntity`,
`TaskEntity`, `SubtaskEntity`, `UserEntity`, `ActivityLogEntity`, `BaseEntity`).

Unlike a value type that only backs a single JPA column, `ActivityAction` is used across three
distinct layers:

- `entity/ActivityLogEntity.java` — as a persisted `@Enumerated` column
- `activitylog/ActivityLogConsumer.java` — mapped 1:1 from the publishing Kafka event's class name
- `dto/activity_dto/ActivityLogResponseDTO.java` — the API-facing representation

That breadth is what makes this a real misplacement rather than a judgement call: it's a
cross-layer domain vocabulary type, not an entity-internal implementation detail.

**Related, weaker case found in the same audit — not necessarily the same fix:**
`entity/ThemePreference.java` is also a plain enum in `entity/`, but it only backs one JPA column
(`UserEntity.themePreference`) and isn't referenced outside the persistence layer the way
`ActivityAction` is. Worth a decision on whether it moves too, or whether "backs exactly one JPA
column" is a legitimate reason to leave a narrow enum in `entity/`.

## Solution

Not scoped here — needs a placement decision, not a default assumption. Candidates worth
considering: a new top-level package (e.g. `activity/` or `enums/`, matching the existing
`event/` package's precedent for cross-cutting domain types), or co-locating with
`activitylog/ActivityLogConsumer.java` if `ActivityAction` is judged primarily a consumer-side
mapping concern. Whatever is chosen, apply the same reasoning to `ThemePreference` explicitly
(move it too, or write down why it's exempt) rather than leaving the inconsistency unexamined.
