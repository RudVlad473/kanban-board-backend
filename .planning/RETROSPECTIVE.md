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

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1.0 | 1 | 1 | First milestone — established the tracer-then-reuse plan-sequencing pattern for symmetric entity work (Task then Column) |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v1.0 | 118+ (full suite) | Not tracked | 0 |

### Top Lessons (Verified Across Milestones)

1. Tracer-first plan sequencing (prove the pattern once end-to-end, then mechanically reuse for symmetric entities) reduces deviations in follow-on plans — v1.0 Plan 01 had 4 auto-fixed deviations, Plan 02 had 0.
