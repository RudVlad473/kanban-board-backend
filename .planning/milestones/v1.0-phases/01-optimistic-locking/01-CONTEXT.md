# Phase 1: Optimistic Locking - Context

**Gathered:** 2026-08-01
**Status:** Ready for planning

<domain>
## Phase Boundary

Retrofit optimistic locking onto `TaskEntity` and `ColumnEntity` so a conflicting concurrent update returns HTTP 409 instead of silently overwriting. Covers: `@Version` fields, client-supplied version checking on update, the existing 423→409 exception-handler bug fix, an E2E concurrent-update test, a Lombok equals/hashCode audit, and the real-schema DDL required to support it. Does not cover the `GET /full` endpoint (v2, separate phase) or any of Epics 1/3–7 of the modernization plan.

</domain>

<decisions>
## Implementation Decisions

### Version exposure in API
- **D-01:** `version` field is exposed on Task and Column response DTOs (`TaskResponseDTO`, `ColumnResponseDTO`) — surfaced on ALL response paths (single-item GET, list endpoints, create/update responses), not just update-adjacent ones. Gives a consistent contract so a client never needs an extra GET just to learn the current version.
- **D-02:** Update requests require a client-supplied `version` field (`UpdateTaskRequestDTO`, `UpdateColumnRequestDTO`). The server compares it against the current DB row's version before applying the update. — **Reversibility:** costly — this is the actual mechanism that makes optimistic locking catch the "two clients read-then-write" scenario; relying on Hibernate's automatic per-transaction `@Version` check alone would NOT catch it, since this codebase's update flow loads-then-saves fresh within one transaction. Removing this later means the feature stops actually protecting against stale-read conflicts, even though `@Version` is still present.

### What triggers a conflict check
- **D-03:** `@Version` protection applies to ALL field updates on Task/Column (not scoped narrowly to a "position" concept) — confirmed via grep that no `position`/`order` field exists anywhere in the entity layer today. The epic spec's "drag-and-drop reorder" framing is the motivating scenario, not a literal existing feature; this phase does not add position/reordering.
- **D-04:** `ColumnController` currently has NO update endpoint at all (only `GET` list and `POST` add-task) — unlike Task/Board/Subtask, which all have `PUT .../updateById`. This phase adds `PUT /boards/{boardId}/columns/{columnId}` + `UpdateColumnRequestDTO` (single `name` field — the only mutable field on `ColumnEntity`), matching the existing controller pattern, so `ColumnEntity`'s `@Version` is actually reachable/testable through the API rather than only defensively present.

### 409 response body shape
- **D-05:** The 409 response stays a plain message string (`ResponseEntity<String>`), matching every other `GlobalExceptionHandler` handler in this codebase — none of which use a structured error DTO today. No resource id/type is embedded in the message; the client already knows the id from the request URL it just called (e.g. `PUT /tasks/{taskId}`). Example message shape: "Task was modified by another request, please refetch."

### Manual DDL timing
- **D-06:** The real Postgres schema needs a one-off manual `ALTER TABLE ... ADD COLUMN version bigint NOT NULL DEFAULT 0` on both `tasks` and `columns` tables (`ddl-auto` is unset in `application.properties`, so Hibernate will not create the column automatically). — **Reversibility:** one-way — skipping this before deploy breaks production immediately, since master auto-deploys to EC2 on every push via GitHub Actions (`.github/workflows/deploy.yml`), and any request touching Task/Column would then hit a missing-column SQL error.
- **D-07:** The phase must deliver the exact DDL as a ready-to-run script/documented command (deliverable, not just a mental note). The user runs it manually against the real DB right before merging/deploying this phase's PR — NOT deferred to Epic 3 (Flyway migrations). Long-term migration tooling still lands later in Epic 3; this is a one-off bridge step for this phase only.
- **Flag raised and resolved during discussion:** initial instinct was to defer the DDL to Epic 3's Flyway work, but that would leave production broken between merge and Epic 3 landing (auto-deploy on push to master). User confirmed running it manually now, right before merge, instead.

### Claude's Discretion
- Exact DDL script format/location (e.g. `docs/plans/backend-modernization/` vs. a scratch SQL file) — pick whatever fits the existing `docs/plans/backend-modernization/` structure.
- Exact wording of the 409 message text, as long as it stays a plain string with no embedded id/type.
- How the bulk-delete/`@Version`-bypass tradeoff gets documented (code comment vs. STATUS.md note) — must be documented somewhere, not silently left implicit (carried from research, see Deferred/Blockers below).
- Whether `ColumnEntity`'s existing `@Data`/`@EqualsAndHashCode(callSuper = false)` needs an explicit `@EqualsAndHashCode.Exclude` on the new `version` field, or whether Lombok config needs to change some other way — implementation detail for the planner/researcher to resolve, just must NOT let `version` participate in equals/hashCode.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Epic spec (source of truth for scope)
- `docs/plans/backend-modernization/02-n-plus-one-optimistic-locking.md` — Task 5 (optimistic locking) is the literal spec this phase implements. Tasks 1–3 (query-count diagnostics, ownership-chain fix, bulk-delete fix) are already done — see STATUS.md.
- `docs/plans/backend-modernization/STATUS.md` — progress/decisions log; documents the completed portion of Epic 2 and the exact gotchas already hit (bulk JPQL delete + FK violation, stale persistence-context entities).
- `docs/plans/backend-modernization/README.md` — overall modernization plan context; confirms this is Epic 2, one-epic-per-PR discipline.

### Project-level requirements
- `.planning/PROJECT.md` — Core Value, Active requirements, full Context/Constraints for this GSD project.
- `.planning/REQUIREMENTS.md` — LOCK-01..04 (v1, this phase) and FULL-01..03 (v2, deferred).

### Research (grounded findings, not general knowledge)
- `.planning/research/SUMMARY.md` — synthesized findings across all 4 research dimensions; read first.
- `.planning/research/STACK.md` — confirms the existing 423→409 bug in `GlobalExceptionHandler.java` with exact fix; `@Version` placement guidance.
- `.planning/research/PITFALLS.md` — bulk-delete/`@Version`-bypass, Lombok equals/hashCode landmine, `ColumnRepository.deleteAllByBoardId` derived-vs-bulk asymmetry, all with codebase-verified line references.
- `.planning/research/ARCHITECTURE.md` — confirms `ddl-auto` is unset in the real `application.properties` (not just the test profile).

### Codebase maps
- `.planning/codebase/CONCERNS.md` — "Batch Delete Operations with Session Cache" (fragile area directly relevant to the bulk-delete/version-bypass tradeoff) and "GlobalExceptionHandler Broad Catch-All".
- `.planning/codebase/ARCHITECTURE.md` — layered architecture, existing DTO/mapper/service conventions.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `GlobalExceptionHandler.java` (`src/main/java/com/vrudenko/kanban_board/handler/`) — already has an `OptimisticLockingFailureException` handler (line 80-84), just mapped to the wrong status (423 → needs to become 409). One-line fix, not a new handler.
- `TaskController`/`BoardController`/`SubtaskController` `PUT .../updateById` pattern — copy this exact shape for the new `ColumnController` update endpoint.
- `OwnershipVerifierServiceTest.QueryCountTest` convention (Hibernate `Statistics.getPrepareStatementCount()`) — existing pattern to follow for any new query-count-style regression tests, though this phase's tests are primarily about HTTP status assertions, not query counts.

### Established Patterns
- DTO layering: `SaveXRequestDTO` (create) / `UpdateXRequestDTO` (partial update) / `XResponseDTO` (read) per entity, MapStruct mappers with `componentModel=SPRING`.
- Services use `@Autowired` field injection (not constructor) — established to sidestep circular bean dependency ordering between Board/Column/Task/Subtask/Ownership services. Follow this for any new service code.
- `BaseEntity` provides the shared ULID `id` field via `@RandFlakeId` — `@Version` goes directly on `TaskEntity`/`ColumnEntity`, NOT on `BaseEntity` (would unscope the change to `UserEntity`/`BoardEntity`/`SubtaskEntity` too).
- `ColumnEntity` uses `@Data` + `@EqualsAndHashCode(callSuper = false)` (no manual exclusions yet); `TaskEntity`'s equals/hashCode situation should be checked directly during planning (research flagged it as "commented-out" — verify current state).

### Integration Points
- New `ColumnController.updateById()` route: `PUT /boards/{boardId}/columns/{columnId}`, following `ApiPaths` conventions already used by `BOARD_ID`/`TASK_ID`/`SUBTASK_ID`.
- `ColumnService` needs a corresponding `updateById()` method (ownership-verified via `OwnershipVerifierService`, matching `TaskService`/`BoardService`/`SubtaskService` patterns).

</code_context>

<specifics>
## Specific Ideas

- 409 message example the user liked: something like "Task was modified by another request, please refetch" — plain string, no embedded id (client already has it from the request URL).
- DDL must be a concrete, ready-to-run deliverable (script or exact documented command) — not just described in prose — since the user is manually running it against the real Postgres instance right before merge.

</specifics>

<deferred>
## Deferred Ideas

- `GET /boards/{boardId}/full` nested read endpoint — already tracked as FULL-01..03 in REQUIREMENTS.md v2, not re-discussed here.
- Flyway migration tooling — explicitly Epic 3 of the modernization plan; this phase's manual DDL is a one-off bridge, not a replacement for that later work. Worth flagging again when Epic 3 starts so the manual DDL step doesn't get silently re-applied or forgotten in migration history.
- Bulk-delete `@Version`-bypass tradeoff and the `ColumnRepository.deleteAllByBoardId` derived-vs-bulk asymmetry (both carried from research/STATE.md Blockers/Concerns) — must be explicitly documented during this phase (Claude's discretion on exact form), not deferred further; noting here so planning doesn't drop it.

### Reviewed Todos (not folded)
None — no pending todos matched this phase (`todo_count: 0`).

</deferred>

---

*Phase: 1-Optimistic Locking*
*Context gathered: 2026-08-01*
