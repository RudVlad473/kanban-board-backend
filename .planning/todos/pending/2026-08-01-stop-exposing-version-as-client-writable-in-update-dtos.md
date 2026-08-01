---
created: 2026-08-01T15:36:25.403Z
title: Stop exposing version as client-writable in update DTOs
area: api
severity: major
files:
  - src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
  - src/main/java/com/vrudenko/kanban_board/service/ColumnService.java
  - src/main/java/com/vrudenko/kanban_board/entity/TaskEntity.java
  - src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java
---

## Problem

As part of the optimistic locking work added during Epic 2, `version` was added to `UpdateTaskRequestDTO` and `UpdateColumnRequestDTO` as a request field the client sends on update — used by the service layer to detect stale writes (`@Version` on the entity). The problem: this makes `version` look and behave like a normal client-editable field in the request DTO, when conceptually it should be treated as read-only from the client's perspective — the backend fully owns incrementing it.

Today the field is dual-purpose in the request DTO: it's used only for the stale-check comparison, but nothing in the DTO/contract communicates "this is not something you edit, it's an echo of what you last read." A client could in principle send an arbitrary version value with no distinct signal that this differs from every other field in the same DTO, which are genuinely mutable. This is a contract/API-design smell, not a functional bug — the current stale-write detection itself works (see `1b3f1d7 test(02-01): reject every unsafe move — stale version, cross-board, unowned target` and prior optimistic-lock tests), but the shape of the DTO misrepresents the field's role to API consumers.

Expected flow once fixed: user 1 modifies a task; user 2 has a stale version; user 2 attempts an update and gets a conflict error; user 2 refetches the task (getting the current version transparently, without needing to specify it as an "editable" field); user 2 resubmits the update with new field values; backend auto-increments version server-side and returns the task with the new version. The client should never perceive `version` as something it sets — only as something it must round-trip for the stale-check, ideally without it looking like a normal writable DTO property.

## Solution

TBD — needs a design decision before implementing, e.g.:
- Keep `version` in the update DTO but document/annotate it clearly as "must equal last-read value, not a field you choose" (lightest touch)
- Move the stale-check version out of the request body entirely (e.g. into an `If-Match`/ETag-style header) so the JSON body only contains genuinely mutable fields
- Split into a distinct concept (e.g. `expectedVersion`) so it reads unambiguously as a precondition rather than a field being "set"

Whatever is chosen, response DTOs (`TaskResponseDTO`, `ColumnResponseDTO`) should keep returning the authoritative post-update `version` so the client can use it for the next stale-check round-trip.
