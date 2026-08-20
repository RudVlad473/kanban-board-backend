---
gsd_state_version: 1.0
milestone: v1.3
milestone_name: Nonprod Environment & CI Hardening
current_phase: 10
status: completed
stopped_at: Phase 10 context gathered
last_updated: "2026-08-20T09:39:01.437Z"
last_activity: 2026-08-20
last_activity_desc: "Phase 9 closed out: plan 09-03 (Avro schema registration, CI-05) live-verified after finding and fixing a real appleboy/ssh-action fail-fast defect (missing set -e) mid-verification; code review (0 critical/4 warnings/3 info) and gsd-verifier goal check (6/6, no gaps) both passed; ROADMAP/STATE/REQUIREMENTS/PROJECT.md all updated and pushed"
state_head: df6844347fe7843305b62daae5aa6b6a82aa4fdf
progress:
  total_phases: 3
  completed_phases: 3
  total_plans: 13
  completed_plans: 13
  percent: 100
current_phase_name: ci-deploy-hardening
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-17)

**Core value:** The backend is feature-complete against its own mock-ups and live in production; the differentiator now is proving the whole system — including a real frontend against a real deploy — is reliable.
**Current focus:** Phase 10 — ci-deploy-hardening

## Current Position

Phase: 10
Plan: Not started
Status: All phases complete
Last activity: 2026-08-20 - Completed quick task 260820-giz: OWASP API Security Top 10 audit, 4 new todos filed

Progress: [███████░░░] 75%

## Performance Metrics

v1.0–v1.2 velocity/per-plan detail archived at milestone close — see `.planning/RETROSPECTIVE.md`'s Cost Observations per milestone and `.planning/milestones/v1.2-phases/` for individual plan SUMMARY.md files (each carries an `actuals` frontmatter block with tokens/tasks/commits). This section resets per milestone rather than accumulating a project-lifetime table.

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md's Key Decisions table and `.planning/RETROSPECTIVE.md`. The full per-plan decision log for v1.0–v1.2 was cleared at v1.2's close — preserved in `git log` and `.planning/milestones/v1.2-phases/`.

v1.3 decisions so far (from requirements/research, before any phase plan):

- Nonprod colocates on the existing Netcup VPS; a second ~€4/month VPS is the fallback only if live memory measurement (NONPROD-06) says colocation doesn't hold.
- Kafka isolation is a **second Redpanda broker**, not topic-prefixing — the Avro registry's `RecordNameStrategy` keys compatibility history by class name, so prefixed topics would still share the registry.
- The backend does **not** gate its own production promotion on the frontend repo's E2E results; the frontend gates itself on nonprod reachability.
- Phase numbering continues across milestones — v1.3 starts at Phase 8 (v1.2 ended at Phase 7 + inserted 07.1).
- Roadmap compressed research's 4 buildable phases to 3 (granularity `coarse`): RESET-01 folded into Phase 8 rather than standing alone, and the 8 HARDEN-* todos given their own phase (Phase 10) since they are independent of the nonprod build and two of them are not CI at all.

### Pending Todos

Phase 10 closed all eight HARDEN-* requirements plus the two Gradle supply-chain todos captured
2026-08-18. **Update (2026-08-20):** while auditing `.planning/todos/pending/` after Phase 10 went
green, 7 of the `resolves_phase: 10`-tagged todos were found still sitting in `pending/` despite
being satisfied on disk — moved to `completed/` with resolution notes (dependabot
`github-actions` ecosystem, digest-pinning, gradle cache in `run-tests`, `security-scan.yml`
stale comment/actions, README expansion, Gradle dependency verification metadata, Gradle wrapper
integrity validation). A separate, previously-untracked issue found the same day (`deploy.yml`
had no `paths-ignore` filter, so a docs-only push triggered a full redeploy) was fixed and closed
in the same pass (quick task `260820-euc`).

Still open and out of v1.3 scope (representative, see `.planning/todos/pending/` for the complete set — ~45 items):

- [security] IDOR chain consistency: nested path segments (`boardId`, `columnId`) are never cross-checked against the leaf resource's actual parent — a same-user chain-confusion variant `AuthorizationGatingTest` doesn't exercise (`2026-08-20-idor-same-user-chain-consistency-boardid-columnid-not-c.md`).
- [security] No rate limiting / volumetric brute-force guard on `POST /signin` (`2026-08-20-add-rate-limiting-to-signin-to-bound-brute-force-volume.md`).
- [security] No CSP anywhere; HSTS likely never emitted behind Caddy (no `forward-headers-strategy`) (`2026-08-20-security-response-headers-csp-and-unreliable-hsts-behind.md`).
- [minor] CSRF defense reasoning is sound but only half-verified — no test proves an actual cross-origin request is rejected (`2026-08-20-verify-csrf-defense-with-a-real-cross-origin-rejection-t.md`).
- [minor] Full-system sequence diagram for frontend hand-off; bump Java 21 → 25; enable virtual threads (JDBC/Hibernate pinning risk first).
- [minor] `E2ETest`-suffix vs. `fastTest`-filter coupling decision; two coexisting session-ceiling enforcers; `ActivityAction` enum misplaced in `entity/`.
- [minor] `activity_log` has no retention policy; `TaskMovedEvent` carries no position; `UpdateBoardRequestDTO.name` optionality assumption.
- [minor] OpenAPI breaking-change detection in CI; `POST /signup` `Location` points at a URI with no GET handler; 4 `Location` sites diverge from signup's pattern.
- [minor] Ratchet `dependencyCheckAnalyze`'s `failBuildOnCVSS` after a CPE-matched baseline; evaluate PMD/Checkstyle/SpotBugs; alert-service microservice exploration; JavaDoc verbosity policy.
- [security] Password composition regex requires ASCII-only character classes, blocking valid Unicode-only passwords and contradicting ASVS's own no-composition-rules guidance (`2026-08-20-password-composition-regex-blocks-unicode-only-pass.md`).
- [security] No password-change endpoint exists anywhere in the API — a password can never be changed once set (`2026-08-20-no-password-change-capability-exists-anywhere-in-api.md`).
- [security] No MFA/second-factor enrollment path; BCrypt password comparison is the sole authentication factor (`2026-08-20-no-mfa-second-factor-enrollment-path.md`).
- [security] Zero security-event logging on the authentication/access-control paths — a real credential-stuffing attempt leaves no forensic trail (`2026-08-20-no-security-event-logging-on-auth-and-access-control.md`).
- [infra] No remote log shipping, structured/UTC logging standard, or alerting on unusual activity — overlaps the existing unimplemented Prometheus+Grafana backlog item (`2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md`).
- [security] No self-service session revocation and no re-authentication gate before destructive actions like a board's cascading delete (`2026-08-20-no-session-revocation-or-reauth-before-destructive-act.md`).
- [security] Container runs as root — Dockerfile has no USER directive in either build or runtime stage (`2026-08-20-dockerfile-runs-as-root-no-user-directive.md`).
- [ci] No branch protection on master — confirmed live via `gh api`, no required reviews or status checks (`2026-08-20-no-branch-protection-on-master.md`).
- [ci] Production deploys get no automated post-deploy health check, unlike nonprod (`2026-08-20-no-prod-post-deploy-health-verification.md`).
- [security] Internal Kafka/schema-registry hop has neither SASL auth nor TLS (Docker-internal-only networking is a compensating control, not a fix) (`2026-08-20-internal-kafka-hop-has-no-sasl-auth-or-tls.md`).
- [security] No secrets vault for runtime production secrets (plaintext `.env.prod` on VM disk) and no stated rotation cadence (`2026-08-20-no-secrets-vault-for-runtime-prod-secrets-no-rotation.md`).
- [security] Swagger/OpenAPI docs are reachable in production with no profile gate (`2026-08-20-swagger-openapi-docs-reachable-in-prod-no-profile-gate.md`).
- [minor] Password length bounds undersized vs. ASVS; no breached-password check; no secret pepper on BCrypt; no auth-detail-change notifications (blocked on missing email infra); no Content-Type validation on REST endpoints; no formal data classification or self-service export/delete; no documented DB backup/restore runbook — see `.planning/todos/pending/` for the 7 minor-severity todos filed 2026-08-20.

**Update (2026-08-20, later same day):** 4 more small pending todos closed (quick task
`260820-g3u`) — the `docs/CODE_STYLE.md` rule 4 correction and the bare-static-import lint
question mentioned above are now resolved, plus `HistoricalActivityEventReconstructorTest` tagged
`@Tag("kafka")` and `ResetServiceE2ETest`'s flaky-in-CI race root-caused and fixed (an unawaited
`@Async` Kafka publish racing `resetService.resetAll()`'s own topic-trim step). Verifying the
flaky-test fix surfaced a genuine, separate gap in `gradle/verification-metadata.xml` (a missing
`guava-33.5.0-jre.pom` checksum) — fixed in the same pass; a new pending todo filed for the
still-missing CI staleness check that let it go undetected.

**Update (2026-08-20, later still):** the long-open OWASP API Security Top 10 audit todo (quick
task `260820-giz`) is closed — cited covered / assumed-covered-but-unverified / genuinely-untested
verdicts for all 10 categories, not just the 6 originally named. DTO mass-assignment and inventory
management came back adequate; dependency CVEs stayed cross-referenced to the existing todo family
(nothing new filed there). 4 new todos above capture the confirmed gaps, including one the original
todo's candidate list didn't name explicitly: `OwnershipVerifierService` validates ownership by
walking up from the leaf path id only, never cross-checking a nested route's other nominal
ownership-chain segments against that same id's real parent.

**Update (2026-08-20, later still — ASVS 4.0.3 Level 2 audit):** a 33-agent ASVS 4.0.3 Level 2
audit cross-referenced the 6 most recent security-area pending todos against the ASVS chapter set
(all 6 corroborated; one — the security-response-headers todo — also gained a new confirmed
finding on Referrer-Policy and a correction narrowing the X-Frame-Options gap; the rate-limiting
todo's scope broadened from `/signin`-only to general request-volume abuse per 3 independently
converging ASVS chapters). 19 new pending todos filed (12 moderate, 7 minor) spanning password
policy, MFA, session revocation, logging/observability, container/infra hardening, and CI
governance.

### Blockers/Concerns

- **[Phase 8, open unknown]** Nonprod Redpanda's memory floor is genuinely unmeasured. Production's reserved caps leave ~2.65GB unreserved on the 7.8GB host, and `mem_limit` is a per-container cap, not a host reservation — this project already hit a cgroup-accounting surprise at exactly this boundary. Requires iterative live restart cycles; if no safe value fits, the second-VPS fallback must actually be exercised.
- **[Phase 9, known trap, resolved by 09-01]** The existing `deploy-to-netcup` job hardcodes its target directory and the Compose project name is pinned, and `cleanup-old-images` deletes every Docker Hub tag except its own run's. A copy-pasted nonprod job that does not change *every* identity axis will mutate live production, and a shared image repo will let production's next push delete nonprod's running tag. Both risks were closed by 09-01's live-verified repository separation and re-confirmed live again during 09-02's verification (both Docker Hub repositories list only their own current tag).
- **[Phase 9, new todo, 2026-08-19]** `security-scan.yml`'s `dependency-check` job has been failing on `NVD_API_KEY repository secret is not set` since at least the 2026-08-17 scheduled run, confirmed unrelated to 09-02's secret sweep (identical failure predates it). See `.planning/todos/pending/2026-08-19-security-scan-yml-nvd-api-key-not-resolving.md`.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260818-ied | Add CI-05 requirement to Phase 9: automate Avro schema registry registration for nonprod as part of the CI deploy pipeline, extending deploy.yml with a schema-registration step (mirroring CI-01's flyway-verify-nonprod/deploy-to-nonprod pattern) so production and nonprod schema registries stay in sync on every deploy without a manual step | 2026-08-18 | b985989 | [260818-ied-add-ci-05-requirement-to-phase-9-automat](./quick/260818-ied-add-ci-05-requirement-to-phase-9-automat/) |
| 260820-ecm | Resolve WINDOWS.md ledger items 3 and 7: record already-completed live verification proof in INFRA_RUNBOOK.md and mark both fixed | 2026-08-20 | b5a265a | [260820-ecm-resolve-windows-md-ledger-items-3-and-7-](./quick/260820-ecm-resolve-windows-md-ledger-items-3-and-7-/) |
| 260820-euc | Add paths-ignore filter to deploy.yml so docs-only pushes don't trigger a full production+nonprod redeploy; also close 7 stale pending todos already resolved by Phase 10 | 2026-08-20 | 95f61cc | [260820-euc-add-paths-ignore-filter-to-deploy-yml-so](./quick/260820-euc-add-paths-ignore-filter-to-deploy-yml-so/) |
| 260820-g3u | Iterate on 4 easy pending todos: docs/CODE_STYLE.md rule 4 correction, tag HistoricalActivityEventReconstructorTest as kafka, qualify bare print() static import in 4 controller tests, fix ResetServiceE2ETest flaky race; also found and fixed a real gap in gradle/verification-metadata.xml along the way | 2026-08-20 | df68443 | [260820-g3u-iterate-on-4-easy-pending-todos-docs-cod](./quick/260820-g3u-iterate-on-4-easy-pending-todos-docs-cod/) |
| 260820-giz | Audit penetration-testing/security coverage against OWASP API Security Top 10 (2023) — cited verdict for all 10 categories, 4 new gap todos filed (IDOR chain consistency, signin rate-limiting, security response headers, CSRF cross-origin test), originating todo closed with Resolution | 2026-08-20 | 6a77e54 | [260820-giz-audit-penetration-testing-and-security-c](./quick/260820-giz-audit-penetration-testing-and-security-c/) |

## Deferred Items

Items acknowledged and carried forward (full v1.2-close table in `.planning/milestones/v1.2-ROADMAP.md`):

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Epics | Modernization Epics 3 (OpenAPI half), 4, 6, 7 (Redis, Observability, K8s) | Deferred to future milestones | 2026-07-31 |
| Kafka | Cursor/keyset pagination on activity feed (PAGE-V2-01) | Deferred to v2 | 2026-08-01 |
| Infra Polish | INFRA-V2-01..03 (observability stack, blue-green deploys, multi-broker Redpanda HA) | Deferred to v2 | 2026-08-03 |
| Schema Registry Polish | SCHEMA-V2-01..02 (pre-merge schema-compatibility CI check, compatibility-mode rationale doc) | Deferred to v2 | 2026-08-03 |
| Frontend coupling | FRONTEND-DISPATCH-V2, FRONTEND-COUPLING-V2 | Deferred — hard-blocked on the frontend repo existing | 2026-08-17 |
| Nonprod | Per-PR ephemeral environments, ephemeral Neon branch per E2E run, second Neon project | Rejected for v1.3 with written rationale (REQUIREMENTS.md Out of Scope) | 2026-08-17 |
| Quick task | 260801-p03, 260802-rq5, 260802-ryf (missing/research-only summaries) | Open since v1.1 close | 2026-08-17 |

**Known verification overrides: 14 total (recorded at v1.2 close)**

## Session Continuity

Last session: 2026-08-19T13:31:30.081Z
Stopped at: Phase 10 context gathered
Resume file: .planning/phases/10-ci-deploy-hardening/10-CONTEXT.md

## Operator Next Steps

- `/gsd-discuss-phase 10` to start Phase 10 (CI & Deploy Hardening) — no CONTEXT.md exists yet for it.
