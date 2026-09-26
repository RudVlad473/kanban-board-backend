---
gsd_state_version: "1.0"
milestone: v1.3
current_phase: 13
current_phase_name: Introduce Kubernetes
status: executing
stopped_at: Completed 13-06-PLAN.md (production cutover to k3s, live window + incident recovery)
last_updated: "2026-09-26T08:53:08.146Z"
last_activity: 2026-09-25
last_activity_desc: Fixed deploy.yml's caddy-reload-inode-bug (force-recreate over exec reload), live-verified via a real production deploy; resumed and completed 13-05 Task 3 (GitOps cycle proof, kubectl health evidence, interim memory budget PASS by 268 MiB thin margin)
state_head: 819a8b588e54670a22db53b7d4b4b17c806840c0
progress:
  total_phases: 3
  completed_phases: 1
  total_plans: 24
  completed_plans: 20
milestone_name: Nonprod Environment & CI Hardening
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-09-08)

**Core value:** The backend is feature-complete against its own mock-ups and live in production; the differentiator now is proving the whole system — including a real frontend against a real deploy — is reliable.
**Current focus:** Phase 13 — Introduce Kubernetes

## Current Position

Phase: 13 (Introduce Kubernetes) — EXECUTING
Plan: 6 of 10 (Wave 4, 13-06) — COMPLETE. Waves 1-4 (13-01 through 13-06) done, merged, verified. Production is now live on k3s (D-01 executed). Waves 5-8 (13-07 through 13-10) not started.
Status: Ready to execute
Last activity: 2026-09-26 — Executed the production cutover to k3s in a live maintenance window (13-06). A mid-window operator error (pushing a staged multi-commit branch by tip instead of by per-gate SHA) landed W2/W3/W4 on `main` simultaneously, causing a real ~11-minute public 502 outage before the actual data migration had happened; no production data was lost (Compose's own Postgres was never stopped until after the real dump was taken). The executor stopped and reported on discovering this rather than improvising alone; the operator explicitly directed pushing forward rather than rolling back. Production now runs on k3s with verified zero-diff row-count parity, all three hostnames on real Let's Encrypt production certificates, Traefik/ServiceLB owning 80/443, and CI fully retargeted (Compose deploy jobs removed, Flyway verification through a fingerprint-pinned SSH forward, proven green via a live `deploy.yml` dispatch). Five real pre-existing gaps were found and fixed live against the cluster for the first time (a Postgres readinessProbe using unexpandable `$(POSTGRES_USER)` syntax, a cert-manager HelmRepository/HelmRelease missing `metadata.namespace`, no IngressRoute anywhere in the phase serving `grafana-tls` for the monitoring hostname, an SSH host-key fingerprint comparison bug, and a CNI/DNS pod-startup race). Full incident account and gap details in `docs/history/2026-09-26-production-cutover-to-k3s.md`; the generalizable lesson (push a staged multi-commit cutover by explicit SHA per gate, never by branch tip) is recorded in `docs/SESSION_LESSONS.md` lesson 8.

Next: 13-07 (observability activation, D-17) — Wave 5. Note: 13-06 built a deliberate placeholder `IngressRoute grafana`/`IngressRoute grafana-http` pair (backed by an Endpoints-less Service, Traefik answers 503) in `k8s/platform/edge/ingressroute-monitoring.yaml`, using the exact object names 13-07's own plan text already expects — 13-07 should replace the backend Service reference, not recreate the IngressRoute objects. Compose is stopped but NOT deleted (D-04 gate is 13-10); all 8 Compose volumes remain intact.

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
| Phase 13 P06 | ~59min window + ~30min post-window fixes/docs | 3 tasks | 23 files |

## Accumulated Context

### Roadmap Evolution

- Phase 11 added: Migrate database from Neon to self-hosted Postgres
- Phase 12 added: Self-hosted observability stack
- Phase 13 added: Introduce Kubernetes

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

- [Phase 13]: 13-05 resume session: fixed deploy.yml's caddy-reload-inode-bug (replaced `caddy reload` inside the running container with `docker compose up -d --force-recreate caddy`) before continuing 13-05 Task 3 rather than deferring it -- appleboy/scp-action's rename-based file replacement gives the new Caddyfile a new inode, but Docker's bind mount is resolved once at container start and kept pointing at the old one, so the old mechanism was fail-closed-looking but actually silently stale; force-recreate sidesteps the inode problem structurally and trades that for a fail-loud failure mode instead. Live-verified via a real production deploy (host/container Caddyfile md5sum match, ~1min container uptime confirming genuine recreation), not just a green CI job -- the same md5sum-comparison discipline that caught the bug in the first place.
- [Phase 13]: 13-05 Task 3's interrupted-executor resume: the prior session's worktree (`agent-a4f43b113ff7a0069`) had no live agent to SendMessage-resume, but was clean and un-reaped, so it was rebased onto current main and then discarded in favor of a fresh `isolation="worktree"` dispatch once the project's own dispatch-isolation guard rejected manually pointing an Agent() call at a pre-existing worktree path -- no work was lost since the manual worktree's commits were already on main.
- [Phase 13]: 13-06: production cutover approved and executed; a mid-window push-by-branch-tip incident caused ~11min public 502 with zero data loss, operator directed push-forward-not-rollback recovery
- [Phase 13]: 13-06: 5 real pre-existing gaps found and fixed live against the cluster for the first time (postgres readinessProbe syntax, cert-manager HelmRepository/HelmRelease namespace, missing monitoring IngressRoute, SSH fingerprint comparison, a CNI-readiness race) -- none introduced by the incident

### Pending Todos

60 pending todos in `.planning/todos/pending/` as of 2026-09-25 (count drifted from this
section's earlier "~46" since v1.3 close and was not tracked incrementally; not re-audited here,
just corrected to the current `ls | wc -l`). Newest: `2026-09-24-netcup-vps-intermittent-tcp-retransmits-under-sustained-throughput.md`
(minor, infra — a real but thin iperf3 retransmit/bitrate-dip signal found while triaging an
orphaned diagnostics file during the 2026-09-25 docs cleanup). Full inventory in this document's
Deferred Items table below, acknowledged and carried forward at v1.3 close. None block the next
milestone; triage candidates for it via
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
| 260908-mtl | Rename the three phase-12 vendored Grafana dashboards to human-readable titles (VM Host Metrics, Per-Container Resource Usage, Postgres Internals) and re-scope the grooming todo to piece 2 only; plan fan-out-reviewed (Claude+agy+Codex) pre-execution, which caught and fixed a false claim about Grafana rewriting stale URL slugs (it doesn't — verified live) and a mis-citation of one of two precedent todos | 2026-09-08 | ba259cb | [260908-mtl-groom-the-three-phase-12-grafana-dashboa](./quick/260908-mtl-groom-the-three-phase-12-grafana-dashboa/) |
| 260908-r16 | Restructure the Postgres Internals dashboard's 23 panels from one crammed "Global Statistics" row into five expanded topic rows (Health & Availability, Connections, Query Performance, Storage & I/O, Locks) via a reviewable Python codemod, rename cAdvisor's title again to "CPU/Memory & Network Usage - cAdvisor", and supersede the now-stale title claim on the originating todo; verified against a real disposable Grafana container plus a live browser screenshot confirming the visual fix | 2026-09-08 | ac12fc3 | [260908-r16-restructure-the-postgres-internals-grafa](./quick/260908-r16-restructure-the-postgres-internals-grafa/) |
| 260908-sj9 | Discovered live (checking why a fresh public Grafana link showed stale content): deploy-to-netcup's SCP step never transferred 4 of 6 repo-relative bind mounts docker-compose.prod.yml declares (grafana provisioning, prometheus.yml, loki-config.yaml, promtail-config.yaml) — production had served Sep-7 hand-copied config behind 14 green deploys. Fixed the source list, added scripts/verify-deploy-scp-coverage.py (proven to fail against the pre-fix string, naming all four paths, and pass against the fix) wired into invariant-checks.yml, recorded the evidence in INFRA_RUNBOOK.md, and filed a todo for the separate remaining gap (3 of 4 consumers need a restart to apply transferred config, not just a copy) | 2026-09-08 | d433360 | [260908-sj9-fix-deploy-yml-add-docker-grafana-provis](./quick/260908-sj9-fix-deploy-yml-add-docker-grafana-provis/) |
| 260911-gkz | Root-caused all 7 dead "Postgres Internals" dashboard tiles via a live 4-layer probe (SSH to netcup-prod + Grafana's /api/ds/query with a viewer credential, substituting for an unavailable browser MCP tool): 2 tiles had disabled exporter collectors, 2 hit an already-documented Grafana 13.2.1 singlestat rendering bug, 2 (really 9 panels) hit a collapsed-row exact-match datname matcher. User approved all 4 fixes at the plan's checkpoint; landed as docker-compose.prod.yml + dashboard-JSON changes verified against disposable local containers only — the pg_stat_statements fix's production Postgres restart is explicitly deferred to a separate, later, gated deploy. A second, independent exporter-version/dashboard metric-name mismatch was found and fixed along the way | 2026-09-11 | 2ad3656 | [260911-gkz-debug-why-several-grafana-postgres-inter](./quick/260911-gkz-debug-why-several-grafana-postgres-inter/) |

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

Last session: 2026-09-26T08:53:08.010Z
Stopped at: Completed 13-06-PLAN.md (production cutover to k3s, live window + incident recovery)
Resume file: None

## Operator Next Steps

- Phase 13 Wave 5 (13-07, observability activation, D-17) is next. Its own precondition text already assumes `k3s kubectl get ingressroute grafana -n monitoring` exists — it does, as a deliberate 13-06 placeholder (Endpoints-less `grafana-placeholder` Service, Traefik answers 503 for it). 13-07 should replace the backend Service reference on the existing `IngressRoute grafana`/`IngressRoute grafana-http` objects, not recreate them.
- Read `docs/history/2026-09-26-production-cutover-to-k3s.md` before starting 13-07 or any later production-touching plan — it documents a real mid-window incident (a staged multi-commit branch pushed by tip instead of per-gate SHA, causing an ~11-minute public 502 with zero data loss) and 5 gaps found and fixed live. `docs/SESSION_LESSONS.md` lesson 8 generalizes the incident's root cause for any future staged multi-commit rollout in this repo.
- Compose is stopped but NOT deleted (D-04 gate is 13-10) — all 8 Compose volumes remain intact on the VM. Do not delete them before 13-10's own gate passes.
- WINDOWS.md entry 9 (13-05 Task 2's unreproducible verbatim rpk/reset-endpoint acceptance-criteria capture) is open, not blocking, worth closing opportunistically.
- Deferred, user-requested: reorganize `/docs` (9 top-level .md files + demo/diagrams/incidents/learning/netcup-report/plans subdirs) — still deferred. Two untracked items found in the working tree in a prior session, not cleaned up (unclear provenance): `docs/netcup-report/netcup_network_diagnostics.txt` and `docs/learning/.review-431836/` (a completed multi-agent review's scratch output, 20 findings) — triage or delete before/during the docs reorg.
