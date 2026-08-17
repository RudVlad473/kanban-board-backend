# Phase 6: Mock-up Feature Gap Closure - Context

**Gathered:** 2026-08-08
**Status:** Ready for planning

<domain>
## Phase Boundary

Close the six concrete gaps `docs/MOCKUP_FEATURE_GAP.md` §1 identifies between the Kanban
mock-ups and the current REST API: a public board-creation route, column deletion, task/column
ordering, a single nested "full board" read, per-user theme persistence, and optimistic-locking
coverage on subtask updates. Covers the Board/Column/Task/Subtask/User domains and the existing
activity-log event pipeline where a new mutation needs a new event type. Does not cover anything
from Phase 5 (Infra Migration), OpenAPI polish, or any capability not named in the six items
above (see `<deferred>` for what came up and was explicitly kept in or out).

</domain>

<decisions>
## Implementation Decisions

### Ordering (task & column position)
- **D-01:** Build ordering fully — a position field AND working reorder endpoints for both
  tasks and columns — even though the mock-up's own visual pass (`docs/MOCKUP_FEATURE_GAP.md`
  §1.3/MU-M3) found no grab handle, drag shadow, or drop indicator anywhere; the mock-up only
  exposes status change via a dropdown. User's call: treat this as the gap doc's own finding
  ("the design does not draw this affordance") rather than a reason to skip the backend
  capability — build to conventional Kanban expectations regardless. — **Reversibility:**
  costly — once position columns exist and a client depends on stable positions, removing the
  field is a schema and contract change.
- **D-02:** Position scheme is a simple `Integer position` column with renumber-on-insert
  (shift subsequent siblings' indices in the same transaction) — not fractional/gap-based keys
  (LexoRank-style). Matches this codebase's existing no-new-concepts style; fractional keys were
  explicitly rejected as disproportionate complexity for this project's scale. — **Reversibility:**
  costly — switching schemes later touches every existing row and every consumer of the field.
- **D-03:** Ordering scope covers both `TaskEntity.position` (within a column) and
  `ColumnEntity.position` (within a board) — matches the gap doc's finding verbatim
  ("reordering tasks within a column or reordering columns within a board is equally
  unsupported").
- **D-04:** Task move and task reorder are **one endpoint**, not two: extend
  `MoveTaskRequestDTO` with a `targetPosition` field alongside the existing `targetColumnId`/
  `version`. A single request covers "move to column X at position N," matching what a real
  drag-drop client would report as one fact, not two separate calls.

### Column deletion
- **D-05:** Deleting a column cascades to its tasks and subtasks — no reassignment option.
  Reuses `TaskService.deleteAllByColumn`'s existing batched delete directly, mirroring how board
  deletion already cascades.
- **D-06:** Column deletion publishes a new `ColumnDeletedEvent` to the activity log, mirroring
  `TaskDeletedEvent`'s existing shape: new sealed record (added to `ActivityEvent`'s `permits`
  list), a new `.avsc` Avro schema, and both switch arms in `ActivityEventAvroMapper` (`toAvro`
  and `toDomain`). Keeps activity-log coverage consistent — board and task deletes are already
  logged; column delete would otherwise be the one unlogged mutation. — **Reversibility:**
  costly — once a schema is registered and events are produced under it, removing the event type
  later is a compatibility-mode-governed change (per Phase 4's D-04 BACKWARD compatibility
  policy), not a simple revert.
- **D-07:** No non-empty-column guard. `DELETE /boards/{boardId}/columns/{columnId}` always
  cascades once ownership is verified, regardless of task count — matches the mock-up's
  client-side confirm-dialog pattern and matches board delete's existing behavior; the backend
  doesn't second-guess a confirmed delete.

### Board creation
- **D-08:** Client submits a board's initial columns via **create-then-batch-add**: `POST
  /boards` with just `{name}`, then one `POST /boards/{boardId}/columns` call per initial
  column — reusing the existing column-add endpoint as-is. `SaveBoardRequestDTO` does NOT grow
  a nested columns list. `UserService.addBoardByUserId` gets wired onto a new `BoardController`
  mapping with no DTO changes.
- **D-09:** Add board-name uniqueness validation per user, applying to **both** `POST /boards`
  (create) and `PUT /boards/{boardId}` (rename) for consistency. This is a deliberate deviation
  from the "leave as-is" default: `UserService.addBoardByUserId`'s existing TODO
  (`src/main/java/com/vrudenko/kanban_board/service/UserService.java:73`) flagging the missing
  check gets resolved here rather than carried forward. Exact validation mechanics (case
  sensitivity, HTTP status/error shape) are Claude's discretion below. — **Reversibility:**
  reversible — a service-layer check with no schema impact; removing it later is a code-only
  change.

### Theme persistence
- **D-10:** Build full server-side theme persistence in Phase 6 — a field on
  `UserEntity`/`UserResponseDTO` plus a read/write endpoint — even though no frontend exists yet
  to consume it. Treated as proportionate: small (one column, one endpoint), and consistent with
  building all 6 gap-doc items rather than trimming one for lack of a current consumer.
- **D-11:** Theme is represented as an **enum, `LIGHT`/`DARK`** — not a free string. Matches
  exactly the two states the mock-up shows (`MU-Th1`..`MU-Th3`); no third state exists in the
  design to justify a more flexible type.
- **D-12:** Default value for users with no explicit preference is **`LIGHT`** (not nullable).
  Avoids a null-handling branch on every consumer of `UserResponseDTO.theme` and matches the
  mock-up's light palette being the design system's primary/first-shown pass.

### Claude's Discretion
- Exact HTTP status/error shape for the board-name-uniqueness conflict (D-09) — align with
  `GlobalExceptionHandler`'s existing conflict conventions (it already maps optimistic-locking
  conflicts to `409`) or use a `400` field-validation error — planner's call.
- Exact renumbering mechanics for `position` inserts/moves (D-02) — e.g. whether every sibling
  after the insertion point shifts by exactly 1, or a larger gap strategy within the "simple
  integer" constraint — planner's call, must stay within D-02's no-fractional-keys boundary.
- Whether `ColumnDeletedEvent`'s Avro schema is registered as a wholly new subject or extends an
  existing one — follow Phase 4's established "one schema per event type" convention
  (`.planning/phases/04-schema-registry/04-CONTEXT.md` D-03) rather than deciding fresh here.
- Nested DTO class names/package structure for `GET /boards/{boardId}/full` (D-13 in spirit —
  no explicit user decision was needed since the shape is dictated by the four existing flat
  DTOs) — follow the existing `dto/{domain}_dto/` package convention; researcher/planner confirm
  exact naming (e.g. `BoardFullResponseDTO`) and whether MapStruct can compose the existing
  per-entity mappers or needs a hand-written aggregation step.
- Whether `SubtaskService.updateById` needs the `entityManager.flush()` call that
  `TaskService.updateById`/`ColumnService.updateById` already have (so the response DTO carries
  the post-update version) — should mirror the existing pattern exactly; planner confirms exact
  placement per `service/TaskService.java:103-132`.

### Folded Todos
- **"Use Snowflake ID generator for activity log events"**
  (`.planning/todos/pending/2026-08-02-use-snowflake-id-generator-for-activity-log-events.md`) —
  folded into Phase 6 **at the user's explicit request**, despite a topical mismatch flagged
  during discussion: this todo is about switching `ActivityLogEntity.eventId` (the activity-log
  dedupe key) from `UUID.randomUUID()` to a Snowflake-style time-ordered ID. It does not touch
  any of the six board/column/task/subtask gap-doc items. The user was shown this mismatch
  explicitly and chose "fold it in anyway." Downstream agents: treat this as a seventh,
  independent deliverable riding along in this phase, not as related work to the other six —
  its own file scope is `src/main/java/com/vrudenko/kanban_board/event/` and
  `entity/ActivityLogEntity.java`, and per the todo's own text it "should probably follow
  whatever [the] broader decision [on project-wide ID generation strategy] lands on" — flag to
  the user if that broader decision hasn't been made before implementing this in isolation.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Source document (this phase's entire origin)
- `docs/MOCKUP_FEATURE_GAP.md` §1 (items 1.1–1.6) — the six gaps this phase closes, each with
  mock-up page citations (MU-xx) and current backend citations (BE-xx). §3 (hand-off tables) and
  Appendix A/B are useful for confirming exact existing endpoint shapes while implementing.

### Project-level state
- `.planning/PROJECT.md` — Key Decisions table: "DTOs are flat (no nested entity graphs)
  specifically to avoid `LazyInitializationException` — the deferred `/full` endpoint DTO will
  need deliberate nested structure, a departure from this pattern that should be justified
  explicitly" (directly governs the `GET /full` implementation); also documents the
  explicit-version-compare-then-409 pattern (governs D-\* subtask locking work) and the
  field-injection-over-constructor convention.
- `.planning/REQUIREMENTS.md` — currently scoped entirely to v1.2 (INFRA-\*/SCHEMA-\*); Phase 6
  has **no requirement IDs defined yet** — planner/researcher must propose new REQ-IDs (e.g. a
  `GAP-01`..`GAP-07` block, covering the 6 gap-doc items plus the folded Snowflake todo) and add
  them to REQUIREMENTS.md's traceability table.
- `.planning/STATE.md` §Deferred Items — records `GET /boards/{boardId}/full` (FULL-01..03) as
  "Deferred to v2" at 2026-07-31; this phase un-defers it (already noted in the Roadmap
  Evolution log).
- `.planning/ROADMAP.md` Phase 6 entry — goal statement and the 6 candidate features as
  originally scoped from the gap doc.

### Prior-phase precedent to follow, not re-decide
- `.planning/phases/04-schema-registry/04-CONTEXT.md` D-03 ("one schema per event type") — the
  convention `ColumnDeletedEvent`'s new Avro schema (D-06) must follow.
- `.planning/phases/04-schema-registry/04-CONTEXT.md` D-04 (BACKWARD compatibility mode,
  already enforced) — governs how the new `ColumnDeletedEvent` schema gets registered.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `UserService.addBoardByUserId(String userId, SaveBoardRequestDTO boardDTO)`
  (`service/UserService.java:70-75`) — already implements board creation end-to-end (calls
  `BoardService.save`, publishes `BoardCreatedEvent`); just needs a controller mapping (D-08).
- `TaskService.deleteAllByColumn` (batched task+subtask delete) — the exact cascade logic D-05's
  column delete reuses.
- `TaskService.updateById`/`ColumnService.updateById`'s explicit compare-then-409
  pattern (`service/TaskService.java:103-132`, `service/ColumnService.java:118-144`) — the
  pattern to mirror onto `SubtaskService.updateById` for the version-field gap (§1.6).
- `ColumnEntity.java:34-37` / `TaskEntity.java:36-38` — the exact `@Version` field declaration
  (`@Version @Column(nullable = false) private Long version;`, with
  `@EqualsAndHashCode.Exclude` on `ColumnEntity`'s) to replicate on `SubtaskEntity`.
- `OwnershipVerifierService.verifyOwnershipOfColumn` (`service/OwnershipVerifierService.java:60-72`)
  — the ownership check the new column-delete endpoint calls directly.
- `ActivityEvent` sealed interface + `BoardCreatedEvent`/`ColumnCreatedEvent`
  (`event/ActivityEvent.java`, already `permits`-listed and already published by
  `BoardService.save`/`ColumnService.save`) — POST /boards needs **no new event type**, only the
  controller mapping is missing.

### Established Patterns
- Flat response DTOs everywhere except the new `/full` endpoint (D-\* above) — `BoardResponseDTO`
  (`id`, `name`), `ColumnResponseDTO` (`id`, `name`, `version`), `TaskResponseDTO` (`id`, `title`,
  `description`, `version`), `SubtaskResponseDTO` (`id`, `title`, `isCompleted`, no `version` —
  the exact gap §1.6 closes).
- Ownership chain always re-derives from the parent: `verifyOwnershipOfBoard` →
  `verifyOwnershipOfColumn` → `verifyOwnershipOfTask` → `verifyOwnershipOfSubtask`, each
  returning a `Pair<UserEntity, XEntity>`.
- `@Version`-guarded updates always do an explicit client-supplied-version compare before
  mutating, then `entityManager.flush()` to force the UPDATE so the response DTO carries the new
  version — not just relying on Hibernate's automatic dirty-check.

### Integration Points
- `BoardController` — new `POST /boards` mapping (D-08).
- `ColumnController` — new `DELETE /{columnId}` mapping (D-05/D-06/D-07); `ColumnService.java:159`
  currently has a literal `// TODO: implement delete logic` marking where the service method
  goes.
- `TaskMoveController`/`MoveTaskRequestDTO` — extended with `targetPosition` (D-04).
- `ActivityEventAvroMapper` (`event/avro/ActivityEventAvroMapper.java`) — both `toAvro` (42-86)
  and `toDomain` (96-135) switch arms need a new case for `ColumnDeletedEvent` (D-06).
- No `UserController` exists today — a theme read/write endpoint (D-10) needs either a new
  controller or a mapping added to an existing one; user-facing operations otherwise live on
  `BoardController`/`AuthenticationController` today, so this is a real "where does it go"
  question for planning, not pre-decided here.

</code_context>

<specifics>
## Specific Ideas

The gap doc (`docs/MOCKUP_FEATURE_GAP.md`) itself is written as a hedge on ordering (§1.3
explicitly notes the mock-up draws no drag affordance) and on theme persistence (§1.5 explicitly
says "if the frontend needs the choice to persist... "). Both hedges were surfaced to the user
directly during discussion; the user chose to build both fully anyway (D-01, D-10) rather than
narrow scope — record this so downstream agents don't re-litigate "should we even build this."

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within the six gap-doc items (plus the one explicitly-folded,
topically-unrelated todo noted above).

### Reviewed Todos (not folded)
- "Create sequence diagram documenting full system flow for frontend handoff",
  "Create high-level infra architecture diagram before live infra onboarding",
  "D-02's replay-from-zero rationale is not delivered by non-transitive BACKWARD",
  "Enable virtual threads in Spring Boot config",
  "Explore an alert-service integration as a separate microservice",
  "Re-enable and rewrite the disabled deploy-to-ec2 CI job once Phase 5 lands",
  "Bump Java version from 21 to 25 (current LTS)",
  "Add dependency vulnerability scan" — all matched Phase 6 on generic keyword overlap only
  (`phase`, `plan`, `version`, `kanban`, `board`, `add`), not actual topic. None concern
  board/column/task/subtask REST gaps. Left pending, unchanged.

</deferred>

---

*Phase: 6-Mock-up Feature Gap Closure*
*Context gathered: 2026-08-08*
