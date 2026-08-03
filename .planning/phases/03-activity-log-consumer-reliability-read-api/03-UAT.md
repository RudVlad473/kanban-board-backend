---
status: partial
phase: 03-activity-log-consumer-reliability-read-api
source: [03-VERIFICATION.md]
started: 2026-08-02T15:31:38Z
updated: 2026-08-03T00:00:00Z
---

## Current Test

[testing complete]

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
result: blocked
blocked_by: other
reason: "User does not have access to the production Postgres deploy-target database"

## Summary

total: 1
passed: 0
issues: 0
pending: 0
skipped: 0
blocked: 1

## Gaps
