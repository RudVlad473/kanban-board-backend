# Phase 11: Migrate database from Neon to self-hosted Postgres - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-26
**Phase:** 11-migrate-database-from-neon-to-self-hosted-postgres
**Areas discussed:** Todo triage, Topology, Data migration & cutover, VPS resource budget & Hikari pool re-tuning, Backups & CI Flyway-verify

---

## Todo Triage

| Option | Description | Selected |
|--------|-------------|----------|
| Fold it in | Phase 11 delivers a real backup/restore procedure for the new self-hosted instance | |
| Leave it out | Keep Phase 11 scoped to the migration itself; backups get their own separate future task | ✓ |

**User's choice:** Leave it out — `.planning/todos/pending/2026-08-20-no-documented-backup-restore-runbook-for-prod-db.md` was not formally folded, though its substance is addressed indirectly via the Backups & CI Flyway-verify area (documenting the gap).

---

## Topology — shared vs. isolated instances

### Q1: Shared instance vs. two separate containers

| Option | Description | Selected |
|--------|-------------|----------|
| One shared instance, two databases | Cheaper on the VPS's tight memory budget; isolation is DB-level not process-level | ✓ |
| Two separate containers (full parity) | Matches the Redpanda prod/nonprod split exactly | |
| You decide | Claude picks based on the measured memory budget | |

**User's choice:** One shared instance, two databases.

### Q2: Postgres version

| Option | Description | Selected |
|--------|-------------|----------|
| Postgres 16 (match existing dev/test) | Zero version drift from local dev / Testcontainers | ✓ |
| Postgres 18 (match what Neon ran) | No behavioral surprise relative to prior schema history | |
| You decide | Claude picks after checking version-specific behavior | |

**User's choice:** Postgres 16.

### Q3: Network exposure

| Option | Description | Selected |
|--------|-------------|----------|
| Internal-only, no host port published | Mirrors Redpanda's INFRA-08 rule | ✓ |
| Published but firewalled port | Lets external tooling connect directly | |

**User's choice:** Internal-only, no host port published.

### Q4: DB roles for the shared instance

| Option | Description | Selected |
|--------|-------------|----------|
| Separate roles, least-privilege | Nonprod role cannot touch production database | ✓ |
| One shared role for both databases | Simpler credential management | |

**User's choice:** Separate roles, least-privilege.

**Notes:** User explicitly noted (via the presented option context, confirmed by selection) that the incident's root cause — Neon's per-project shared compute-hour billing — has no direct analog in self-hosted Postgres, so the original NONPROD-01 full-isolation rationale doesn't automatically transfer to this decision.

---

## Data migration & cutover

### Q1: Schema + data migration approach

| Option | Description | Selected |
|--------|-------------|----------|
| pg_dump/pg_restore of real data | Preserves every real user/board/task exactly as-is | |
| Fresh start, no data carried over | Flyway builds clean schema; existing Neon data is left behind | ✓ |
| You decide | Claude weighs it once Neon data is inspectable | |

**User's choice:** Fresh start, no data carried over.

### Q2: Downtime/risk tolerance during cutover

| Option | Description | Selected |
|--------|-------------|----------|
| No new constraint — already down | No live traffic to protect; take the time needed | ✓ |
| Target a specific restore window | Bounded maintenance window | |

**User's choice:** No new constraint — already down.

### Q3: Nonprod scope

| Option | Description | Selected |
|--------|-------------|----------|
| Both together | Nonprod is disposable/resettable; proves the setup cheaply first | ✓ |
| Production only, nonprod later | Narrows this phase's blast radius | |

**User's choice:** Both together.

### Q4: Neon project fate post-cutover

| Option | Description | Selected |
|--------|-------------|----------|
| Delete once verified | Matches existing decommission discipline (AWS EC2/RDS precedent) | ✓ |
| Keep dormant for now | Leaves a rollback path, at the cost of a dangling resource | |

**User's choice:** Delete the Neon project once verified.

---

## VPS resource budget & Hikari pool re-tuning

### Q1: Postgres container memory ceiling

| Option | Description | Selected |
|--------|-------------|----------|
| Measure it live, like Redpanda was | Restart-ladder measurement discipline (NONPROD-06, 05-04 Task 3) | ✓ |
| You decide | Claude picks a starting cap from documented defaults | |

**User's choice:** Measure it live, like Redpanda was.

### Q2: Revisit Neon-specific Hikari/JDBC tuning?

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, revisit it | Local Postgres has no cold start/autosuspend/PgBouncer — old rationale doesn't apply | ✓ |
| Leave it alone for now | Keep current values; treat as a separate future cleanup | |

**User's choice:** Yes, revisit it.

### Q3: Re-measure the app JVM's own mem_limit too?

| Option | Description | Selected |
|--------|-------------|----------|
| No — out of scope | JVM sizing was about heap/non-heap overhead, unrelated to DB latency | |
| Yes, re-measure alongside Postgres | Catches any second-order local-vs-remote latency effect in one pass | ✓ |

**User's choice:** Yes, re-measure alongside Postgres.

### Q4: Postgres configuration profile

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, conservative profile | Small shared_buffers/work_mem/max_connections given the shared VPS | ✓ |
| You decide | Leave the exact profile to the researcher/planner | |

**User's choice:** Yes, conservative profile.

---

## Backups & CI Flyway-verify

### Q1: Backup strategy for the self-hosted instance

| Option | Description | Selected |
|--------|-------------|----------|
| A real, working pg_dump backup routine | Scheduled backup + tested restore procedure, documented | |
| Document the gap, don't build automation yet | Write down the gap in INFRA_RUNBOOK.md without building automation | ✓ |
| You decide | Claude picks the right level of automation during planning | |

**User's choice:** Document the gap, don't build automation yet.

### Q2: CI's pre-merge Flyway verification against a non-internet-reachable Postgres

| Option | Description | Selected |
|--------|-------------|----------|
| SSH tunnel from the runner into the VM | Keeps the same pre-merge guarantee without exposing Postgres publicly | ✓ |
| Drop the pre-merge external check | Rely on deploy-time Flyway-on-boot + health-check gate instead | |
| Throwaway Postgres inside the CI runner | Verifies migrations apply in isolation, doesn't prove anything about the real instance's state | |

**User's choice:** SSH tunnel from the runner into the VM's internal network.

---

## Claude's Discretion

None — every gray area surfaced during discussion received an explicit user decision.

## Deferred Ideas

- Automated backup tooling (scheduled pg_dump, tested restore, retention policy) for the
  self-hosted instance — deferred to a future phase/todo (see Backups Q1 above).
- Re-splitting the shared Postgres instance into two fully separate containers (full parity
  with the Redpanda prod/nonprod split) — not chosen now given the VPS's memory budget, worth
  revisiting if nonprod's workload grows.
