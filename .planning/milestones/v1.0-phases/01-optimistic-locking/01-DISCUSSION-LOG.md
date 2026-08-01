# Phase 1: Optimistic Locking - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-01
**Phase:** 1-Optimistic Locking
**Areas discussed:** Version exposure in API, What triggers a conflict check, 409 response body shape, Manual DDL timing

---

## Version exposure in API

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, expose version | Client receives version on GET/update responses and can echo it back | ✓ |
| No, keep internal only | Version never appears in API responses | |
| You decide | Claude picks based on flat-DTO conventions | |

**User's choice:** Yes, expose version.

**Follow-up:** Since a typical update request re-loads the entity fresh in the same transaction, Hibernate's automatic `@Version` check alone won't catch a stale-read conflict — should update requests require a client-supplied `version` checked against the DB?

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, required on update | UpdateTaskRequestDTO/UpdateColumnRequestDTO include a required `version`; server compares before applying | ✓ |
| No, rely on JPA's automatic check only | Simpler, but doesn't really solve the stated concurrent-edit problem | |

**User's choice:** Yes, required on update.

**Follow-up:** Should `version` be on ALL response DTOs, or only single-item GET + update response?

| Option | Description | Selected |
|--------|-------------|----------|
| All response DTOs | Consistent contract, no extra GET needed before editing | ✓ |
| Only single-item GET + update response | Smaller touch surface | |

**User's choice:** All response DTOs.

**Notes:** This area turned out to be the central design decision for the whole phase — without client-supplied version checking on update, `@Version` alone would not catch the "two clients drag the same task concurrently" scenario the epic spec describes, since this codebase's update flow loads-then-saves fresh within one transaction.

---

## What triggers a conflict check

| Option | Description | Selected |
|--------|-------------|----------|
| All updates | @Version bumps/checked on any save, regardless of field | ✓ |
| Position/reorder only | Only reorder/move operations carry a version check | |

**User's choice:** All updates.

**Notes:** Confirmed via grep that no `position`/`order` field exists anywhere in the entity layer — the epic's "drag-and-drop reorder" framing is a motivating scenario, not a literal existing feature.

**Follow-up:** `ColumnController` has no PUT/update endpoint today — how should ColumnEntity's `@Version` be made reachable/testable?

| Option | Description | Selected |
|--------|-------------|----------|
| Add a column update endpoint | New PUT /boards/{boardId}/columns/{columnId} + UpdateColumnRequestDTO, matching Task/Board/Subtask pattern | ✓ |
| @Version on Column only, no new endpoint | Field added defensively; E2E test covers Task only | |

**User's choice:** Add a column update endpoint.

**Notes:** ColumnEntity has only a `name` field besides its base/relations, so the new endpoint's request DTO is minimal.

---

## 409 response body shape

| Option | Description | Selected |
|--------|-------------|----------|
| Message + resource id/type | Structured-ish, but no existing precedent in this codebase | initially selected, then revised |
| Message + current entity state | More payload, only useful for a future frontend | |
| Generic message only | Least work | |

**User's choice (initial):** Message + resource id/type.

**Claude flagged:** every existing `GlobalExceptionHandler` handler returns a plain `ResponseEntity<String>` — no structured error DTO exists anywhere in this codebase. Asked whether to embed id/type in the message text (staying with plain String) or introduce a new structured DTO (precedent-setting).

**User's revised answer (free text):** "let's just return message without the id, since during update id isn't changed, only mutable properties are" — plain message string only, no id/type at all, since the client already knows the id from the request URL.

**Final confirmed choice:** Plain message string only, e.g. "Task was modified by another request, please refetch."

---

## Manual DDL timing

| Option | Description | Selected |
|--------|-------------|----------|
| Phase delivers a ready-to-run SQL script | .sql file/documented command as a deliverable | ✓ |
| Just document the command, no script file | Command lives in docs/commit message only | |

**User's choice (initial, free text):** "we are going to do flyway introduction after this phase so let's just tag this in 03 epic so we dont miss it" — wanted to defer the actual DDL application to Epic 3 (Flyway).

**Claude flagged:** this repo auto-deploys to production EC2 on every push to master (GitHub Actions). If code with `@Version` fields merges before the DB column exists, Hibernate breaks in production on the next Task/Column request.

| Option | Description | Selected |
|--------|-------------|----------|
| Manual ALTER TABLE right before merging this phase | Keeps prod working now; Flyway migration-management still lands in Epic 3 | ✓ |
| Accept the gap, tag for Epic 3 | Production breaks until Epic 3 lands | |

**User's final choice:** Manual ALTER TABLE right before merging this phase.

**Notes:** Long-term migration tooling (Flyway) still happens in Epic 3 as originally planned — this is a one-off bridge step scoped to this phase only, not a replacement for that later work.

---

## Claude's Discretion

- Exact DDL script format/location within `docs/plans/backend-modernization/`.
- Exact wording of the 409 message text (plain string, no embedded id/type).
- How the bulk-delete/`@Version`-bypass tradeoff gets documented (code comment vs. STATUS.md note) — must be documented somewhere.
- Exact Lombok exclusion mechanism for `version` on `ColumnEntity`/`TaskEntity` equals/hashCode.

## Deferred Ideas

- `GET /boards/{boardId}/full` — already tracked as v2 in REQUIREMENTS.md, not re-discussed.
- Flyway migration tooling — Epic 3, explicitly out of this phase's scope; flag again when Epic 3 starts.
