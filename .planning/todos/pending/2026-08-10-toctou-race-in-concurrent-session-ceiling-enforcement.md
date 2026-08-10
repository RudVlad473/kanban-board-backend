---
created: 2026-08-10T20:00:00.000Z
title: TOCTOU race in concurrent-session-ceiling enforcement lets a single account briefly exceed maximumSessions
area: backend
severity: security
files:
  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java:112
  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
---

## Problem

`/claude-security` scan (2026-08-10, medium effort, whole-repo `attack-surface` scope, run
against phase 07.1's finished HEAD) finding **F6** (low severity, confidence medium, 2/3
adversarial-panel votes):

`AuthenticationController.authenticate` (`:112`) invokes `sessionAuthenticationStrategy
.onAuthentication(...)`, which enforces `MAX_CONCURRENT_SESSIONS = 2`
(`SecurityConfiguration.java`) by reading the caller's live session count from
`SpringSessionBackedSessionRegistry` (backed by the shared `SPRING_SESSION` table) and rejecting
the new signin if the count is already at the ceiling. Two concurrent signin requests for the
same principal can both read the same under-threshold count before either has persisted its new
session row, and both proceed — a classic time-of-check-to-time-of-use race, allowing a brief
window where more than 2 sessions exist for one account.

## Why this is deferred, not fixed now

- Severity is low, and confidence is medium (2/3, not unanimous) — this project's
  `security_block_on: high` setting does not mandate an in-phase fix, and the race is not
  introduced or worsened by this phase (the ceiling-enforcement mechanism itself shipped in quick
  task `260803-m2z`, well before 07.1).
- A correct fix needs either a database-level serialization point (e.g. an advisory lock or a
  `SELECT ... FOR UPDATE`-style guard scoped per principal around the count-then-register
  sequence) or accepting a small, bounded, self-healing overshoot — both are real design decisions
  with their own trade-offs (added latency on every signin vs. a temporarily-soft ceiling), not a
  small fix appropriate to bundle into this phase's frontend-readiness scope.
- Practical impact is bounded and low-stakes: the race window is the gap between two concurrent
  HTTP requests completing their session-registry read and their session-row write — realistically
  microseconds to low milliseconds — and the worst case is a brief, self-correcting overshoot
  (e.g. 3 live sessions momentarily instead of 2), not an unbounded or persistent bypass.

## Solution

When picked up: evaluate a per-principal serialization point around the
count-then-register sequence in `ConcurrentSessionControlAuthenticationStrategy`'s call path —
options include a short-lived advisory lock keyed on the principal (e.g. Postgres
`pg_advisory_xact_lock` scoped to the authenticating transaction) or accepting the race as a
documented, bounded design trade-off with a comment explaining why, mirroring the treatment
`T-07.1-05-04`/D-14 gave `UserEntity`'s deliberately-unversioned last-write-wins theme updates.
Whichever is chosen, add a regression test that drives two genuinely concurrent signin requests
(matching the existing `BoardCreationE2ETest.ConcurrentCreate` real-concurrency test pattern) and
asserts the resulting live session count for that principal, proving the fix (or documenting the
accepted bound) empirically rather than by inspection.
