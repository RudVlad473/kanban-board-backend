# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v1.0 — Optimistic Locking

**Shipped:** 2026-08-01
**Phases:** 1 | **Plans:** 3 | **Sessions:** 1

### What Was Built
- `@Version` fields on `TaskEntity` and `ColumnEntity`, with explicit client-supplied version checks in `TaskService.updateById`/`ColumnService.updateById` and a `GlobalExceptionHandler` fix mapping `OptimisticLockingFailureException` to HTTP 409 (was 423)
- The previously-missing `PUT /boards/{boardId}/columns/{columnId}` endpoint, giving `ColumnEntity` its first update path
- Real HTTP E2E tests (`TaskLockingE2ETest`, `ColumnLockingE2ETest`) proving concurrent-conflict 409 behavior end-to-end, plus a standalone idempotent DDL bridge script for the real Postgres schema

### What Worked
- Building Task locking as a single tracer slice (entity → DTO → service → handler → E2E test) in Plan 01 gave Plan 02 a proven pattern to mechanically reuse for Column, cutting Plan 02's scope to just the new endpoint plus documentation
- Narrowing v1 scope to optimistic locking only (deferring the `/full` endpoint to v2) kept this a genuinely one-sitting, independently reviewable milestone

### What Was Inefficient
- The mandated real-HTTP E2E test surfaced a pre-existing, previously-unexercised authentication bug (`UserAuthenticationProvider` storing a raw userId string as principal) — necessary to fix, but unplanned scope discovered mid-plan rather than caught by research
- That same auth fix briefly introduced a security regression (full `UserEntity` incl. `passwordHash` flowing into the session store), caught only by code review — a case where the "obvious" fix needed a second pass

### Patterns Established
- Optimistic-lock update pattern: load managed entity → compare `dto.version` to `entity.version` → throw `OptimisticLockingFailureException` on mismatch → mutate → save → `entityManager.flush()` → map to response DTO (the flush is required so the response reflects the post-increment version, not the stale pre-flush one)
- One-off manual bridge DDL for schema gaps `ddl-auto` won't cover: deliver as a runnable `.sql` file with a header comment on scope/timing, not just prose in STATUS.md

### Key Lessons
1. When `ddl-auto` is unset against a real database, any new `@Version`/column addition needs an explicit manual migration step called out loudly (STATUS.md decision log + standalone script) — easy to silently forget since local H2 test runs won't reveal the gap.
2. A single true end-to-end (RANDOM_PORT + real HTTP) test is worth writing early: it exercised the real cookie-authentication path for the first time in this codebase and caught a bug that unit/MockMvc tests structurally could not see.

### Cost Observations
- Sessions: 1
- Notable: All 3 plans (tracer + reuse + DDL bridge) completed within a single session; the reuse-heavy shape of Plan 02 (mirroring Plan 01's pattern) kept it markedly cheaper (9200 vs 5510 tokens is comparable, but zero deviations vs four in Plan 01) despite delivering a whole new endpoint.

---

## Milestone: v1.1 — Kafka Activity Feed

**Shipped:** 2026-08-03
**Phases:** 2 | **Plans:** 6 | **Sessions:** several (spanning multiple quick tasks between phases)

### What Was Built
- Local Kafka dev stack (`docker-compose.yml`: postgres + KRaft-mode `apache/kafka-native`, no Zookeeper + app, health-gated) and `PATCH /tasks/{taskId}/move` reusing v1.0's explicit-version-compare convention
- All 5 domain mutation types publishing typed, sealed `ActivityEvent` records via `KafkaEventPublisher` strictly after transaction commit
- Idempotent, deduplicated `ActivityLogConsumer`/`ActivityLogRecorder` with a database-unique-constraint-backed dedup and a byte-fidelity-preserving dead-letter topic
- `GET /boards/{boardId}/activity` — first paginated, ownership-verified endpoint in this codebase

### What Worked
- Tracer-first plan sequencing (proven in v1.0) repeated cleanly: Phase 2 Plan 01 proved the full producer vertical slice, Plan 02 mechanically extended it to the remaining 4 event types; Phase 3 Plan 01 proved the consumer slice end-to-end before Plans 02/03 built reliability and the read API on top
- Live-broker verification (real Testcontainers Kafka, not mocks) caught two genuine production bugs that unit tests/mocks would have missed entirely: a dead-letter `KafkaTemplate` bean silently suppressed by Spring's `@ConditionalOnMissingBean`, and an `@Qualifier` ambiguity routing the dead-letter path through the wrong (JSON-encoding) producer template

### What Was Inefficient
- **Phase 2's verification step was never run at the time.** All 3 plans executed and got summaries, but no `VERIFICATION.md` was produced — a gap invisible until the pre-milestone-close audit caught it via `init.manager`'s `phase_complete`/`verification_status` fields. Retroactive verification passed 15/15 cleanly, so the work itself was fine — but the gap in process meant the milestone nearly closed without ever confirming it.
- **The Phase 3 human-verification item (run the production DDL script against the real deploy target) sat blocked for over a day** before being resolved as "superseded" — not because the check was wrong, but because the underlying AWS infrastructure it referenced was deleted by the operator mid-milestone (unrelated pricing decision), which happened to also retroactively close this blocker. Coincidental, not a process fix.
- Five quick tasks (lint/tooling backlog items — ArchUnit, Error Prone at two different rigor levels, session-auth wiring, UserMapper hash-leak cleanup) were interleaved between phases 2 and 3, stretching wall-clock time across "several sessions" without a clean phase boundary — reasonable given the personal-project pacing, but made this milestone's actual phase-to-phase timeline harder to reconstruct after the fact.

### Patterns Established
- `@TransactionalEventListener(phase = AFTER_COMMIT)` as the sole Kafka-publish touchpoint, confined to one class (`KafkaEventPublisher`) that `src/main` service code never imports directly — verified by grep, not just code review
- Sealed `ActivityEvent` interface with an exhaustive switch (no `default` arm) in the consumer — adding a 6th event type is a compile error, not a silent no-op
- Idempotent consume pattern: `existsByEventId` fast path + a database UNIQUE constraint as the actual arbiter of concurrent redelivery races (the fast path is an optimization, never the sole safety net)
- A shared `sendAndAwaitAck` test helper (added post-milestone during Error Prone hardening) as the standard shape for any future Kafka-send test — ack-checked, not fire-and-forget, so a broker rejection surfaces as an immediate exception instead of a misleading 30-second Awaitility timeout

### Key Lessons
1. **A phase with all plans executed and summarized is not the same as a verified phase** — `has_summary: true` on every plan tells you execution finished, not that anyone checked the result against the goal. Run `/gsd-verify-work` (or confirm `VERIFICATION.md` exists) before treating a phase as done, not just before closing the milestone.
2. **Live-infrastructure verification (real broker, real Docker Compose stack) finds bugs that code review and mocked tests structurally cannot** — both production bugs this milestone found (dead-letter bean suppression, `@Qualifier` ambiguity) only surfaced when tests ran against an actual Kafka broker.
3. **External-world state (a deleted cloud database) can silently invalidate a verification item that has nothing wrong with it as written** — the DDL-bridge-script check was correctly designed; it became unresolvable only because its target stopped existing. Worth a periodic sanity check on human-verification items that reference specific external infrastructure, independent of whether the codebase itself changed.

### Cost Observations
- Sessions: several, interleaved with 5+ quick tasks between phases
- Notable: the pre-milestone-close audit (checking `init.manager`, `audit-open`, and the UAT/VERIFICATION state directly) surfaced two real process gaps — the unverified Phase 2 and the stale UAT blocker — that a straight "run /gsd-complete-milestone and accept defaults" pass would have silently missed or force-closed incorrectly

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1.0 | 1 | 1 | First milestone — established the tracer-then-reuse plan-sequencing pattern for symmetric entity work (Task then Column) |
| v1.1 | several | 2 | Extended tracer-first sequencing to producer/consumer Kafka slices; surfaced a real process gap (a phase can finish execution with zero verification) that the pre-milestone-close audit is now the catch for |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v1.0 | 118+ (full suite) | Not tracked | 0 |
| v1.1 | 178 (full suite, E2E included) | Not tracked | 0 (spring-kafka/Testcontainers-Kafka were already anticipated by the epic spec) |

### Top Lessons (Verified Across Milestones)

1. Tracer-first plan sequencing (prove the pattern once end-to-end, then mechanically reuse for symmetric entities) reduces deviations in follow-on plans — v1.0 Plan 01 had 4 auto-fixed deviations, Plan 02 had 0.
2. Execution completeness (`has_summary: true`) and verification completeness (`VERIFICATION.md` exists and passed) are two different facts — v1.1 Phase 2 had the former without the latter for its entire lifetime until the milestone-close audit caught it. Check both before treating any phase as done.
3. Live-infrastructure verification (a real broker, a real running stack) finds a class of bug — bean-resolution ambiguity, conditional-bean suppression — that mocked tests and code review both structurally miss, regardless of how carefully either is done.
