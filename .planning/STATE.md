---
gsd_state_version: 1.0
milestone: v1.2
milestone_name: Infra Migration & Schema Registry
status: Awaiting next milestone
stopped_at: "v1.2 (Infra Migration & Schema Registry) fully archived: Phase 07.1 independently re-verified (was missing VERIFICATION.md despite 9/9 plans complete -- 10/10 goal truths, 118 tests run live, 0 gaps), SEED-001's stale dormant status corrected, all 31 open pending-todo/quick-task items acknowledged at close, ROADMAP.md collapsed to milestone-grouped summary, PROJECT.md given a full evolution review, RETROSPECTIVE.md updated, and this file (STATE.md) trimmed of the v1.0-v1.2 per-plan decision log and quick-task table now that both are preserved in git history and .planning/milestones/v1.2-phases/. Ready for /gsd-new-milestone."
last_updated: "2026-08-17T11:35:00.000Z"
last_activity: 2026-08-17
last_activity_desc: "Milestone v1.2 completed and archived -- all 7 phases (4, 04.1, 04.2, 5, 6, 7, 07.1) verified, 39/39 plans, 107 tasks."
progress:
  total_phases: 7
  completed_phases: 7
  total_plans: 39
  completed_plans: 39
  percent: 100
current_phase: null
current_phase_name: null
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-17)

**Core value:** v1.0 through v1.2 shipped the backend-depth showcase and made the project reachable and cheaply/reliably deployable again on Netcup + Neon + self-hosted Redpanda after the AWS EC2/RDS deletion. The backend is now feature-complete against its own mock-ups and live in production.
**Current focus:** v1.2 milestone complete and archived — awaiting `/gsd-new-milestone` to scope what's next

## Current Position

Phase: Milestone v1.2 complete
Plan: —
Status: Awaiting next milestone
Last activity: 2026-08-17 — Milestone v1.2 completed and archived

## Performance Metrics

v1.0–v1.2 velocity/per-plan detail archived at milestone close — see `.planning/RETROSPECTIVE.md`'s Cost Observations per milestone and `.planning/milestones/v1.2-phases/` for individual plan SUMMARY.md files (each carries an `actuals` frontmatter block with tokens/tasks/commits). This section resets per milestone rather than accumulating a project-lifetime table.

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md's Key Decisions table (current milestone's headline decisions) and `.planning/RETROSPECTIVE.md` (per-milestone what-worked/what-was-inefficient analysis). The full per-plan decision log for v1.0–v1.2 (roughly 130 entries) was cleared from this file at v1.2's milestone close — it's fully preserved in `git log` and the archived phase artifacts under `.planning/milestones/v1.2-phases/`. Nothing was lost; this file just stopped being the right place to keep re-reading it.

No decisions recorded yet for the next milestone.

### Pending Todos

- [minor] Create a sequence diagram documenting the full system flow — deferred until all functional epics of the backend modernization plan are complete and the project is ready for frontend hand-off. See `.planning/todos/pending/2026-08-01-create-sequence-diagram-documenting-full-system-flow-for-fro.md`.
- [minor] Bump Java version from 21 to 25 (current LTS) — build.gradle toolchain, Dockerfile (both stages), and CI `java-version` all pinned to 21; not urgent (21 LTS supported until ~2028), but worth doing proactively. See `.planning/todos/pending/2026-08-01-bump-java-version-from-21-to-25-current-lts.md`.
- [minor] Decide E2ETest-suffix vs. `fastTest`-filter coupling — Phase 7 downgraded 12 classes to the in-process tier but kept the `E2ETest` suffix, since `build.gradle`'s `fastTest` task (run by the pre-commit hook) excludes tests by that literal name pattern; renaming would silently change what runs pre-commit. Three framed options recorded. See `.planning/todos/pending/2026-08-09-decide-e2etest-suffix-vs-fasttest-filter-coupling.md`.
- [minor] Enable virtual threads in Spring Boot config (`spring.threads.virtual.enabled=true`) — evaluate JDBC/Hibernate and Spring Session JDBC blocking-call pinning risk first. See `.planning/todos/pending/2026-08-02-enable-virtual-threads-in-spring-boot-config.md`.
- [minor] Add a dependency vulnerability scan (OWASP dependency-check or similar) — no scan exists today despite several manually-pinned third-party libs. See `.planning/todos/pending/2026-08-03-add-dependency-vulnerability-scan.md`. *(Note: this may already be closed by 260813-q1i — check before re-triaging.)*
- [minor] Evaluate PMD/Checkstyle/SpotBugs — likely redundant given Error Prone + ArchUnit + `docs/CODE_STYLE.md`; only revisit if a concrete gap surfaces. See `.planning/todos/pending/2026-08-03-evaluate-pmd-checkstyle-spotbugs.md`.
- [minor] Explore an alert-service integration as a separate microservice — speculative/theoretical, not scoped; a genuine second consumer of `kanban.activity` if pursued. See `.planning/todos/pending/2026-08-04-explore-alert-service-as-a-separate-microservice-for-tech-ex.md`.
- [minor] Revisit Java code comment and JavaDoc verbosity policy. See `.planning/todos/pending/2026-08-11-revisit-java-code-comment-and-javadoc-verbosity-policy.md`.
- [minor] `UpdateBoardRequestDTO.name`'s optionality rests on the same unexamined assumption D-02 (260811-ufu) declined to accept for `UpdateColumnRequestDTO`. See `.planning/todos/pending/2026-08-11-updateboardrequestdto-name-optionality-rests-on-same-unex.md`.
- [minor] `TaskMovedEvent` carries no position, unlike `ColumnReorderedEvent`. See `.planning/todos/pending/2026-08-11-taskmovedevent-position-asymmetry-not-fixed-in-s5e-fork-d-e.md`.
- [minor] `activity_log` has no retention policy, and subtask-completion events materially accelerate its growth rate. See `.planning/todos/pending/2026-08-11-unbounded-activity-log-growth-rate-materially-accelerated-by-s.md`.
- [minor] `ActivityAction` enum lives in `entity/` but is used across 3 layers — misplaced relative to CLAUDE.md's package convention. See `.planning/todos/pending/2026-08-11-activityaction-enum-misplaced-in-entity-package.md`.
- [minor] Add OpenAPI breaking-change detection to CI (`oasdiff` against a checked-in baseline). See `.planning/todos/pending/2026-08-11-add-openapi-breaking-change-detection-to-ci.md`.
- [minor] `POST /signup`'s `Location` header names a resource URI with no `GET` handler yet. See `.planning/todos/pending/2026-08-12-signup-location-header-points-at-a-uri-with-no-get-handler.md`.
- [minor] 4 remaining `ResponseEntity.created(request.getRequestURI())` sites diverge from signup's fixed `Location` pattern. See `.planning/todos/pending/2026-08-12-four-remaining-created-location-sites-now-diverge-from-signup.md`.
- [minor] **Add a nonprod/staging environment and wire Playwright E2E tests against a real (non-mocked) deploy** — candidate direction for the next milestone (raised 2026-08-17). See `.planning/todos/pending/2026-08-12-add-nonprod-staging-environment-and-playwright-e2e-ci-gate.md`.
- [minor] `docs/CODE_STYLE.md` rule 4's MockMvc-shortcut refusal-envelope claim is falsified by 260813-m9x's measurement. See `.planning/todos/pending/2026-08-13-code-style-rule-4-refusal-envelope-claim-falsified-by-m9x.md`.
- [minor] Two independent session-concurrency enforcers coexist. See `.planning/todos/pending/2026-08-13-two-independent-session-ceiling-enforcers-coexist.md`.
- [security] Audit penetration-testing and security coverage end-to-end against a checklist like OWASP API Security Top 10 (CSRF posture, signin brute-force/rate-limiting, full-depth IDOR, security response headers, DTO mass-assignment). See `.planning/todos/pending/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md`.
- [minor] Ratchet `dependencyCheckAnalyze`'s `failBuildOnCVSS` after a real, CPE-matched baseline exists. See `.planning/todos/pending/2026-08-13-ratchet-failbuildoncvss-after-a-real-dependency-check-baseline.md`.
- [minor] Add `package-ecosystem: "github-actions"` to `.github/dependabot.yml` — was deliberately deferred until Phase 5's deploy.yml rewrite settled; **that condition is now met (Phase 5 shipped 2026-08-17)**, this is actionable. See `.planning/todos/pending/2026-08-13-add-github-actions-ecosystem-to-dependabot-after-deploy-rewrite.md`.
- [minor] Expand README into a full project architecture showcase. See `.planning/todos/pending/2026-08-16-expand-readme-into-a-full-project-architecture-showcase.md`.

*(Full list is 20 items as of v1.2 close — see `.planning/todos/pending/` directly for the complete, current set; this is a representative summary, not necessarily exhaustive going forward.)*

### Blockers/Concerns

None currently open. All blockers recorded during v1.2 (Oracle A1 Flex tenancy sizing, Avro/sealed-interface mapping design, BACKWARD-vs-FULL compatibility mode, production Kafka/Redpanda config) were resolved during the milestone — see `.planning/milestones/v1.2-ROADMAP.md` and `.planning/RETROSPECTIVE.md` for how each was settled.

### Roadmap Evolution (v1.0–v1.2, for reference)

- Phase 04.1 inserted after Phase 4: Flyway database migration implementation (URGENT)
- Phase 04.2 inserted after Phase 4: Testcontainers Postgres, drop H2 — pulled forward ahead of Phase 5 so the Flyway migrations were CI-exercised before the Neon cutover (URGENT)
- Phase 6 added: Mock-up Feature Gap Closure — un-defers `GET /boards/{boardId}/full`
- Phase 7 added: Restructure test folder
- Phase 07.1 inserted after Phase 7: Address hard blockers and inconsistencies from the frontend-integration-readiness audit (URGENT)
- Full detail: `.planning/milestones/v1.2-ROADMAP.md`

## Deferred Items

Items acknowledged and carried forward:

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Endpoint | `GET /boards/{boardId}/full` nested read (FULL-01..03) | Shipped — Phase 6 (GAP-04), 2026-08-09 | 2026-07-31 |
| Epics | Modernization Epics 3–7 (Flyway/OpenAPI, Redis, Testcontainers project-wide, Observability, K8s) | Partially delivered as v1.2 Phase 04.1/04.2; remainder deferred to future milestones | 2026-07-31 |
| Kafka | Production (EC2) Kafka deployment (KAFKA-V2-01) | Superseded — shipped as self-hosted Redpanda on Netcup, Phase 5 | 2026-08-01 |
| Kafka | Cursor/keyset pagination on activity feed (PAGE-V2-01) | Deferred to v2 | 2026-08-01 |
| UAT | Phase 3 human-verification item (production DDL bridge script) | Superseded — deploy target deleted, addressed by v1.2 Phase 5 (INFRA-06) | 2026-08-03 |
| Seed | SEED-001 Confluent Schema Registry (Avro/Protobuf) | Shipped — Phase 4, corrected from stale `dormant` status at v1.2 close | 2026-08-04 |
| Infra Polish | INFRA-V2-01..03 (full observability stack, blue-green deploys, multi-broker Redpanda HA) | Deferred to v2 | 2026-08-03 |
| Schema Registry Polish | SCHEMA-V2-01..02 (pre-merge schema-compatibility CI check, documented compatibility-mode rationale) | Deferred to v2 | 2026-08-03 |
| Quick task | 260801-p03, 260802-rq5, 260802-ryf (all missing/research-only summaries) | Acknowledged at v1.1 close, re-acknowledged at v1.2 close, still open | 2026-08-17 |
| Quick task | 260816-hn1 (audit flagged status "unknown") | False positive — SUMMARY.md exists, work is complete (#48, gitleaks scanning, commit 393d0d9); audit tool's status classifier doesn't recognize this SUMMARY's frontmatter shape | 2026-08-17 |
| Todo | 20 pending todos (see Pending Todos above) | Acknowledged at v1.2 close — none block v1.2's shipped scope; remain in `.planning/todos/pending/` for triage in a future milestone | 2026-08-17 |

**Known verification overrides: 14 total (see above)**

## Session Continuity

Last session: 2026-08-17 (resumed via /gsd-resume-work)
Stopped at: v1.2 milestone fully archived. Reconciled Plan 05-06's stale checkpoint bookkeeping against already-live commits, independently re-verified Phase 5 and Phase 07.1 (the latter had no VERIFICATION.md despite being complete), fixed SEED-001's stale tracking status, acknowledged all open backlog items, archived ROADMAP.md/REQUIREMENTS.md to `.planning/milestones/v1.2-*`, gave PROJECT.md a full evolution review, updated RETROSPECTIVE.md, and trimmed this file's accumulated per-plan history now that it's preserved elsewhere.
Resume file: None

## Operator Next Steps

- Start the next milestone with `/gsd-new-milestone`. Candidate direction already raised: a nonprod/staging environment + CI gate for a separate frontend repo's Playwright E2E suite (see the Pending Todos entry above) — feasibility not yet researched.
