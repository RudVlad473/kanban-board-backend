---
gsd_state_version: "1.0"
milestone: v1.3
current_phase: 12
current_phase_name: Self-hosted observability stack
status: verifying
stopped_at: Completed 12-06-PLAN.md, phase 12 ready for verification
last_updated: "2026-09-08T10:41:22.511Z"
last_activity: 2026-09-08
last_activity_desc: Phase 12 execution resumed (wave continue)
state_head: aaeb537514f215124d17e1d360fa2f3ae2d2f695
progress:
  total_phases: 2
  completed_phases: 1
  total_plans: 14
  completed_plans: 14
milestone_name: Nonprod Environment & CI Hardening
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-17)

**Core value:** The backend is feature-complete against its own mock-ups and live in production; the differentiator now is proving the whole system — including a real frontend against a real deploy — is reliable.
**Current focus:** Phase 12 — Self-hosted observability stack

## Current Position

Phase: 12 (Self-hosted observability stack) — EXECUTING
Plan: 6 of 6
Status: Phase complete — ready for verification
Last activity: 2026-09-08 — Phase 12 execution resumed (wave continue)

## Performance Metrics

v1.0–v1.2 velocity/per-plan detail archived at milestone close — see `.planning/RETROSPECTIVE.md`'s Cost Observations per milestone and `.planning/milestones/v1.2-phases/` for individual plan SUMMARY.md files (each carries an `actuals` frontmatter block with tokens/tasks/commits). This section resets per milestone rather than accumulating a project-lifetime table.

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 11 P01 | 25min | 3 tasks | 6 files |
| Phase 11 P02 | 55min | 3 tasks | 1 files |
| Phase 11 P03 | 90min | 3 tasks | 3 files |
| Phase 11 P04 | 30min | 2 tasks | 1 files |
| Phase 11 P05 | 41min | 3 tasks | 2 files |
| Phase 11 P06 | 40min | 3 tasks | 2 files |
| Phase quick P260902-vjo | ~40min | 3 tasks | 2 files |
| Phase 12 P01 | 94min | 3 tasks | 6 files |
| Phase 12 P02 | 70min | 3 tasks | 6 files |
| Phase 12 P03 | 130min | 3 tasks | 5 files |
| Phase 12 P04 | 123min | 2 tasks | 5 files |
| Phase 12 P05 | 60min | 3 tasks | 2 files |
| Phase 12 P06 | 55min | 3 tasks | 7 files |

## Accumulated Context

### Roadmap Evolution

- Phase 11 added: Migrate database from Neon to self-hosted Postgres
- Phase 12 added: Self-hosted observability stack

### Decisions

Decisions are logged in PROJECT.md's Key Decisions table and `.planning/RETROSPECTIVE.md`. The full per-plan decision log for v1.0–v1.3 was cleared at v1.3's close — preserved in `git log` and `.planning/milestones/v1.3-phases/`.

No active-milestone decisions pending — next milestone not yet scoped.

- [Phase null]: 260825-h7m: MapStruct name-based auto-mapping was sufficient to carry createdAt onto BoardFullResponseDTO with no explicit @Mapping needed
- [Phase 11]: Phase 11 plan 01: postgres service placed in docker-compose.prod.yml (not a dedicated third file), following the kanban-edge cross-project precedent
- [Phase 11]: Phase 11 plan 01: DB_JDBC_PARAMS dropped entirely (not softened) from both app services -- self-hosted target has no TLS listener
- [Phase 11]: Phase 11 plan 01: postgres mem_limit: 512m is an explicitly-labeled PROVISIONAL Iteration 0 baseline; plan 11-03 owns the measured floor
- [Phase 11]: Phase 11 plan 04: re-derived HikariCP pool for a same-host Postgres engine (min-idle 0->2, connection-timeout 30000->10000, idle-timeout/max-lifetime raised to Hikari defaults, keepalive-time 0->120000), dropped the DB_JDBC_PARAMS query-string override, and proved prepareThreshold=0 removal safe with 1028 observed server-side prepared-statement executions against a real instance
- [Phase 11]: Phase 11 plan 04: pg_prepared_statements is session-scoped and unusable for cross-session verification from a separate docker exec psql session -- used Postgres's own statement log (log_statement=all) instead, confirmed empirically before adopting
- [Phase 11]: Phase 11 plan 05: D-13 Task 1 resolved as Option A (one-off Flyway container over SSH), no D-03 exception taken
- [Phase 11]: Phase 11 plan 05: DB_HOST/DB_NAME moved from GitHub Environment secrets to variables so the resolved database name is provably visible, unmasked, in CI logs (DB_USER/DB_PASS remain secrets)
- [Phase 11]: Phase 11 plan 05: gate-still-bites evidence gathered via a live VM-local reproduction against a disposable scratch database rather than a throwaway-branch CI push
- [Phase 11]: 260829-ii3: ResetController's params-based two-route split (fullReset=true vs fullReset!=true) routed exactly as predicted; the plan's accepted-async-race assumption for activity_log/topic-offset assertions did not hold empirically and required bounding, not tightening
- [Phase 11]: 260902-vjo: Netcup SCP console triage guidance added to INFRA_RUNBOOK.md between Access and Firewall sections; new scheduled uptime-check.yml workflow (cron */15, workflow_dispatch, permissions: {}) probes both public health endpoints, proven to bite on non-200/unreachable/wrong-body branches locally
- [Phase 12]: D-03 amended: a login-path-scoped Caddy rate_limit zone is permitted (and required) on the Grafana hostname, distinct from an authentication gate
- [Phase 12]: node-exporter uses a host-root read-only bind mount + --path.rootfs/procfs/sysfs instead of network_mode:host/pid:host (forbidden by verify-compose-ports.py I2); node_network_* still leaks host interface topology via the sysfs bind, corrected in the compose comment post-deploy
- [Phase 12]: Phase 12 plan 02: general lesson for any future Loki deploy against an already-populated Docker host -- schema_config.from and reject_old_samples_max_age must both predate the oldest on-disk log backlog, not the deploy date; a third distinct too_far_behind per-stream ordering guard is an accepted operational characteristic, not something to configure around
- [Phase 12]: Phase 12 plan 02: .planning/config.json gained git.allow_default_branch_commits: true, making explicit this project's already-established branching_strategy: none convention
- [Phase 12]: Phase 12 plan 04: two real bugs found in a vendored postgres-exporter Grafana dashboard by diffing its PromQL against the live exporter's own /metrics output and against 12-03's actual scrape job name -- two metric renames (pg_replication_lag/pg_database_size) and one template-variable job-label mismatch; reusable playbook for any future vendored dashboard
- [Phase 12]: Phase 12 plan 04: 2 of 13 postgres-exporter dashboard panels (Max Connections, Shared Buffers) diagnosed as a Grafana 13.2.1 legacy singlestat panel-type rendering bug -- underlying data confirmed correct via direct /api/ds/query call -- accepted as a documented finding rather than fixed (would need a singlestat->stat panel-type migration, out of scope)
- [Phase 12]: Phase 12 plan 04: fixed an unrelated CI gap found mid-plan -- .github/workflows/deploy.yml's Caddyfile validation step was missing APP_DOMAIN_MONITORING (needed by 12-01's third Caddyfile block), silently failing production deploys since 12-01 merged
- [Phase 12]: Phase 12 plan 05: user explicitly authorized the agent to drive the restart-ladder memory

measurement directly over SSH to netcup-prod, rather than a human operator running it manually — Matches a documented prior precedent in docs/INFRA_RUNBOOK.md of agent-driven SSH sessions
authorized mid-session; the plan's own checkpoint:human-action was presented to the user as a
decision before proceeding, not bypassed

- [Phase 12]: Phase 12 plan 06: user authorized the agent to drive the caddy restart-ladder measurement over

SSH too, with explicit acknowledgment this ladder briefly interrupts live public production
traffic on every rung -- a materially higher-stakes decision than plan 12-05's authorization — Presented as its own fresh checkpoint decision rather than assumed to extend 12-05's blanket
authorization, given the real-traffic-interruption and Let's Encrypt cert-safety risk profile

### Pending Todos

~46 pending todos remain in `.planning/todos/pending/` (security/CI/minor backlog, all explicitly
out of v1.3 scope) — full inventory in this document's Deferred Items table below, acknowledged
and carried forward at v1.3 close. None block the next milestone; triage candidates for it via
`.planning/todos/pending/`.

### Blockers/Concerns

None open. All three carried into v1.3 (Redpanda memory floor, nonprod/production identity
confusion risk, `NVD_API_KEY` resolution failure) were resolved during Phases 8–10 — see
`.planning/milestones/v1.3-ROADMAP.md` and PROJECT.md's Key Decisions for detail.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260818-ied | Add CI-05 requirement to Phase 9: automate Avro schema registry registration for nonprod as part of the CI deploy pipeline, extending deploy.yml with a schema-registration step (mirroring CI-01's flyway-verify-nonprod/deploy-to-nonprod pattern) so production and nonprod schema registries stay in sync on every deploy without a manual step | 2026-08-18 | b985989 | [260818-ied-add-ci-05-requirement-to-phase-9-automat](./quick/260818-ied-add-ci-05-requirement-to-phase-9-automat/) |
| 260820-ecm | Resolve WINDOWS.md ledger items 3 and 7: record already-completed live verification proof in INFRA_RUNBOOK.md and mark both fixed | 2026-08-20 | b5a265a | [260820-ecm-resolve-windows-md-ledger-items-3-and-7-](./quick/260820-ecm-resolve-windows-md-ledger-items-3-and-7-/) |
| 260820-euc | Add paths-ignore filter to deploy.yml so docs-only pushes don't trigger a full production+nonprod redeploy; also close 7 stale pending todos already resolved by Phase 10 | 2026-08-20 | 95f61cc | [260820-euc-add-paths-ignore-filter-to-deploy-yml-so](./quick/260820-euc-add-paths-ignore-filter-to-deploy-yml-so/) |
| 260820-g3u | Iterate on 4 easy pending todos: docs/CODE_STYLE.md rule 4 correction, tag HistoricalActivityEventReconstructorTest as kafka, qualify bare print() static import in 4 controller tests, fix ResetServiceE2ETest flaky race; also found and fixed a real gap in gradle/verification-metadata.xml along the way | 2026-08-20 | df68443 | [260820-g3u-iterate-on-4-easy-pending-todos-docs-cod](./quick/260820-g3u-iterate-on-4-easy-pending-todos-docs-cod/) |
| 260820-giz | Audit penetration-testing/security coverage against OWASP API Security Top 10 (2023) — cited verdict for all 10 categories, 4 new gap todos filed (IDOR chain consistency, signin rate-limiting, security response headers, CSRF cross-origin test), originating todo closed with Resolution | 2026-08-20 | 6a77e54 | [260820-giz-audit-penetration-testing-and-security-c](./quick/260820-giz-audit-penetration-testing-and-security-c/) |
| 260820-iwo | File 19 new pending todos + enrich 6 already-filed todos from the ASVS 4.0.3 Level 2 audit (33-agent workflow, 253 requirements traced) — 12 moderate + 7 minor new todos filed, STATE.md Pending Todos refreshed (~26 → ~45 items) | 2026-08-20 | cdaf0b9 | [260820-iwo-file-19-new-pending-todos-and-enrich-6-a](./quick/260820-iwo-file-19-new-pending-todos-and-enrich-6-a/) |
| 260825-dfd | Add a createdAt timestamp to BoardEntity and expose it on BoardResponseDTO, populated once in BoardService.save() and shared with BoardCreatedEvent's timestamp | 2026-08-25 | 6aadda1 | [260825-dfd-add-a-createdat-timestamp-to-boardentity](./quick/260825-dfd-add-a-createdat-timestamp-to-boardentity/) |
| 260825-h7m | Add createdAt to BoardFullResponseDTO, carried through the nested GET /boards/{boardId}/full read via MapStruct name-based auto-mapping; extended the flat-equivalence test to cover board-level fields (name/version/createdAt), closing the untested gap that let createdAt reach production missing from the nested document | 2026-08-25 | 1dd30ea | [260825-h7m-add-createdat-to-boardfullresponsedto-ge](./quick/260825-h7m-add-createdat-to-boardfullresponsedto-ge/) |
| 260829-ii3 | In the nonprod reset endpoint, add a REQUIRED targeted-delete mode selected by a fullReset query param: POST /api/admin/reset?fullReset=true keeps the unconditional full reset unchanged, a bare POST with {"userIds": [...]} cascade-deletes only those users' boards/columns/tasks/subtasks and their own activity_log rows via a new ResetService.deleteUsers, reusing UserService.deleteById's existing cascade | 2026-08-29 | c29a32d | [260829-ii3-in-the-nonprod-reset-endpoint-add-a-requ](./quick/260829-ii3-in-the-nonprod-reset-endpoint-add-a-requ/) |
| 260902-vjo | Document Netcup console staleness triage in docs/INFRA_RUNBOOK.md and add a GitHub Actions cron uptime check for both public health endpoints | 2026-09-02 | 4613746 | [260902-vjo-document-netcup-console-staleness-triage](./quick/260902-vjo-document-netcup-console-staleness-triage/) |
| 260904-obv | add 'color' field to column and accept it on column creation | 2026-09-04 | 50d4aca | [260904-obv-add-color-field-to-column-and-accept-it-](./quick/260904-obv-add-color-field-to-column-and-accept-it-/) |
| 260905-qxi | add a CI invariant (scripts/verify-compose-ports.py + self-test) that only caddy may publish host ports 80/443 in the two deployed compose files, wire it into invariant-checks.yml, and correct docs/INFRA_RUNBOOK.md's false "both layers enforce identical policy" firewall claim | 2026-09-05 | 566e725 | [260905-qxi-add-ci-invariant-that-only-caddy-may-pub](./quick/260905-qxi-add-ci-invariant-that-only-caddy-may-pub/) |
| 260905-tw0 | Infra diagrams: pin a digest-pinned mermaid-cli renderer (scripts/render-diagrams.sh + render-manifest.tsv) and measure drift on all nine committed diagrams (7 pre-existing drift 6-11% in height, filed as a todo); add four flowchart layout rules with deciding tests to docs/DIAGRAM_CONVENTIONS.md; add an inbound packet-path Scenario to docs/INFRA_ARCHITECTURE.md with DOCKER-USER drawn empty and dated 2026-09-05; number trust boundaries [1]-[5] on the Physical view incl. the external Netcup Cloud Firewall; extend the Maintenance Note file list | 2026-09-05 | 2b0bd7b | [260905-tw0-infra-diagrams-pin-a-committed-mmd-png-r](./quick/260905-tw0-infra-diagrams-pin-a-committed-mmd-png-r/) |
| 260906-feq | Fill the empty DOCKER-USER iptables chain with a version-controlled default-drop policy for Docker-published ports (infra/vm/docker-user-firewall.sh + systemd unit, PartOf=docker.service), reviewed three ways (Claude/Gemini/Codex) pre-execution which caught a checkpoint that silently defaulted to rebooting production and other real gaps, proved via off-box canary probe + packet-counter attribution + a genuine VM reboot, and corrected the runbook/architecture-doc/diagram that had described the chain as empty since 2026-09-05; IPv6 gap left open and filed as a new todo per operator decision | 2026-09-06 | f47f912 | [260906-feq-docker-user-iptables-rules-on-the-vm-sys](./quick/260906-feq-docker-user-iptables-rules-on-the-vm-sys/) |
| 260908-dl3 | Let POST /api/boards optionally accept a caller-supplied board id, validated against RandFlakeGenerator's real base36 format, uniqueness-checked before insert, and rejected 409 when taken; corrected `.claude/CLAUDE.md`'s stale ULID claim to the actual base36 scheme | 2026-09-08 | cb38532 | [260908-dl3-create-board-endpoint-optionally-accepts](./quick/260908-dl3-create-board-endpoint-optionally-accepts/) |

## Deferred Items

Items acknowledged and carried forward (full v1.2-close table in `.planning/milestones/v1.2-ROADMAP.md`):

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| Epics | Modernization Epics 3 (OpenAPI half), 4, 6, 7 (Redis, Observability, K8s) | Deferred to future milestones | 2026-07-31 | v1.2 |
| Kafka | Cursor/keyset pagination on activity feed (PAGE-V2-01) | Deferred to v2 | 2026-08-01 | v1.2 |
| Infra Polish | INFRA-V2-01..03 (observability stack, blue-green deploys, multi-broker Redpanda HA) | Deferred to v2 | 2026-08-03 | v1.2 |
| Schema Registry Polish | SCHEMA-V2-01..02 (pre-merge schema-compatibility CI check, compatibility-mode rationale doc) | Deferred to v2 | 2026-08-03 | v1.2 |
| Frontend coupling | FRONTEND-DISPATCH-V2, FRONTEND-COUPLING-V2 | Deferred — hard-blocked on the frontend repo existing | 2026-08-17 | v1.2 |
| Nonprod | Per-PR ephemeral environments, ephemeral Neon branch per E2E run, second Neon project | Rejected for v1.3 with written rationale (REQUIREMENTS.md Out of Scope) | 2026-08-17 | v1.2 |
| Quick task | 260801-p03, 260802-rq5, 260802-ryf (missing/research-only summaries) | Open since v1.1 close | 2026-08-17 | v1.2 |
| quick_tasks | 260801-p03-add-explicit-comments-to-taskservice-upd | missing | 2026-08-25 | v1.3 |
| quick_tasks | 260802-rq5-bump-java-version-from-21-to-25-current- | missing | 2026-08-25 | v1.3 |
| quick_tasks | 260802-ryf-enable-virtual-threads-in-spring-boot-co | missing | 2026-08-25 | v1.3 |
| quick_tasks | 260816-hn1-wire-up-secret-scanning-gitleaks-truffle | unknown | 2026-08-25 | v1.3 |
| quick_tasks | 260824-v7n-install-docker-natively-inside-this-wsl2 | missing | 2026-08-25 | v1.3 |
| todos | 45 pending todos (full list: `.planning/todos/pending/`) | (presence-only) | 2026-08-25 | v1.3 |

**Known verification overrides: 14 total (recorded at v1.2 close); 50 newly acknowledged, 0 carried forward from a prior close, at v1.3 close (see above)**

**v1.3 close notes on the 5 flagged quick tasks:**

- `260801-p03`, `260802-rq5`, `260802-ryf` — pre-existing gap, unchanged since v1.1/v1.2 close (see rows above).
- `260816-hn1` (secret scanning) — false positive: fully complete with both PLAN.md and SUMMARY.md; flagged only because its `duration` frontmatter field is free text ("unknown (resumed mid-execution...)") rather than a parseable value.
- `260824-v7n` (install Docker natively inside WSL2) — genuinely empty, abandoned directory (scaffolded 2026-08-24, never executed); unrelated local dev-environment tooling, not a v1.3 requirement.

The 46 pending todos are individually listed and categorized in this document's own Pending Todos section above (security/CI/minor backlog, all explicitly out of v1.3 scope per PROJECT.md).

## Session Continuity

Last session: 2026-09-08T10:41:22.390Z
Stopped at: Completed 12-06-PLAN.md, phase 12 ready for verification
Resume file: None

## Operator Next Steps

- Continue Phase 12 via `/gsd-execute-phase 12` (12-05: measured mem_limit floors; 12-06: phase close-out)
- After Phase 12 closes: start the next milestone with /gsd-new-milestone
