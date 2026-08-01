# Milestones

## v1.0 Optimistic Locking (Shipped: 2026-08-01)

**Phases completed:** 1 phases, 3 plans, 7 tasks

**Key accomplishments:**

- End-to-end optimistic locking on Task updates (entity @Version, required client version, explicit service check, 423->409 fix) proven by a real HTTP E2E test, plus a fix to a pre-existing cookie-authentication bug that silently broke every real (non-MockMvc) authenticated request.
- Added the previously-missing Column update endpoint (PUT /boards/{boardId}/columns/{columnId}) with the same explicit version-check optimistic-locking pattern proven for Task in Plan 01, plus documentation of the two research-carried bulk-delete/@Version tradeoffs.
- Delivered a standalone, idempotent `ALTER TABLE ... ADD COLUMN version bigint NOT NULL DEFAULT 0` SQL script for the real Postgres `tasks`/`columns` tables, plus a dated STATUS.md decision-log entry recording the one-way manual-run obligation before merge/deploy.

---
