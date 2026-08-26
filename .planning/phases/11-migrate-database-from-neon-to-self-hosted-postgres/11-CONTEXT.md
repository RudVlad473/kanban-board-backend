# Phase 11: Migrate database from Neon to self-hosted Postgres - Context

**Gathered:** 2026-08-26
**Status:** Ready for planning

<domain>
## Phase Boundary

Replace Neon (managed, autoscaling, serverless Postgres) with a self-hosted PostgreSQL
container on the existing Netcup VPS, as the database for both production and nonprod —
following the same self-hosting pattern already proven for Redpanda. This closes the
structural risk that caused the 2026-08-26 outage: Neon's Free-plan 100 CU-h/month compute
allowance is billed per PROJECT, shared across every branch, so one config choice (keeping a
connection warm to avoid cold starts) silently billed both the production and nonprod
computes 24/7 and exhausted the shared quota mid-month, taking both environments down
simultaneously with no available in-repo fix until 2026-09-01 or a plan upgrade. Full
incident record: `.planning/debug/admin-reset-500-nonprod.md`.

**In scope:** standing up self-hosted Postgres on the VPS (topology, version, resource
sizing), cutting production and nonprod over to it, decommissioning Neon, and adapting
everything downstream of "the database is now local, not a remote managed service" (Hikari
pool tuning, CI's pre-merge Flyway verification, docker-compose files, INFRA_RUNBOOK.md).

**Out of scope:** carrying over real data from Neon (explicit decision below — fresh start),
building automated backup tooling for the new instance (explicit decision below — document
the gap only), re-tuning the app JVM's own heap sizing beyond the resource-measurement pass
this phase already does for a different reason.

</domain>

<decisions>
## Implementation Decisions

### Topology
- **D-01:** One shared Postgres 16 instance serving both production and nonprod, as two
  separate databases with separate least-privilege Postgres roles (the nonprod role cannot
  connect to the production database) — not two fully separate containers mirroring the
  existing `redpanda`/`redpanda-nonprod` split. — **Reversibility:** costly — **rationale:**
  splitting later means standing up a second instance and moving one database's data across
  without downtime; chosen over full parity because the VPS already commits ~6.5G of its
  7.8G to the existing app+Redpanda containers, and the incident's root cause (Neon's
  *per-project* compute-hour billing) doesn't transfer to self-hosted Postgres — there's no
  analogous shared-quota mechanism a self-hosted instance can exhaust the way Neon did.
- **D-02:** Postgres version 16, matching what `docker-compose.yml` (local dev) and the
  Testcontainers-backed test suite already run — not Postgres 18 (what Neon ran). —
  **Reversibility:** costly — **rationale:** a later major-version bump needs its own
  `pg_upgrade`/dump-restore pass; picked to keep one Postgres version tested everywhere
  (dev/test/prod) rather than matching Neon's version, since Neon is being removed entirely.
- **D-03:** No host port published for Postgres — internal-only on the Compose network,
  mirroring Redpanda's existing INFRA-08 rule (no Kafka port ever internet-facing). Admin
  access (backups, ad-hoc `psql`) happens via SSH + `docker exec` on the VM.

### Data migration & cutover
- **D-04:** Fresh start — do NOT carry over existing data from Neon via `pg_dump`/`pg_restore`.
  Flyway (V1–V8) builds the schema from scratch against the new empty self-hosted instance. —
  **Reversibility:** one-way — **rationale:** whatever real board/task/user data currently
  exists in the Neon project is deliberately left behind; there is no plan to recover it later.
  This is acceptable because the project is a personal/portfolio showcase, not one with
  external users depending on their data, and production is already fully down.
- **D-05:** No downtime/timing constraint on the cutover window — production is already
  unreachable (Neon's quota block, not resolvable until 2026-09-01 or a plan upgrade), so
  there is no live traffic to protect during migration. Take whatever time is needed to do it
  correctly.
- **D-06:** Production and nonprod migrate together, in this same phase — not production
  first with nonprod deferred to a follow-up. Nonprod is already the disposable/resettable
  environment (its own admin reset endpoint TRUNCATEs everything), so it's the lower-risk half
  and proves the self-hosted setup works before anything that matters goes through it.
- **D-07:** Delete the Neon project once the self-hosted cutover is verified working — not
  kept around dormant as a rollback path. — **Reversibility:** one-way — **rationale:**
  matches this project's existing decommission discipline (the AWS EC2/RDS teardown after the
  earlier infra pivot); once deleted there is no path back to Neon without re-provisioning
  from scratch. Do NOT delete the Neon project until the self-hosted instance is confirmed
  working end-to-end.

### VPS resource budget & Hikari/JDBC pool re-tuning
- **D-08:** Measure the new Postgres container's real memory floor live — the same
  restart-ladder measurement discipline already used for both Redpanda containers (NONPROD-06,
  05-04 Task 3) — rather than assigning a `mem_limit` by arithmetic guess.
- **D-09:** Revisit the Neon-specific Hikari/JDBC tuning in `application.properties` as part of
  this phase: `connection-timeout=30000` (absorbed Neon's cold start), `minimum-idle=0`/
  `keepalive-time=0` (just set 2026-08-26, specifically to let Neon scale to zero — see the
  incident record), `sslmode=require&channel_binding=require`, and `prepareThreshold=0` (a
  PgBouncer transaction-mode-pooling workaround). A local same-host Postgres has no cold start,
  no autosuspend, and no PgBouncer in front of it, so this rationale no longer applies as-is —
  don't carry it forward unexamined.
- **D-10:** Also re-measure the app container's own `mem_limit` (currently `3g`, sized in
  05-04 Task 3 partly around Neon's remote-latency assumptions) alongside the Postgres
  measurement pass in D-08, since DB latency drops from a network hop to local/Unix-socket.
- **D-11:** Default Postgres's own configuration to a conservative profile (small
  `shared_buffers`, small `work_mem`, low `max_connections`) rather than generic
  dedicated-server defaults (e.g. the commonly-cited 25%-of-RAM `shared_buffers` guidance) —
  this is a shared 4-vCPU/7.8G VPS already running a JVM app and a Kafka broker, with a small
  HikariCP pool (max 5) and near-zero concurrent traffic.

### Backups & CI Flyway-verify
- **D-12:** Document the loss of Neon's built-in point-in-time recovery in
  `docs/INFRA_RUNBOOK.md` (what backup coverage exists now — none — and what a manual restore
  procedure would look like), but do NOT build automated backup tooling (cron `pg_dump`, a
  sidecar, etc.) as part of this phase. — **Reversibility:** reversible — **rationale:**
  narrower scope for this phase; automation can be added later. Flagged explicitly because this
  is a real, acknowledged regression from what Neon provided by default — not an oversight.
  Relates to the still-open, not-folded todo `.planning/todos/pending/2026-08-20-no-documented-backup-restore-runbook-for-prod-db.md`
  (originally scoped around confirming Neon's PITR, now moot).
- **D-13:** Replace deploy.yml's `flyway-verify`/`flyway-verify-nonprod` jobs (which currently
  reach Neon's public direct endpoint straight from GitHub Actions runners) with an SSH tunnel
  from the runner into the VM's internal Docker network, reusing the existing SSH deploy
  credentials — not dropping the pre-merge check, and not spinning up a throwaway ephemeral
  Postgres in CI instead. — **Reversibility:** costly — **rationale:** preserves the existing
  pre-merge guarantee (migrations verified against the real reachable instance before merge)
  without exposing Postgres to the internet just for CI, consistent with D-03's internal-only
  exposure decision.

### Claude's Discretion
None — every gray area surfaced during discussion had an explicit user decision (no "you
decide" was selected on any final answer).

### Reviewed Todos (not folded)
- `.planning/todos/pending/2026-08-20-no-documented-backup-restore-runbook-for-prod-db.md` —
  reviewed but the user chose not to formally fold it into this phase's scope as a tracked
  item; its substance is still addressed by D-12 above (document the gap in
  `docs/INFRA_RUNBOOK.md`).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Incident record (why this phase exists)
- `.planning/debug/admin-reset-500-nonprod.md` — full investigation of the 2026-08-26 outage:
  root cause (Neon's per-project shared compute-hour quota exhausted by always-warm HikariCP
  settings), the preventive fix already applied (`minimum-idle=0`/`keepalive-time=0`), and the
  explicit note that this is a stopgap, not a structural fix — self-hosting is the structural fix.

### Current infra state
- `docs/INFRA_RUNBOOK.md` — production infra runbook; has an existing "Database — Neon"
  section (to be replaced/superseded by this phase) and the measurement methodology this
  phase's D-08/D-10 should follow (see its Redpanda/nonprod resource-measurement sections).
- `src/main/resources/application.properties` — datasource URL template, Hikari pool block
  (lines ~67–139), and its dated 2026-08-26 "Decisions" comment record explaining the current
  Neon-specific tuning that D-09 revisits.
- `docker-compose.prod.yml` — production Compose manifest; explicitly has NO `postgres`
  service today ("Neon is the production database" — that comment becomes stale once this
  phase adds one). Redpanda's service definition here is the pattern to mirror for the new
  Postgres service (resource caps, healthcheck, named volume, no `ports:` entry).
- `docker-compose.nonprod.yml` — nonprod Compose manifest, same current gap; also has the
  memory-measurement methodology and evidence trail (in its Redpanda-nonprod comment block)
  that D-08 should replicate for Postgres.
- `docker-compose.yml` — local dev Compose file, already runs `postgres:16` (host port 5433)
  — the version/image this phase's self-hosted instance should match per D-02.
- `.github/workflows/deploy.yml` — `flyway-verify`/`flyway-verify-nonprod` jobs (~lines
  159–237) that D-13 replaces; currently guard against Neon's pooled endpoint and connect with
  `sslmode=require` from the runner directly to Neon's public host.
- `.env.prod.example` / `.env.nonprod.example` — current shape of `DB_HOST`/`DB_PORT`/
  `DB_NAME`/`DB_USER`/`DB_PASS`/`DB_JDBC_PARAMS`, all of which change meaning once the target
  is a local container instead of a remote Neon endpoint.

### Project-level context
- `.planning/PROJECT.md` — Key Decisions table (production redeploy history, AWS EC2/RDS
  decommission precedent that D-07 follows) and Context section's infra history.
- `.planning/todos/pending/2026-08-20-no-documented-backup-restore-runbook-for-prod-db.md` —
  see D-12/Reviewed Todos above.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `docker-compose.yml`'s `postgres` service block — the local-dev Postgres 16 container
  definition (image, env vars, named volume) is the closest existing analog for the new
  self-hosted production/nonprod service, though it will need production-appropriate
  additions (resource caps, healthcheck, logging anchor, no host port) matching how
  `docker-compose.prod.yml`'s `redpanda` service was built out from the dev version.
- `docker-compose.prod.yml` / `docker-compose.nonprod.yml`'s Redpanda service blocks and
  their `x-logging` anchors — the established pattern for a self-hosted, resource-capped,
  internal-only, healthchecked service on this VPS. The new Postgres service should follow
  the same shape (mem_limit, logging, named volume, healthcheck, no `ports:`).
- The NONPROD-06 / 05-04 Task 3 memory-measurement methodology (documented in
  `docs/INFRA_RUNBOOK.md`'s "Nonprod resource measurement" and "Manual deploy... Task 3"
  sections) — a restart-ladder descent under a real burst workload, re-verified end-to-end,
  not a single startup snapshot. D-08/D-10 should reuse this exact method.

### Established Patterns
- Every resource-capped service on this VPS documents its cap with a dated,
  measurement-backed comment (not an arithmetic guess) — both Redpanda blocks and the app
  service's `mem_limit: 3g` follow this. The new Postgres service and any re-measured app
  `mem_limit` should match this documentation discipline.
- Flyway owns all schema creation/alteration (`spring.jpa.hibernate.ddl-auto=validate`) —
  unaffected by this migration; the self-hosted instance starts empty and Flyway builds it,
  same as it always has against Neon.
- `docs/INFRA_RUNBOOK.md` accumulates one dated section per plan/task, with measured
  evidence inline rather than summarized — the new Postgres sections should follow this
  existing structure rather than replacing the file's overall shape.

### Integration Points
- `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASS`/`DB_JDBC_PARAMS` env vars, read identically
  by both `docker-compose.prod.yml`'s `app` service and `docker-compose.nonprod.yml`'s
  `app-nonprod` service — this phase changes what these resolve to (a local container
  hostname/port instead of a Neon endpoint) but not the variable contract itself, so
  `application.properties`' datasource URL template does not need to change shape, only the
  env values supplied to it (and D-09's Hikari-tuning values, which do live in that file).

</code_context>

<specifics>
## Specific Ideas

No UI/visual specifics — this is a pure infrastructure migration. The two concrete
reference points the user grounded decisions in were: (1) the Redpanda self-hosting pattern
already proven twice on this VPS (production and nonprod), which this migration should mirror
for topology, resource-measurement methodology, and network-exposure discipline; and (2) the
incident record itself (`.planning/debug/admin-reset-500-nonprod.md`), which is the reason
this phase exists and the source of the D-01 root-cause explanation (why Neon's per-project
quota mechanism doesn't have a self-hosted analog).

</specifics>

<deferred>
## Deferred Ideas

- Automated backup tooling for the self-hosted Postgres instance (scheduled `pg_dump`, tested
  restore procedure, retention policy) — explicitly deferred by D-12 to a future phase/todo;
  only documentation of the gap ships in this phase.
- Re-splitting the shared Postgres instance into two fully separate containers (full parity
  with the Redpanda prod/nonprod split) — not ruled out permanently, just not chosen now (D-01)
  given the VPS's current memory budget; worth revisiting if nonprod's workload ever grows
  enough to risk starving production's connections.

### Reviewed Todos (not folded)
See `<decisions>` § Reviewed Todos above.

</deferred>

---

*Phase: 11-migrate-database-from-neon-to-self-hosted-postgres*
*Context gathered: 2026-08-26*
