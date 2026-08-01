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

**Desired approach: `ETag` / `If-Match` headers, not a request-body field.**

Move the stale-check version out of the request body entirely and onto HTTP's own concurrency-control mechanism:
- Server returns `ETag: "<version>"` on responses that carry a Task/Column representation (GET, POST, PUT).
- Client echoes the value back as `If-Match: "<version>"` on update requests.
- Server compares `If-Match` against the current row's version before applying the update; mismatch → `409 CONFLICT` (or `412 PRECONDITION_FAILED`, needs a decision — see below).
- `version` is removed from `UpdateTaskRequestDTO`/`UpdateColumnRequestDTO` entirely — the request body only contains genuinely mutable fields.

This fully resolves the encapsulation complaint: `version` stops being presented as a domain field at all and becomes transport-level metadata, matching what it actually is (Hibernate bookkeeping, not resource data).

Rejected alternatives (kept for context, not pursued):
- Keep `version` in the update DTO but annotate/document it as "must equal last-read value, not a field you choose" — lightest touch, but doesn't fix the encapsulation issue, just documents around it.
- Split into a distinct body field (e.g. `expectedVersion`) — clearer than status quo but still a body field pretending to be domain data.

Open questions to resolve during planning:
- `409 CONFLICT` (current behavior) vs. `412 PRECONDITION_FAILED` (more HTTP-idiomatic for `If-Match` mismatches) — needs a decision, may have back-compat implications for existing E2E tests (`TaskLockingE2ETest`, `ColumnLockingE2ETest`) that assert `409`.
- Whether `version` stays in response DTOs (`TaskResponseDTO`, `ColumnResponseDTO`) as a convenience/debugging field in addition to the `ETag` header, or is dropped from the JSON body now that the header is authoritative.
- GET responses need to start setting `ETag` too, not just update responses, so a client's very first read has something to echo back.
