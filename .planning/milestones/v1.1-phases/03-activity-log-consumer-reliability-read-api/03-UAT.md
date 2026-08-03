---
status: complete
phase: 03-activity-log-consumer-reliability-read-api
source: [03-VERIFICATION.md]
started: 2026-08-02T15:31:38Z
updated: 2026-08-03T21:15:00Z
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
result: skipped
reason: "Superseded: the AWS EC2/RDS deploy target this test referenced was deleted by the operator (moving off AWS due to pricing risk). A v1.2 infra-migration milestone is replacing it with Oracle Cloud + Neon Postgres, which will need its own equivalent pre-merge DDL verification against the new deploy target. Tracking this as a fresh check in that milestone rather than resolving it against a database that no longer exists."

## Summary

total: 1
passed: 0
issues: 0
pending: 0
skipped: 1
blocked: 0

## Gaps
