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

## Milestone: v1.2 — Infra Migration & Schema Registry

**Shipped:** 2026-08-17
**Phases:** 7 (4, 04.1, 04.2, 5, 6, 7, 07.1) | **Plans:** 39 | **Sessions:** many, spanning 2026-08-03 to 2026-08-17

### What Was Built
- A versioned Avro Schema Registry in front of the Kafka activity-log pipeline (5→14 `ActivityEvent` types over the milestone), enforced BACKWARD compatibility, build-time registration, and DLT byte-fidelity re-proven under Avro
- Flyway-managed domain schema (V1→V7 by milestone end) reconstructing the app's real DDL evolution, `ddl-auto=validate` outside tests, and the whole test suite cut over from H2 to a real Testcontainers Postgres executing the same migrations production runs
- Production redeploy after the AWS EC2/RDS deletion — pivoted from Oracle Cloud (structurally capacity-constrained) to a Netcup VPS, with Neon serverless Postgres, self-hosted resource-capped Redpanda, Caddy automatic HTTPS, and a full GitHub Actions CI/CD pipeline (build → Flyway-verify → deploy → cleanup)
- Six mock-up-vs-API feature gaps closed (board creation, column deletion, task/column ordering, nested full-board read, per-user theme, subtask locking) plus a monotonic Snowflake-style `eventId`
- A test-suite reorganization (support/ package split, tier downgrades where a real socket/Kafka wasn't needed) and a frontend-integration-readiness pass (RFC 7807 envelope, 401/403 split, CORS, Board locking, adversarial security test coverage)

### What Worked
- Tracer-first sequencing continued to pay off at larger scale — Phase 4's Avro cutover, Phase 05-04's manual end-to-end deploy before CI/CD automation, and Phase 06-01's Flyway V5 tracer before the rest of that phase's feature work all followed the same prove-once-then-extend shape v1.0 established
- Live-infrastructure verification kept finding real bugs mocked tests structurally can't: `cleanup-old-images`' Docker Hub auth (two separate bugs, both only visible on a real API call), a Redpanda memory-limit boundary that only broke on an actual container restart, and a genuine `POST /logout` 500 a live `/claude-security` scan surfaced
- Deliberately choosing to independently re-verify rather than override past a gap, twice in one milestone-close pass (Phase 5's stale checkpoint bookkeeping, Phase 07.1's missing VERIFICATION.md) — both driven by tooling signals (`gsd_run query init.manager`) rather than manual inspection, which is what made them findable at all

### What Was Inefficient
- **The exact v1.1 lesson recurred, twice, within this single milestone.** Phase 07.1 (9/9 plans, all summarized) reached milestone-close with no `VERIFICATION.md` ever produced — the identical failure mode v1.1's retrospective already named ("execution completeness ≠ verification completeness"). Separately, Plan 05-06 halted at a genuine checkpoint, was resolved by two follow-on commits in a later session, and the SUMMARY/todo/STATE bookkeeping was never updated to match — a related but distinct gap (verification artifact exists and is stale, rather than missing outright). Both were only caught by deliberately running readiness tooling before archiving, not by trusting `has_summary: true` or a phase's own narrative.
- A tooling bug in `gsd-tools`' STATE.md progress-percent writer was filed mid-milestone (real, reproduced twice) rather than fixed — the workaround was manual STATE.md correction each time it recurred, which is exactly the kind of drift this milestone's close then had to spend real effort reconciling.
- SEED-001 (Confluent Schema Registry) sat with `status: dormant` in its own tracking file for the entire milestone despite being fully delivered by Phase 4 in the first week — nothing downstream depended on that field being correct, but it meant the milestone-close artifact audit flagged a fully-shipped feature as an open item, adding noise to a process step meant to catch real gaps.

### Patterns Established
- Independent `gsd-verifier` dispatch as the standard way to close a "plans done, verification missing/stale" gap, rather than either overriding past it or hand-declaring completion — used twice this milestone (Phase 5, Phase 07.1), both times finding zero real gaps but producing a durable, evidence-backed `VERIFICATION.md` that didn't exist before
- When a plan halts at a genuine human-only checkpoint and a later session resolves it via code-only fixes, treat the SUMMARY/todo/STATE reconciliation as its own explicit step, not an assumed side effect of the fix commits landing — the fix being live and correct is not the same as the planning record reflecting that
- Milestone-close readiness checks (`init.manager`, `audit-open`) are worth running even when the operator's own mental model says "everything's done" — every phase-level gap this milestone's close caught was invisible from ROADMAP.md/STATE.md's own prose, only visible via the structured tooling queries

### Key Lessons
1. **A lesson from a prior milestone's retrospective is not automatically internalized process** — v1.1 explicitly named "execution completeness ≠ verification completeness" as a lesson, and v1.2 reproduced the identical gap on a different phase. Naming a lesson in a retrospective doesn't prevent recurrence; only a structural check run at the right time (milestone-close readiness, not just phase-close) reliably catches it.
2. **Tracking-file staleness (a seed's `status`, a todo's location, a checkpoint's narrative) accumulates silently across a long milestone and is cheapest to fix at the first sign of drift, not batched at milestone-close** — none of this milestone's bookkeeping gaps were hard to fix once found, but finding all of them at once during close cost more total effort than fixing each as it happened would have.
3. **Live evidence beats narrative confidence at every scale, from a single CI job to an entire phase** — the pattern that resolved Plan 05-06's Docker Hub bug (read the actual job log, not the commit message) is the same pattern that resolved Phase 07.1's missing verification (run the actual tests, don't trust the SUMMARY) and the same one that caught SEED-001's stale status (check the shipped feature against the tracking file, don't assume they stayed in sync).

### Cost Observations
- Sessions: many, spanning roughly two weeks (2026-08-03 → 2026-08-17), including several single-topic "quick task" sessions interleaved between phases (matching v1.1's pattern)
- Notable: milestone-close alone required two full `gsd-verifier` dispatches (Phase 5, Phase 07.1) beyond the phases' own original execution — a direct cost of the verification-completeness gap recurring, not inherent to the milestone's feature scope

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1.0 | 1 | 1 | First milestone — established the tracer-then-reuse plan-sequencing pattern for symmetric entity work (Task then Column) |
| v1.1 | several | 2 | Extended tracer-first sequencing to producer/consumer Kafka slices; surfaced a real process gap (a phase can finish execution with zero verification) that the pre-milestone-close audit is now the catch for |
| v1.2 | many (~2 weeks) | 7 | Largest milestone by phase/plan count so far; the v1.1 verification-completeness gap recurred twice (Phase 07.1's missing VERIFICATION.md, Plan 05-06's stale-but-resolved checkpoint) — caught both at milestone-close via `init.manager`/`audit-open` readiness tooling rather than by trusting phase narratives |

### Cumulative Quality

| Milestone | Tests | Coverage | Zero-Dep Additions |
|-----------|-------|----------|-------------------|
| v1.0 | 118+ (full suite) | Not tracked | 0 |
| v1.1 | 178 (full suite, E2E included) | Not tracked | 0 (spring-kafka/Testcontainers-Kafka were already anticipated by the epic spec) |
| v1.2 | 278 at Phase 7 close, growing further through 07.1 (118 tests independently re-run live across 6 classes at milestone-close verification, 0 failures); JaCoCo ratchet gate added (INSTRUCTION/LINE≥0.90, BRANCH≥0.75) | Ratcheted via JaCoCo (see above) | Flyway, Testcontainers PostgreSQL, gradle-avro-plugin/Avro, OWASP dependency-check, gitleaks — all directly load-bearing for this milestone's own goals (schema governance, prod-parity testing, deploy security), not incidental |

### Top Lessons (Verified Across Milestones)

1. Tracer-first plan sequencing (prove the pattern once end-to-end, then mechanically reuse for symmetric entities) reduces deviations in follow-on plans — v1.0 Plan 01 had 4 auto-fixed deviations, Plan 02 had 0.
2. Execution completeness (`has_summary: true`) and verification completeness (`VERIFICATION.md` exists, passed, and still matches reality) are different facts, and naming this as a lesson doesn't prevent recurrence — v1.1 Phase 2 had the gap once; v1.2 reproduced it twice on two different phases despite the standing lesson. Only a structural check run at milestone-close (not phase-close alone) has reliably caught it so far.
3. Live-infrastructure verification (a real broker, a real running stack, a real CI job log) finds a class of bug — bean-resolution ambiguity, conditional-bean suppression, auth-header/pagination mismatches on a real third-party API — that mocked tests and code review both structurally miss, regardless of how carefully either is done. This held at every scale tried so far: a single Kafka bean (v1.1), a whole production deploy pipeline (v1.2).
