---
status: testing
phase: 03-activity-log-consumer-reliability-read-api
source: [03-VERIFICATION.md]
started: 2026-08-02T15:31:38Z
updated: 2026-08-02T15:31:38Z
---

## Current Test

number: 1
name: Run the production DDL bridge script against the real Postgres deploy target
expected: |
  Run docs/plans/backend-modernization/03-activity-log-ddl.sql via psql against the REAL
  Postgres deploy-target database (not H2, not a local dev instance) before this phase's PR
  merges.

  `\d activity_log` shows the table, the unique constraint `uk_activity_log_event_id` on
  `event_id`, and the index `idx_activity_log_board_created_id` on
  `(board_id, created_at DESC, id DESC)`.
awaiting: user response

## Tests

### 1. Run the production DDL bridge script against the real Postgres deploy target
expected: |
  The real Postgres profile has no `spring.jpa.hibernate.ddl-auto` set, so Hibernate will never
  create this table in production. The H2 test profile's `create-drop` schema generation makes
  every test in this phase pass regardless of whether this manual step has been done, so a fully
  green suite proves nothing about the production database. `master` auto-deploys to EC2 on every
  push, so if this table is missing at deploy time, every consumed Kafka event silently exhausts
  retries and lands on the dead-letter topic instead of ever being persisted — a total feature
  outage that superficially looks like "the dead-letter path works." This cannot be verified from
  the codebase; it requires access to the real deploy-target database.
result: [pending]

## Summary

total: 1
passed: 0
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps
