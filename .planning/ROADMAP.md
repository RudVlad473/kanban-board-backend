# Roadmap: Kanban Board Backend — Epic 2 Completion

## Milestones

- ✅ **v1.0 Optimistic Locking** — Phase 1 (shipped 2026-08-01)
- ✅ **v1.1 Kafka Activity Feed** — Phases 2-3 (shipped 2026-08-03)
- ✅ **v1.2 Infra Migration & Schema Registry** — Phases 4, 04.1, 04.2, 5, 6, 7, 07.1 (shipped 2026-08-17)
- ✅ **v1.3 Nonprod Environment & CI Hardening** — Phases 8-10 (shipped 2026-08-25)

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

<details>
<summary>✅ v1.0 Optimistic Locking (Phase 1) — SHIPPED 2026-08-01</summary>

- [x] Phase 1: Optimistic Locking (3/3 plans) — completed 2026-08-01

Full details: [milestones/v1.0-ROADMAP.md](milestones/v1.0-ROADMAP.md)

</details>

<details>
<summary>✅ v1.1 Kafka Activity Feed (Phases 2-3) — SHIPPED 2026-08-03</summary>

- [x] Phase 2: Kafka Foundation, Domain Events & Move Endpoint (3/3 plans) — completed 2026-08-01
- [x] Phase 3: Activity Log Consumer, Reliability & Read API (3/3 plans) — completed 2026-08-02

Full details: [milestones/v1.1-ROADMAP.md](milestones/v1.1-ROADMAP.md)

</details>

<details>
<summary>✅ v1.2 Infra Migration & Schema Registry (Phases 4, 04.1, 04.2, 5, 6, 7, 07.1) — SHIPPED 2026-08-17</summary>

- [x] Phase 4: Schema Registry (4/4 plans) — completed 2026-08-04
- [x] Phase 04.1: Flyway database migration implementation (INSERTED) (3/3 plans) — completed 2026-08-05
- [x] Phase 04.2: Testcontainers Postgres, drop H2 (INSERTED) (3/3 plans) — completed 2026-08-06
- [x] Phase 6: Mock-up Feature Gap Closure (7/7 plans) — completed 2026-08-09
- [x] Phase 7: Restructure test folder (7/7 plans) — completed 2026-08-09
- [x] Phase 07.1: Address hard blockers and inconsistencies from the frontend-integration-readiness audit (INSERTED) (9/9 plans) — completed 2026-08-17
- [x] Phase 5: Infra Migration (6/6 plans) — completed 2026-08-17

Full details: [milestones/v1.2-ROADMAP.md](milestones/v1.2-ROADMAP.md)

</details>

<details>
<summary>✅ v1.3 Nonprod Environment & CI Hardening (Phases 8-10) — SHIPPED 2026-08-25</summary>

- [x] Phase 8: Isolated Nonprod Environment, Live and Resettable (3/3 plans) — completed 2026-08-18
- [x] Phase 9: Nonprod Continuous Deploy & Scoped CI Credentials (4/4 plans) — completed 2026-08-19
- [x] Phase 10: CI & Deploy Hardening (6/6 plans) — completed 2026-08-19

Full details: [milestones/v1.3-ROADMAP.md](milestones/v1.3-ROADMAP.md)

</details>

## Progress

| Milestone | Phases | Plans | Status | Shipped |
|-----------|--------|-------|--------|---------|
| v1.0 Optimistic Locking | 1 | 3/3 | Complete | 2026-08-01 |
| v1.1 Kafka Activity Feed | 2 | 6/6 | Complete | 2026-08-03 |
| v1.2 Infra Migration & Schema Registry | 7 | 39/39 | Complete | 2026-08-17 |
| v1.3 Nonprod Environment & CI Hardening | 3 | 13/13 | Complete | 2026-08-25 |

Next milestone not yet scoped — run `/gsd-new-milestone` to define it.

### Phase 11: Migrate database from Neon to self-hosted Postgres

**Goal:** Both production and nonprod run against a single self-hosted PostgreSQL 16 container on the existing Netcup VPS — two databases, two least-privilege roles that cannot reach each other's data, no host port published — with Neon decommissioned, the pool/JDBC tuning re-derived for a same-host engine, CI's pre-merge Flyway gate preserved over SSH, and the resulting loss of point-in-time recovery documented as an acknowledged gap.
**Requirements**: D-01..D-13 (CONTEXT.md decisions — no REQUIREMENTS.md exists for this not-yet-scoped milestone)
**Depends on:** Phase 10
**Plans:** 8/8 plans complete

Plans:
**Wave 1**

- [x] 11-01-PLAN.md — Postgres service, multi-DB/multi-role provisioning, shared `kanban-db` network, env-file contracts (wave 1)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 11-02-PLAN.md — Live cutover of both environments onto the self-hosted instance (wave 2)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 11-03-PLAN.md — Measured memory floors for the database and both app containers (wave 3)
- [x] 11-04-PLAN.md — HikariCP/JDBC re-tuning for a same-host engine (wave 3)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 11-05-PLAN.md — CI Flyway verification over SSH against the internal network (wave 4)

**Wave 5** *(blocked on Wave 4 completion)*

- [x] 11-06-PLAN.md — Neon decommission and backup-gap documentation (wave 5)

### Phase 12: Self-hosted observability stack

**Goal:** Metrics (CPU/memory/disk, per-container and host-level) and log aggregation are queryable without any paid SaaS — Prometheus + Grafana + Loki/Promtail + cAdvisor + node_exporter running as additional containers on the existing Netcup VPS, retention and `mem_limit`s sized against measured headroom (4.9 GiB RAM free of 7.8 GiB, CPU load avg 0.11/4 vCPU, 222 GB disk free — measured 2026-09-07), and the Grafana UI reachable only through Caddy (not publicly exposed) rather than a raw published port. Closes the metrics/log-shipping half of `.planning/todos/pending/2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md`; the alerting half is out of scope for this phase.
**Requirements**: D-01 through D-08, the eight locked decisions in `12-CONTEXT.md` (no REQUIREMENTS.md for this not-yet-scoped milestone — those decisions are the acceptance surface instead)
**Depends on:** Phase 11
**Plans:** 6/6 plans executed

Plans:
**Wave 1**

- [x] 12-01-PLAN.md — Tracer: node_exporter → Prometheus → Grafana → Caddy, proven end to end over public HTTPS (D-03, D-04, D-07)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 12-02-PLAN.md — Loki + Promtail: all six containers' logs queryable in Grafana, 30d retention (D-01, D-07, D-08)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 12-03-PLAN.md — cAdvisor, postgres_exporter, both Redpanda brokers, and the kanban-metrics cross-project network (D-04, D-05, D-06)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 12-04-PLAN.md — Grafana dashboards as committed JSON, rendering real data (D-04, D-05, D-06)

**Wave 5** *(blocked on Wave 4 completion)*

- [x] 12-05-PLAN.md — Restart-ladder measurement replacing all seven provisional mem_limits (D-04, D-05, D-06, D-07)

**Wave 6** *(blocked on Wave 5 completion)*

- [x] 12-06-PLAN.md — Caddy mem_limit, architecture doc + diagram correction, folded-todo closure (D-01, D-02)
