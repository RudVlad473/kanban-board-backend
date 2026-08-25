# Kanban Board Backend — Epic 2 Completion

## What This Is

A Spring Boot 3.5.16 / Java 21 REST API backend for a Kanban board application (users → boards → columns → tasks → subtasks), with session-based authentication, ownership-based access control, optimistic locking on every mutable entity (Board/Column/Task/Subtask), and a Kafka-backed, event-driven per-board activity log governed by a versioned Avro Schema Registry. Originally scoped to finish **Epic 2** of the [backend modernization plan](../docs/plans/backend-modernization/README.md) (v1.0); v1.1 additionally delivered Epic 1 (the activity feed). v1.2 closed the schema-evolution risk (Schema Registry), redeployed the app to a real production target (Netcup VPS + Neon + self-hosted Redpanda + Caddy + GitHub Actions CI/CD) after the AWS EC2/RDS deletion, closed the six concrete feature gaps between the API and the project's own Kanban mock-ups (board creation, column deletion, task/column ordering, a nested full-board read, per-user theme persistence, subtask locking), and fixed a set of frontend-integration-readiness blockers (CORS, RFC 7807 error envelope, 401/403 split, Board optimistic locking, consistent 201s, adversarial security test coverage). The project is a personal/portfolio showcase; it is now live in production and API-complete against its own mock-ups, with its next milestone not yet scoped.

## Core Value

v1.0 through v1.2 shipped the backend-depth showcase (JPA/Hibernate optimistic locking, Kafka event sourcing with dead-letter reliability, idempotent consumption, Avro schema governance — all proven against real Postgres/Kafka, not mocks) and, after the AWS EC2/RDS deploy target was deleted (2026-08-03, cost-risk driven), made the project reachable and cheaply/reliably deployable again on Netcup + Neon + self-hosted Redpanda. The backend is now feature-complete against its own mock-ups and live in production; the differentiator going forward is less "does the backend do X" and more "is the whole system, including a real frontend against this real deploy, provably reliable."

## Current State

v1.3 (Nonprod Environment & CI Hardening) shipped 2026-08-25 — Phases 8, 9, and 10 all complete
and verified (13/13 plans, zero gaps). The backend now has an isolated, continuously-deployed
nonprod environment colocated on the existing Netcup VPS, and the CI/deploy pipeline's accumulated
hardening debt (secret scanning, action digest-pinning, Gradle supply-chain integrity, session
cookie `Secure` flag, README architecture showcase) is closed. Full milestone detail archived at
[`milestones/v1.3-ROADMAP.md`](milestones/v1.3-ROADMAP.md) and
[`milestones/v1.3-REQUIREMENTS.md`](milestones/v1.3-REQUIREMENTS.md).

Next milestone not yet scoped — run `/gsd-new-milestone` to define it.

## Requirements

### Validated

- ✓ Board/Column/Task/Subtask CRUD with ownership-based access control — existing
- ✓ Session-based authentication (Spring Security + JDBC session store) — existing
- ✓ Cascading deletes (board → columns → tasks → subtasks) — existing
- ✓ Bulk task/column delete N+1 fix (batched JPQL deletes, `entityManager.flush()/clear()`) — Epic 2, done 2026-07-31
- ✓ Query-count regression guard (`OwnershipVerifierServiceTest.QueryCountTest`) — Epic 2, done 2026-07-31
- ✓ Confirmed non-issue: `OwnershipVerifierService` chain already resolves in 1 query via EAGER joins (Finding 1) — no code change needed
- ✓ Optimistic locking on `TaskEntity` and `ColumnEntity` (`@Version`), required client-supplied version checked explicitly before mutation on both `TaskService.updateById` and the new `ColumnService.updateById`, `GlobalExceptionHandler`'s 423→409 fix, and a Lombok equals/hashCode exclusion on `ColumnEntity.version` — Phase 1, done 2026-08-01. Proven end-to-end by real HTTP E2E tests (`TaskLockingE2ETest`, `ColumnLockingE2ETest`).
- ✓ New `PUT /boards/{boardId}/columns/{columnId}` endpoint — Phase 1, done 2026-08-01 (previously missing; added so `ColumnEntity`'s `@Version` is reachable/testable via the API)
- ✓ Local Kafka dev stack (`docker-compose.yml`: postgres + KRaft-mode `apache/kafka-native` + app, health-gated) — v1.1 Phase 2, done 2026-08-01
- ✓ `PATCH /tasks/{taskId}/move` endpoint reusing the Phase 1 explicit-version-compare convention (409 stale version, 400 cross-board) — v1.1 Phase 2, done 2026-08-01
- ✓ All 5 domain mutation types (task create/move/delete, board create, column create) publish typed, sealed `ActivityEvent` records via `KafkaEventPublisher` only after transaction commit — v1.1 Phase 2, done 2026-08-01. Proven live by killing the Kafka broker mid-request and confirming mutations still succeed with the failure logged, never swallowed.
- ✓ Idempotent, deduplicated activity-log consumer (`ActivityLogConsumer`/`ActivityLogRecorder`) with a database-unique-constraint-backed dedup and a dead-letter topic (byte-fidelity preserved) for poison messages — v1.1 Phase 3, done 2026-08-02. Proven against a real Testcontainers Kafka broker, not mocks.
- ✓ `GET /boards/{boardId}/activity` — paginated, ownership-verified, deterministic newest-first read endpoint — v1.1 Phase 3, done 2026-08-02
- ✓ Avro schemas for all 5 `ActivityEvent` types with enforced BACKWARD compatibility, build-time registration (not producer auto-registration), DLT byte-fidelity under Avro, and a real-historical-data rehearsal — v1.2 Phase 4, done 2026-08-04
- ✓ Flyway-managed domain schema: V1–V4 reconstructing the schema's real evolution, `ddl-auto=validate` outside the test profile so Hibernate can no longer create or alter — v1.2 Phase 04.1, done 2026-08-05
- ✓ Whole test suite runs against a Testcontainers PostgreSQL 16 instance whose schema is built by the same Flyway V1–V4 migrations production runs; `com.h2database:h2` removed outright with no fallback profile — v1.2 Phase 04.2, done 2026-08-06. Verified 17/17 must-haves against the live codebase; schema provenance is enforced by a standing test (`FlywaySchemaProvenanceTest`) asserting Flyway-only artifacts and zero Hibernate-generated constraint names, so a silent regression to Hibernate-built DDL fails the build. Measured ~19s/6.1% **faster** than the like-for-like H2 baseline, inverting the expected cost.
- ✓ Production redeploy on a cost-guarded stack (Netcup VPS, pivoted from Oracle Cloud A1 Flex after 200+ automated provisioning attempts hit structural capacity constraints) — Neon serverless Postgres, self-hosted resource-capped Redpanda, Caddy automatic HTTPS, GitHub Actions CI/CD with a pre-merge Flyway verification gate — v1.2 Phase 5, done 2026-08-17. All 8 INFRA requirements verified live by an independent `gsd-verifier` pass: real HTTPS health check, an off-VM external port scan (three independent full-range passes confirming only 22/80/443 reachable), Docker log-rotation proven deterministic via a throwaway container, AWS-era secrets confirmed absent, and Docker Hub tag pruning fixed and live-verified (two bugs: Basic-auth rejection, then pagination) after an initial same-day checkpoint halt.
- ✓ Six mock-up-vs-API feature gaps closed (`POST /boards`, `DELETE /boards/{boardId}/columns/{columnId}` with cascade, task/column position + reorder endpoints, `GET /boards/{boardId}/full` single-round-trip nested read, per-user theme persistence, subtask `@Version` optimistic locking) plus a Snowflake-style time-ordered `activity_log.eventId` replacing random UUIDs — v1.2 Phase 6, done 2026-08-09. GAP-01..07, all proven over real HTTP.
- ✓ Test suite reorganized: 5 shared fixture files split into a `support/` package, 3 auth/session test classes merged into one, 13 real-socket `E2ETest` classes downgraded to the cheaper in-process MockMvc tier where a real socket/Kafka broker wasn't actually needed (9 stayed on the expensive tier) — v1.2 Phase 7, done 2026-08-09. 278/278 tests, zero shrinkage, zero production-code changes.
- ✓ Frontend-integration-readiness blockers closed: every error response converged on one RFC 7807 `ProblemDetail` envelope with a stable `code`; 401 (unauthenticated) and 403 (ownership-forbidden) no longer overlap; CORS wired for credentialed local frontend dev origins; `@Valid` turned on for signin/signup; every resource-creating POST returns 201; Board gained the same `@Version` optimistic locking Column/Task/Subtask already had; `InjectionAttemptTest`/`AuthorizationGatingTest` added as permanent adversarial and auth-gating coverage — v1.2 Phase 07.1 (inserted), done 2026-08-17. 118 tests verified live, zero gaps.
- ✓ Nonprod environment live and resettable: colocated Compose stack (app + second Redpanda broker) on the existing Netcup VPS, isolated Neon branch, real HTTPS on its own DuckDNS subdomain, CORS wired for the eventual frontend origin, and Redpanda's memory floor measured live via iterative restart cycles — NONPROD-01..06, v1.3 Phase 8, done 2026-08-18.
- ✓ Nonprod continuous deploy on every push to master, fully isolated from production: scoped `production`/`staging` GitHub Environments (repository-level deploy secrets swept to just `NVD_API_KEY`), a separate Docker Hub repository per environment with independent retention sweeps, a bounded health-check gate that fails the run visibly on a dead nonprod stack, and automated Avro schema registration against both registries (nonprod gated ahead of its own app start; production automated in place, zero behavior change to its live deploy script) — CI-01..05, v1.3 Phase 9, done 2026-08-19. Every acceptance criterion live-verified against real infrastructure (not just statically checked), including finding and fixing a real `appleboy/ssh-action` fail-fast defect (missing `set -e`) discovered mid-verification.
- ✓ Generated OpenAPI spec declares the `ProblemDetail` error envelope on every operation via a global customizer, with an automated regression guard — API-01, v1.3 Phase 9, done 2026-08-19 (scope exception: API-contract completeness, not CI/deploy work, folded in by explicit user decision after a downstream frontend consumer discovered the gap live).
- ✓ CI, secret-scanning, and session-cookie hardening: Dependabot `github-actions` ecosystem entry (grouped, softened per D-Dependabot-cost); digest-pinned TruffleHog verified-credential gate alongside gitleaks, plus a pre-commit `case`-branch fix for out-of-tree worktrees; both `appleboy/*` SSH actions pinned to immutable digests with a dated risk-acceptance comment for the first-party actions kept tag-trusted; Gradle cache in `run-tests`; Gradle distribution SHA-256 pin + `wrapper-validation` + a fully-covering `verification-metadata.xml`; `security-scan.yml`'s stale comment/action-version drift and its `NVD_API_KEY` resolution bug fixed; `Secure` set unconditionally on the session cookie in both Spring profiles; README restructured into a 269-line, 12-section production-reality architecture showcase — HARDEN-01..08, v1.3 Phase 10, done 2026-08-19. All 8 requirements verified live (8/8), zero gaps.

### Active

No active milestone — v1.3 shipped 2026-08-25. Run `/gsd-new-milestone` to scope the next one.

### Out of Scope

- Epics 4, 6, 7 (Redis, Observability, Kubernetes) — deferred to future milestones. Two of the originally-deferred epics have since been pulled forward and delivered as inserted v1.2 phases: Epic 3's Flyway half (Phase 04.1) and Epic 5 (Testcontainers, drop H2 — Phase 04.2). Epic 3's OpenAPI-polish half remains deferred and is the only part of Epic 3 still outstanding.
- Re-fixing `OwnershipVerifierService`'s chain of `findById()` calls (Finding 1) — measured and confirmed non-issue; already resolves in 1 query via EAGER join chain, no work needed
- Full microservice extraction of the activity-log consumer into a separate deployable service — the in-process `@KafkaListener` already demonstrates the event-driven pattern; a possible later epic, not this one
- GraphQL, Elasticsearch, WebFlux/reactive, Kotlin, Oracle/PL-SQL, Angular — explicitly excluded by the modernization plan as niche/low-ROI for this project's scope
- Cursor/keyset pagination on the activity feed (PAGE-V2-01) — offset `Pageable` shipped in v1.1 for API consistency; revisit only if activity-log scale becomes a real concern
- Field-level diff logging, DLT replay/reprocessing admin tooling, real-time push (WebSocket/SSE) of activity updates, retention/archival policy, generic/pluggable event schema — all explicitly excluded from v1.1's scope per REQUIREMENTS.md's Out of Scope table; reasons still valid, no signal any of these are needed yet
- Per-PR ephemeral environments, ephemeral Neon branch per E2E run, second Neon project — rejected for v1.3 with written rationale; nonprod's own continuous-deploy stack already gives a stable, always-current Playwright target
- `repository_dispatch` from this repo into the frontend repo's Playwright workflow (FRONTEND-DISPATCH-V2) — hard-blocked, the frontend repo has no workflow file to dispatch into yet
- Universal digest-pinning of every third-party GitHub Action (narrowed 2026-08-19, D-05) — scoped to just the two `appleboy/*` actions holding real SSH keys; first-party GitHub/Docker actions kept tag-trusted behind a documented risk-acceptance comment

## Context

- This is a personal/portfolio project; the modernization plan's stated purpose is closing real technology gaps in the backend stack (see [README.md](../docs/plans/backend-modernization/README.md)).
- Full epic specs: [02-n-plus-one-optimistic-locking.md](../docs/plans/backend-modernization/02-n-plus-one-optimistic-locking.md) (v1.0), Epic 1 Kafka activity feed (v1.1, archived requirements at `.planning/milestones/v1.1-REQUIREMENTS.md`).
- Progress notes and gotchas already hit during the completed portion of Epic 2 are logged in [STATUS.md](../docs/plans/backend-modernization/STATUS.md) — includes a real gotcha about mixing derived `deleteAllByXIn` (fetch-then-remove) with bulk JPQL deletes causing FK violations, and stale persistence-context entities requiring `flush()`/`clear()`.
- Codebase map available at `.planning/codebase/` (ARCHITECTURE.md, STACK.md, CONVENTIONS.md, TESTING.md, INTEGRATIONS.md, CONCERNS.md, STRUCTURE.md) — read before planning.
- Existing convention: services use `@Autowired` field injection (not constructor) to sidestep circular bean dependencies between Board/Column/Task/Subtask/Ownership services.
- Existing convention: DTOs are flat (no nested entity graphs) specifically to avoid `LazyInitializationException` — the deferred `/full` endpoint DTO will need deliberate nested structure, a departure from this pattern that should be justified explicitly.
- Phase 1's required auth-path fix (`UserAuthenticationProvider` propagating the actual authenticated principal instead of a broken bare-userId string) initially introduced a real security regression — the full `UserEntity` (with bcrypt `passwordHash`) flowing into the JDBC-backed session table. Caught by code review, fixed same-session (commit `c5fb656`) by using a minimal Spring Security `User` principal instead.
- **Infrastructure change (2026-08-03):** The operator deleted the project's AWS EC2 instance and its Postgres database, moving off AWS due to unpredictable pricing risk. This makes the "run the DDL bridge script against the real Postgres deploy target" human-verification step from v1.1 Phase 3 permanently moot — there is no longer a deploy target for it to run against (acknowledged in `03-VERIFICATION.md`'s Acknowledged Gaps and `03-UAT.md`). A v1.2 infra-migration milestone is next: Oracle Cloud Always Free (compute) + Neon (serverless Postgres, scale-to-zero, zero JPA/Hibernate code changes needed) + a self-hosted single-node Redpanda broker (Kafka-protocol-compatible, zero producer/consumer/DLQ code changes needed) + GitHub Actions CI/CD. That milestone will need its own equivalent pre-merge DDL verification step against the new deploy target.
- Phase 2 (Kafka Foundation, Domain Events & Move Endpoint) was executed successfully but its verification step was never run at the time — discovered during the pre-close audit for v1.1. Retroactively verified 2026-08-03: 15/15 must-haves passed against a live Docker Compose stack, no regressions to Phase 3.
- **v1.2 close (2026-08-17):** Two phases (6 "Mock-up Feature Gap Closure" and 07.1 "frontend-integration-readiness blockers") had genuinely completed all their plans but were missing this document's Validated entries and, for 07.1, a formal `VERIFICATION.md` altogether — discovered during milestone-close readiness checks (`gsd_run query init.manager`) rather than assumed clean. 07.1 was independently re-verified before milestone close (10/10 goal truths, 118 tests run live, zero gaps) rather than overridden past. `SEED-001` (Confluent Schema Registry) was also found still marked `dormant` in its own tracking file despite being fully delivered by Phase 4 — corrected to `implemented`. None of these were functional gaps in the shipped product, only planning-artifact bookkeeping that had drifted from reality.
- The app is now live in production at a real HTTPS domain (see `docs/INFRA_RUNBOOK.md` for current infra state) — any future phase touching deploy config, Docker Compose, or CI/CD should treat that as a real, traffic-serving target, not a rehearsal.
- **v1.3 close (2026-08-25):** STATE.md/ROADMAP.md had drifted stale — both still described Phase 10 as not started despite all 6 plans, summaries, and a passed `10-VERIFICATION.md` (8/8) already on disk. Two ad-hoc spikes run after Phase 10 shipped (`.planning/spikes/001-board-duplicate-name-409-override`, VALIDATED; `002-error-code-coverage-survey`, PARTIAL) investigated per-operation OpenAPI error-response overrides layered on `ProblemDetailOpenApiCustomizer`'s generic bucket; spike 001's own README claimed its experimental `BoardController.java` change and throwaway probe test were reverted/deleted, but they were still present uncommitted at milestone-close time — reverted and cleaned up as part of closing v1.3. Spike 002 flagged three concrete override candidates (ranked): `PATCH /tasks/{taskId}/move`'s misleading 400, `POST /signup`'s 409 (direct copy of spike 001's proven pattern), and `PUT /boards/{boardId}`'s two-cause 409 — none implemented yet, candidates for a follow-up quick task.
- Two directories under `.planning/quick/` are worth knowing about going forward: `260816-hn1` is fully complete (secret scanning) despite an audit false-positive on its free-text `duration` field; `260824-v7n` (install Docker natively inside WSL2) is a genuinely empty, abandoned directory — unrelated local dev-environment tooling, not a product requirement.

## Constraints

- **Tech stack**: Spring Boot 3.5.16, Java 21, Spring Data JPA/Hibernate, PostgreSQL for both production and tests (tests run against a Testcontainers-managed PostgreSQL instance executing the same Flyway migrations) — no new frameworks introduced for this scope
- **Production infra** (added v1.2): Netcup VPS (Docker Compose), Neon serverless Postgres, self-hosted single-node Redpanda (built-in Confluent-compatible Schema Registry), Caddy (automatic HTTPS), GitHub Actions CI/CD (build → Flyway-verify → deploy → cleanup, gated by fingerprint-pinned SSH)
- **Testing**: Match existing convention — unit tests for services/DTOs, integration tests (REST Assured) for controllers; query-count assertions via Hibernate `Statistics.getPrepareStatementCount()` (not `getQueryExecutionCount()`, which misses `findById()` calls)
- **PR discipline**: This work should remain reviewable as its own unit, consistent with the modernization plan's one-epic-per-PR intent
- **Format check**: `./gradlew spotlessCheck` and `./gradlew test` must pass (matches existing CI)

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Scope this GSD project to Epic 2 completion only, not the full 7-epic plan | User wants to resume exactly where prior work left off rather than commit to the full modernization roadmap right now | ✓ Good |
| Treat Finding 1 (ownership chain) as closed, no further code change | Already measured and confirmed as 1 query via EAGER join chain; re-opening would be wasted effort | ✓ Good |
| Narrow v1 to optimistic locking only; defer `/full` endpoint to v2 | User confirmed this narrower scope during requirements definition, after research showed both were independent, separately-sized pieces of work | ✓ Good |
| Client-supplied `version` required + explicitly compared server-side, not just relying on Hibernate's automatic `@Version` dirty-check | This codebase's update flow loads-then-saves fresh within one transaction, so the automatic check alone would never catch a stale-read conflict across separate requests — the explicit check is what actually delivers the phase's goal | ✓ Good |
| Manual DDL now, not deferred to Epic 3/Flyway | Deferring would leave production broken between merge and Epic 3 landing, since master auto-deploys on push | ✓ Good — script delivered; live-DB run superseded by AWS deletion, moved to v1.2 (see Context) |
| Continue phase numbering across milestones (v1.1 starts at Phase 2, not Phase 1) | Keeps a single, continuous phase history across the whole modernization plan rather than resetting per milestone | ✓ Good — though it produced a tooling false-positive at v1.1 close (a phase-completeness checker misread the already-shipped, archived Phase 1 as "unstarted"); required `--force` after manual verification, not a real gap |
| Split v1.1 into 2 coarse phases (producer, then consumer) instead of research's suggested 5 | Producer side is independently buildable/testable with a mocked `KafkaTemplate`; consumer side depends on producer's event contracts and needs a running pipeline | ✓ Good |
| Retroactively verify Phase 2 before v1.1 close, rather than skip it | Phase 2 had zero verification artifacts (executed but the verifier step never ran) — a real gap in project history, not acceptable to paper over | ✓ Good — 15/15 must-haves passed |
| Resolve the stale Phase 3 UAT blocker (prod DDL check) as superseded rather than force-passing it | The AWS deploy target it referenced was deleted; forcing it to "pass" would misrepresent what was actually verified | ✓ Good |
| Error Prone landed at "hard-gate main and test" (`allErrorsAsWarnings` removed, 5 checks promoted to ERROR on `compileTestJava`) rather than the todo's literal "just remove the demotion" | Measured that removing the demotion alone was a no-op (all 27 test findings were WARNING severity); promotion is what makes "hard-gate" true | ✓ Good — teeth-checked by reintroduce-and-revert |
| Pivoted production deploy target from Oracle Cloud A1.Flex to Netcup VPS Lite 2 | Oracle's A1 Flex free-tier ARM capacity proved structurally unavailable after 200+ automated provisioning attempts over 10+ hours across regions; Netcup's two-layer firewall model meets the same externally-verified 80/443-only intent | ✓ Good — fully re-verified, zero drift from recorded intent |
| Un-deferred `GET /boards/{boardId}/full` and folded it into v1.2 as Phase 6 (Mock-up Feature Gap Closure) rather than leaving it deferred to a hypothetical v2 | Closing the gap between the API and this project's own committed Kanban mock-ups was judged higher-value than an unscoped future milestone; also closes the last mock-up-vs-API gap, making the backend API-complete against its own design reference | ✓ Good — GET /full ships as a single chained LEFT JOIN FETCH round trip, no N+1 |
| Retroactively verified Phase 07.1 before milestone close rather than overriding the missing verification | `gsd_run query init.manager` flagged `verification_status=missing` for a phase whose plans were all complete — accepting that at milestone-close time would have shipped a formally-unverified phase into the historical record permanently | ✓ Good — 10/10 goal truths, 118 tests run live, zero gaps found |
| Nonprod colocates on the existing Netcup VPS rather than provisioning a second VPS | Live memory measurement (NONPROD-06, restart-ladder down to a 128M/300m floor) showed colocation fits; the second-VPS fallback was reserved only for if it didn't | ✓ Good — memory floor live-measured, not assumed |
| Kafka isolation via a second Redpanda broker instance, not topic-prefixing | The Avro registry's `RecordNameStrategy` keys compatibility history by class name, so prefixed topics on the same broker would still share the registry — a genuine isolation gap topic-prefixing alone can't close | ✓ Good |
| The backend does not gate its own production promotion on the frontend repo's E2E results | Inverted ownership: the frontend gates itself on nonprod reachability instead, since the frontend repo doesn't exist yet to dispatch into (FRONTEND-DISPATCH-V2 stays deferred) | ✓ Good |
| Digest-pinning narrowed to just the two `appleboy/*` SSH actions, not every third-party `uses:` in `deploy.yml` (D-05) | Those two hold real production/staging SSH keys; pinning every first-party GitHub/Docker action too would add churn without a matching blast-radius reduction — recorded via an explicit, dated risk-acceptance comment instead | ✓ Good |
| Dependabot's `github-actions` PRs grouped (softened) rather than accepted at raw volume | User chose grouping over accept-as-is when the Dependabot-cost trade-off was surfaced explicitly at a Phase 10 checkpoint, rather than left to the executor's provisional default | ✓ Good |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-08-25 after v1.3 milestone*
