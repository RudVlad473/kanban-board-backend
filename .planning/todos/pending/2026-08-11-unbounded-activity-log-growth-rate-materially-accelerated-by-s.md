---
created: 2026-08-11T21:00:00.000Z
title: activity_log has no retention policy, and its growth rate just materially accelerated
area: backend
severity: minor
files:

  - src/main/resources/db/migration/V3__add_activity_log.sql
  - src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

`V3__add_activity_log.sql`'s own comment states there is no retention policy on the `activity_log`
feed — it grows forever. Quick task 260811-s5e expanded the `kanban.activity` pipeline from 6 to
14 event types, closing the gap where `SubtaskService` published nothing at all. Subtask
completion toggling is very plausibly the single highest-frequency action a user takes on a kanban
board (checking off subtasks is a routine, repeated interaction, unlike creating or deleting a
board/column), so this change is a real, material acceleration of the row-growth rate, not a
theoretical one.

Reads are unaffected — `idx_activity_log_board_created_id` keeps `GET /boards/{boardId}/activity`
an index scan regardless of table size — so this is a storage-growth problem, not a latency
problem. Accepted as a known trade-off at 260811-s5e time (see that quick task's threat model,
T-S5E-06, disposition `accept`), filed here rather than silently absorbed.

## Solution

Not yet decided. Candidates for whoever picks this up:

1. **Time-based retention** — a scheduled job or Flyway-adjacent script deleting/archiving rows
   older than N days/months. Needs a product decision on how long activity history should be
   retorable per board.

2. **Row-count-based retention per board** — cap each board's activity feed at the most recent N
   rows, deleting older ones. Simpler to reason about per-board storage, but changes the semantics
   of "full history" if a board is very active.

3. **Do nothing yet, monitor** — if actual production row counts stay small relative to available
   Postgres storage (this project's deploy target, per `.planning/PROJECT.md`, is a cost-guarded
   Oracle Cloud + Neon stack with real storage limits), this may not need action for a long time.
   Revisit once Phase 5 (Infra Migration) has real production data to measure against, rather than
   guessing capacity needs from a local dev environment.
