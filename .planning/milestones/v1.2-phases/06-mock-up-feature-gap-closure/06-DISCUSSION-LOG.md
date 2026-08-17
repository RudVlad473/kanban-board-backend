# Phase 6: Mock-up Feature Gap Closure - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-08
**Phase:** 6-Mock-up Feature Gap Closure
**Areas discussed:** Pending todos triage, Ordering scope & mechanism, Column deletion behavior, Board creation request shape, Theme persistence necessity

---

## Pending Todos Triage

| Option | Description | Selected |
|--------|-------------|----------|
| None apply | All 9 matched on generic keyword overlap only — none topically about this phase's REST feature gaps | |
| Sequence diagram — full system flow | Docs/handoff task, not a feature to build | |
| Snowflake ID generator for activity log | About ActivityLogEntity.eventId, unrelated to board/column/task/subtask feature gaps | ✓ |
| Let me see all 9 | Show the remaining todos before deciding | |

**User's choice:** Snowflake ID generator for activity log
**Notes:** Flagged the topical mismatch explicitly (this todo is about `ActivityLogEntity.eventId`, not any of the 6 gap-doc features) and re-asked for confirmation.

| Option | Description | Selected |
|--------|-------------|----------|
| Leave it out | Unrelated to Phase 6's scope — leave pending, not folded here | |
| Fold it in anyway | Include the eventId → Snowflake-ID switch as part of Phase 6's work despite touching a different entity | ✓ |
| I meant something else | Reinterpret as sortable IDs for task/column ordering instead | |

**User's choice:** Fold it in anyway
**Notes:** User confirmed the mismatch was understood and wanted it folded regardless. Recorded in CONTEXT.md's Folded Todos section as a seventh, independent deliverable riding along in this phase.

---

## Ordering scope & mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| Build it fully | Add position field AND reorder endpoints for tasks/columns | ✓ |
| Schema only, no endpoint | Add the column now, skip the reorder logic this phase | |
| Drop this feature entirely | Skip both — treat as a documented gap, not a deliverable | |

**User's choice:** Build it fully

| Option | Description | Selected |
|--------|-------------|----------|
| Integer index, renumber on insert | Simple `Integer position` column, shift siblings in the same transaction | ✓ |
| Fractional / gap-based keys | LexoRank-style float/string key, avoids renumbering most moves | |

**User's choice:** Integer index, renumber on insert

| Option | Description | Selected |
|--------|-------------|----------|
| Both tasks and columns | Position field on both TaskEntity and ColumnEntity | ✓ |
| Tasks only | Smaller scope, columns implicitly ordered by creation | |

**User's choice:** Both tasks and columns

| Option | Description | Selected |
|--------|-------------|----------|
| Extend MoveTaskRequestDTO with position | Add `targetPosition` alongside existing `targetColumnId`/`version` on PATCH /move | ✓ |
| Separate reorder endpoint | Keep /move column-change-only, add a distinct position-change endpoint | |

**User's choice:** Extend MoveTaskRequestDTO with position

---

## Column deletion behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Cascade delete | Reuse TaskService.deleteAllByColumn, mirrors board delete | ✓ |
| Reassign tasks to another column | Requires target column, more complex, no mock-up support | |

**User's choice:** Cascade delete

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, add ColumnDeletedEvent | New sealed record + Avro schema, mirrors TaskDeletedEvent | ✓ |
| No, skip activity logging for this | Smaller change, leaves column delete unlogged | |

**User's choice:** Yes, add ColumnDeletedEvent

| Option | Description | Selected |
|--------|-------------|----------|
| Always cascade, no guard | Matches mock-up's client-side confirm pattern and board-delete precedent | ✓ |
| Reject if column has tasks | New error path, no precedent in this codebase | |

**User's choice:** Always cascade, no guard

---

## Board creation request shape

| Option | Description | Selected |
|--------|-------------|----------|
| Create-then-batch-add | POST /boards then N × POST /columns, reuses existing endpoint | ✓ |
| Single DTO with columns list | Grow SaveBoardRequestDTO, one transactional request | |

**User's choice:** Create-then-batch-add

| Option | Description | Selected |
|--------|-------------|----------|
| Leave as-is, no dedup | Matches current behavior everywhere else (rename also unchecked) | |
| Add uniqueness validation now | New validation logic, touches rename too for consistency | ✓ |

**User's choice:** Add uniqueness validation now
**Notes:** Deviation from the recommended default. Resolves the existing TODO in `UserService.addBoardByUserId` (line 73) rather than carrying it forward. Applies to both create and rename per the option's own description.

---

## Theme persistence necessity

| Option | Description | Selected |
|--------|-------------|----------|
| Build it | Add UserEntity/UserResponseDTO field + endpoint, closes the gap fully | ✓ |
| Drop from Phase 6 | Theme stays client-local until a real frontend exists | |

**User's choice:** Build it

| Option | Description | Selected |
|--------|-------------|----------|
| Enum: LIGHT / DARK | Matches the mock-up's two-state toggle exactly | ✓ |
| Free string | More flexible for future themes, not currently needed | |

**User's choice:** Enum: LIGHT / DARK

| Option | Description | Selected |
|--------|-------------|----------|
| LIGHT | Matches the mock-up's light palette shown first; conventional default | ✓ |
| Nullable / no default | Client falls back to its own default, adds null-handling everywhere | |

**User's choice:** LIGHT

---

## Claude's Discretion

- Exact HTTP status/error shape for the board-name-uniqueness conflict (align with existing 409 optimistic-locking convention, or use a 400 field-validation error).
- Exact renumbering mechanics for `position` inserts/moves (within the integer-index, no-fractional-keys constraint locked by D-02).
- Whether `ColumnDeletedEvent`'s Avro schema is a wholly new subject vs. extends an existing one — follow Phase 4's "one schema per event type" convention.
- Nested DTO class names/package structure for `GET /boards/{boardId}/full`.
- Whether `SubtaskService.updateById` needs the `entityManager.flush()` call that Task/Column update already have — should mirror the existing pattern.

## Deferred Ideas

None — discussion stayed within the six gap-doc items plus the one explicitly-folded, topically-unrelated Snowflake ID todo (see Pending Todos Triage above).

Reviewed-but-not-folded todos (generic keyword overlap only, no topical fit): sequence diagram for frontend handoff, infra architecture diagram, D-02 replay-from-zero rationale, virtual threads, alert-service microservice exploration, deploy-to-ec2 CI rewrite, Java 25 bump, dependency vulnerability scan.
