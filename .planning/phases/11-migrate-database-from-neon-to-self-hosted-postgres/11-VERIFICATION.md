---
phase: 11-migrate-database-from-neon-to-self-hosted-postgres
verified: 2026-08-26T16:34:39Z
status: gaps_found
score: 9/11 must-haves verified
behavior_unverified: 0
overrides_applied: 0
gaps:
  - truth: "Postgres runs on an internally consistent, deliberately conservative memory profile — the configured shared_buffers fits inside the container's own mem_limit with real headroom for concurrent-backend overhead (D-08, D-11)."
    status: failed
    reason: >-
      docker-compose.prod.yml sets shared_buffers=128MB (line 158) while capping the same
      container at mem_limit: 64m (line 130) — the buffer-pool allocation is double the cgroup
      ceiling that bounds it. This is not a hypothetical: 11-REVIEW.md's CR-02 (BLOCKER, filed
      2026-08-26, same day as this migration) already identified this exact inconsistency and it
      remains unfixed in the current tree. The restart-ladder measurement that adopted 64m
      (docs/INFRA_RUNBOOK.md, "Self-hosted Postgres resource measurement — Plan 11-03") used a
      54-57-request sequential burst against a ~55-row dataset — a workload that never exercised
      concurrent-backend memory pressure (multiple connections touching distinct shared_buffers
      pages simultaneously, index scans across a wider working set, or max_connections=25 actually
      being exercised concurrently with work_mem=4MB sorts/hashes). Peak RSS staying at ~29-30MiB
      under that workload is evidence about that one synthetic test, not a bound on the config's
      real safety envelope under realistic concurrent traffic — and the failure mode this exact
      config class already produced once (kernel OOM-killing postgres at the adjacent 32m rung,
      forcing crash recovery and live HTTP 500s) is still reachable if concurrent load ever pushes
      resident memory past 64MiB.
    artifacts:
      - path: "docker-compose.prod.yml"
        issue: "postgres service: mem_limit: 64m (line 130) vs. shared_buffers=128MB (line 158) — buffer pool allocation exceeds its own cgroup cap"
    missing:
      - "Lower shared_buffers to a fraction of mem_limit (e.g. 24-32MB) leaving headroom for per-backend work_mem/connection overhead under the 64m cap, OR raise mem_limit to comfortably exceed shared_buffers plus realistic concurrent per-backend overhead (shared_buffers=128MB + max_connections=25 × work_mem=4MB ≈ 228MB minimum plus baseline overhead — likely mem_limit: 256m or higher)."
      - "Re-validate under a concurrent multi-connection workload (all app/app-nonprod HikariCP connections issuing queries simultaneously, not sequentially) before trusting a new floor."
  - truth: "The first-boot provisioning mechanism that realizes D-01's isolation is free of injection-shaped defects — credential values are safely quoted, not spliced unescaped into a SQL literal (implicit precondition of D-01's provisioning artifact being production-safe)."
    status: failed
    reason: >-
      docker/postgres-init/01-create-databases-and-roles.sh:41-51 interpolates
      $PROD_DB_USER/$PROD_DB_PASS/$NONPROD_DB_USER/$NONPROD_DB_PASS directly into a psql heredoc
      SQL literal with no escaping (`CREATE ROLE "${PROD_DB_USER}" WITH LOGIN PASSWORD
      '${PROD_DB_PASS}';`). 11-REVIEW.md's CR-01 (BLOCKER) flagged this exact defect and it remains
      unfixed in the current tree. The script's own comment acknowledges the hazard and relies
      entirely on an unenforced operational convention (every password generated via `openssl rand
      -hex 32`, which cannot contain a single quote) — nothing in the script itself validates or
      escapes the incoming values before they run as the Postgres superuser at first boot. This is
      the textbook SQL-injection anti-pattern (interpolating externally-sourced strings into a SQL
      statement) even though today's actual inputs are safe by convention, not by construction —
      any future change to how these values are generated (password manager, rotated-secret
      pipeline, an operator typing a value by hand) that ever produces a value containing `'`
      breaks provisioning in a way that's hard to diagnose from the container log, or in the worst
      case runs attacker-influenced SQL as the Postgres superuser.
    artifacts:
      - path: "docker/postgres-init/01-create-databases-and-roles.sh"
        issue: "Lines 41-51: PROD_DB_USER/PROD_DB_PASS/NONPROD_DB_USER/NONPROD_DB_PASS interpolated unescaped into a psql heredoc SQL literal"
    missing:
      - "Use psql's :'var' (SQL-literal quoting) / :\"var\" (identifier quoting) variable substitution via -v prod_user=... -v prod_pass=... instead of shell interpolation into the heredoc — closes the hole regardless of what future credential-generation tooling produces."
      - "This script provisions on first boot only (runs once against an empty data directory); since the current volume is already provisioned, a fix here protects only future re-provisioning (a fresh volume, a DR restore) — but the defect ships in the repo as committed code today and is not gated behind that caveat."
---

# Phase 11: Migrate database from Neon to self-hosted Postgres Verification Report

**Phase Goal:** Both production and nonprod run against a single self-hosted PostgreSQL 16
container on the existing Netcup VPS — two databases, two least-privilege roles that cannot reach
each other's data, no host port published — with Neon decommissioned, the pool/JDBC tuning
re-derived for a same-host engine, CI's pre-merge Flyway gate preserved over SSH, and the
resulting loss of point-in-time recovery documented as an acknowledged gap.

**Verified:** 2026-08-26T16:34:39Z
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A single postgres:16 container provisions BOTH production and nonprod databases, each with one least-privilege role that cannot reach the other's data (D-01, D-02) | ✓ VERIFIED | `docker-compose.prod.yml:94-170` — one `postgres:16` service, `PROD_DB_*`/`NONPROD_DB_*` env vars; `docker/postgres-init/01-create-databases-and-roles.sh:41-51` — `CREATE ROLE`/`CREATE DATABASE`/`REVOKE CONNECT ... FROM PUBLIC`/`GRANT CONNECT` per database. Cross-isolation proven live on the VM per 11-02-SUMMARY.md ("docker exec psql refusal/success proof, captured verbatim in docs/INFRA_RUNBOOK.md 'Isolation proof (D-01)'") |
| 2 | No host port is published for Postgres; both app services reach it only over Docker networks (D-03) | ✓ VERIFIED | `docker-compose.prod.yml:94-170` postgres service block has no `ports:` key (confirmed by direct read of the full block — only `environment`, `command`, `networks`, `volumes`, healthcheck). `docker-compose.nonprod.yml` has no postgres service of its own; `app-nonprod` joins the external `kanban-db` network to reach the shared instance by DNS name |
| 3 | Both production's `app` and nonprod's `app-nonprod` resolve the same shared instance across two separate Compose projects (D-01, D-06) | ✓ VERIFIED | `docker-compose.prod.yml` declares external network `kanban-db` (owns `postgres`); `docker-compose.nonprod.yml:42-48` declares the same external `kanban-db` network, joined by `app-nonprod` (`docker-compose.nonprod.yml:165`) |
| 4 | Postgres runs an internally consistent, deliberately conservative memory/connection profile (D-11), and its mem_limit is a live-measured floor with a genuine failing rung below it (D-08) | ✗ FAILED | See gap 1 above — `shared_buffers=128MB` exceeds `mem_limit: 64m`; the measurement methodology that adopted 64m did not exercise concurrent-backend memory pressure, per 11-REVIEW.md CR-02, still unresolved |
| 5 | The provisioning mechanism realizing D-01's isolation is free of injection-shaped defects | ✗ FAILED | See gap 2 above — `docker/postgres-init/01-create-databases-and-roles.sh:41-51` splices credential values unescaped into a SQL literal, per 11-REVIEW.md CR-01, still unresolved |
| 6 | Fresh start — no data carried over from Neon; Flyway V1..V8 builds both schemas from scratch (D-04) | ✓ VERIFIED | 11-02-SUMMARY.md coverage D3: "Both databases show 8/8 successful Flyway migrations, built from scratch, no data carried over" — verified live on the VM |
| 7 | Both production and nonprod public HTTPS health endpoints respond 200, backed by the self-hosted instance, with a write surviving an app restart (D-06) | ✓ VERIFIED | 11-02-SUMMARY.md coverage D2: "curl https://.../api/actuator/health from off-VM (both envs); board created/verified/restart-survived through the public production API" |
| 8 | HikariCP/JDBC tuning is re-derived for a same-host engine, not carried forward from Neon's cold-start/autosuspend/pooler assumptions (D-09) | ✓ VERIFIED | `src/main/resources/application.properties:32-127` — full re-derived pool block (maximum-pool-size=5, minimum-idle=2, connection-timeout=10000, idle-timeout=600000, max-lifetime=1800000, keepalive-time=120000), `DB_JDBC_PARAMS` removed, prior 2026-08-26 Neon-era decision record superseded in place, not deleted. Removal of `prepareThreshold=0` smoke-tested live (11-04-SUMMARY.md: "1028 server-side named-statement executions observed... workload survived a mid-session engine restart, zero prepared-statement exceptions") |
| 9 | The pre-merge Flyway CI verification gate is preserved, reaching the database only through the VM's SSH identity over the internal Docker network — no host port exception (D-03, D-13) | ✓ VERIFIED | `.github/workflows/deploy.yml:168-233` (`flyway-verify`) — SCP's migration files to the VM, then SSH-runs `docker run --rm --network kanban-db postgres:16 pg_isready` and `flyway/flyway:11.7.2 migrate` against `postgres:5432` over the internal network; no port publication added anywhere. Live green run confirmed per 11-05-SUMMARY.md (run 32985965535, both jobs' logs showing correct per-environment database names) |
| 10 | Neon is decommissioned — project deleted only after the self-hosted cutover is confirmed working end-to-end, via an explicit recorded human decision (D-07) | ✓ VERIFIED | 11-06-SUMMARY.md: "Neon project deleted, confirmed via control-plane API (list_projects empty + describe_project 404)"; decision record cites five live-checked gate conditions plus operator's explicit delete-now authorization |
| 11 | The loss of Neon's point-in-time recovery is documented as an acknowledged regression, with a written (though untested) manual restore procedure — no automated backup tooling built (D-12) | ✓ VERIFIED | `docs/INFRA_RUNBOOK.md:206-264` "Backups and restore" — opens with "There is no backup of the production database... none exists," full `pg_dump`/`pg_restore` command shapes for this deployment's real container/volume names, explicit "written but has never been executed" caveat, explicit scope-out of automation |

**Score:** 9/11 truths verified (0 present, behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `docker/postgres-init/01-create-databases-and-roles.sh` | First-boot provisioning of 2 databases + 2 roles with explicit PUBLIC CONNECT revocation | ⚠️ VERIFIED WITH DEFECT | Exists, substantive, wired (mounted at `/docker-entrypoint-initdb.d`), REVOKE logic correct — but unescaped SQL interpolation (CR-01, unresolved) |
| `docker-compose.prod.yml` | New `postgres` service, `kanban-db` network, `postgres-data` volume, `app` rewired | ⚠️ VERIFIED WITH DEFECT | Exists, wired, no host port — but `shared_buffers`/`mem_limit` internally inconsistent (CR-02, unresolved) |
| `docker-compose.nonprod.yml` | `kanban-db` network declared and joined by `app-nonprod`, DB env retargeted | ✓ VERIFIED | Confirmed lines 42-48, 165 |
| `.env.prod.example` / `.env.nonprod.example` | Env-file contract for the new topology, including D-01 cross-environment credential crossover | ✓ VERIFIED (indirect) | Confirmed present via 11-REVIEW.md's WR-02 finding (quotes both files' exact content); direct read blocked by this session's own permission settings on `.env*` files — corroborated by independent review evidence, not re-read directly |
| `.github/workflows/deploy.yml` | SCP step carries init script to VM; `flyway-verify`/`flyway-verify-nonprod` rewritten for SSH | ✓ VERIFIED | Confirmed lines 168-233 and surrounding; dead Neon-endpoint-suffix guard removed, replaced with `pg_isready` reachability check |
| `src/main/resources/application.properties` | Revised datasource URL template and HikariCP block with new dated decision record | ✓ VERIFIED | Confirmed lines 32-127 |
| `docs/INFRA_RUNBOOK.md` | Dated sections: cutover, memory measurement, CI mechanism, decommission record, backup gap | ✓ VERIFIED | All sections present and cross-referenced (grep confirms Neon references are historical/superseded framing, not live current-state claims) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `postgres` service name | DNS alias on both `default` (prod project) and `kanban-db` (cross-project) network | Compose `networks:` declaration | ✓ WIRED | `docker-compose.prod.yml` postgres service lists both `default` and `kanban-db`; `docker-compose.nonprod.yml` app-nonprod joins `kanban-db` |
| `REVOKE CONNECT ... FROM PUBLIC` | D-01's isolation requirement | Init script SQL | ✓ WIRED (logic correct, injection-shaped defect present — see gap 2) | Present for both databases in the init script |
| `deploy.yml` SCP source | Init script present on VM before first `up -d postgres` | `deploy-to-netcup` job's SCP step | ✓ WIRED | Confirmed by 11-02-SUMMARY.md live cutover evidence (provisioning succeeded on the real VM) |
| Flyway CI verification | Self-hosted instance over SSH | `docker run --network kanban-db ... flyway/flyway migrate` inside an `appleboy/ssh-action` step | ✓ WIRED | Confirmed `.github/workflows/deploy.yml:190-233`; live green run 32985965535 per 11-05-SUMMARY.md |
| `shared_buffers=128MB` (postgres `command:`) | `mem_limit: 64m` (postgres cgroup cap) | Compose resource cap vs. engine startup flag | ✗ NOT CONSISTENT | See gap 1 — the two values contradict each other; not a wiring gap but an internal-consistency gap |

### Requirements Coverage

All of D-01 through D-13 (the phase's declared requirement set, per 11-CONTEXT.md; no REQUIREMENTS.md exists for this milestone) are claimed as `requirements-completed` across the six plan SUMMARYs with no orphans:

| Requirement | Source Plan | Status | Evidence |
|-------------|-------------|--------|----------|
| D-01 | 11-01, 11-02 | ✓ SATISFIED | Two-database/two-role isolation, live-proven refusal both directions |
| D-02 | 11-01 | ✓ SATISFIED | `postgres:16` image, matches dev/test |
| D-03 | 11-01, 11-05 | ✓ SATISFIED | No `ports:` entry anywhere; CI reaches via SSH, not a published port |
| D-04 | 11-02 | ✓ SATISFIED | Fresh start, 8/8 Flyway on both DBs, no data carried over |
| D-05 | 11-02 | ✓ SATISFIED | No downtime constraint — cutover proceeded on an already-down production |
| D-06 | 11-01, 11-02 | ✓ SATISFIED | Both environments cut over together, nonprod first, both healthy |
| D-07 | 11-06 | ✓ SATISFIED | Neon deleted only after live gate + explicit human decision |
| D-08 | 11-03 | ⚠️ SATISFIED WITH CAVEAT | Restart-ladder floor measured and a genuine failing rung found — but see gap 1: the workload used does not validate the floor against the config's own `shared_buffers` setting under concurrent load |
| D-09 | 11-04 | ✓ SATISFIED | Full HikariCP re-derivation, decision record superseded in place, prepared-statement removal smoke-tested |
| D-10 | 11-03 | ✓ SATISFIED | App/app-nonprod mem_limits re-measured with evidence-backed keep verdicts |
| D-11 | 11-01, 11-03 | ⚠️ SATISFIED WITH CAVEAT | Conservative profile chosen and re-examined post-measurement — but see gap 1: the profile itself is internally inconsistent |
| D-12 | 11-06 | ✓ SATISFIED | Backup gap documented honestly with a written (untested) restore procedure, automation explicitly deferred |
| D-13 | 11-05 | ✓ SATISFIED | Gate preserved over SSH, live green run, gate proven to still fail on a broken migration |

No orphaned requirements found.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `docker/postgres-init/01-create-databases-and-roles.sh` | 41-51 | Unescaped shell-to-SQL interpolation of credential values | 🛑 Blocker | See gap 2 — SQL-injection-shaped hole in a script that runs as Postgres superuser |
| `docker-compose.prod.yml` | 130, 158 | `shared_buffers` exceeds `mem_limit` | 🛑 Blocker | See gap 1 — real OOM risk under concurrent load, not validated by the sequential burst test used |
| `.github/workflows/deploy.yml` | 168-233, 239-285 | No `concurrency:` guard on `flyway-verify`/`flyway-verify-nonprod` (unlike the deploy jobs, which have one for the identical reason) | ⚠️ Warning | Two rapid pushes can interleave SCP+read against the same shared VM staging directory (11-REVIEW.md WR-01) — does not block the phase goal, not required by any must-have, but degrades CI robustness |
| `.env.prod.example` / `.env.nonprod.example` + init script | — | Cross-file password identity (`NONPROD_DB_PASS` = `DB_PASS`) enforced only by a comment, not a check; init script's one-shot-on-first-boot nature means editing `.env.prod` alone does not rotate the live role password | ⚠️ Warning | Operational drift risk (11-REVIEW.md WR-02), not a phase-goal blocker |
| `docs/INFRA_RUNBOOK.md` | Backups section | No backup exists for either database | ⚠️ Warning (explicitly accepted) | D-12's knowingly-accepted, documented trade-off — not a gap, listed here only because 11-REVIEW.md's own Security criteria flags it as a real, live risk regardless of it being consciously accepted |

No debt markers (`TBD`/`FIXME`/`XXX`) found in any file modified by this phase.

### Human Verification Required

None — no items required human-only judgment beyond what the plans' own live-infrastructure evidence (captured verbatim in each SUMMARY.md and docs/INFRA_RUNBOOK.md) already provides, per this verification's scope (repository-state checks; live infra state is trusted from that verbatim evidence per the task's own instructions).

### Gaps Summary

Two BLOCKER-severity findings from `11-REVIEW.md` (dated the same day as this migration) remain
present, verbatim, in the current repository state — neither has been fixed by any commit after
the review:

1. **CR-02 / mem_limit inconsistency** — `docker-compose.prod.yml`'s `postgres` service is capped
   at `mem_limit: 64m` while started with `shared_buffers=128MB`, double its own cgroup ceiling.
   The restart-ladder measurement that adopted `64m` (Plan 11-03) used a light, sequential,
   single-workload burst against a ~55-row dataset that never exercised concurrent-backend memory
   pressure — the exact failure mode (kernel OOM-killing postgres, forcing crash recovery, live
   HTTP 500s) that already occurred once at the adjacent 32m rung during measurement remains
   reachable under realistic concurrent load. This directly undermines the phase-goal claim that
   Postgres runs a "deliberately conservative memory/connection profile" (D-11) and that the
   mem_limit is a trustworthy "live-measured floor" (D-08) — the floor was measured against a
   workload that cannot expose this specific failure mode.

2. **CR-01 / SQL injection-shaped defect** — the first-boot provisioning script
   (`docker/postgres-init/01-create-databases-and-roles.sh`) splices `PROD_DB_PASS`,
   `NONPROD_DB_PASS`, and the corresponding user/db names directly into a `psql` heredoc SQL
   literal with no escaping. The mitigation is a pure operational convention (values must be
   generated via `openssl rand -hex 32`, guaranteeing no embedded `'`), enforced nowhere in the
   script itself. This is the textbook SQL-injection anti-pattern, present in code that runs as
   the Postgres superuser exactly once, at first container boot — a mechanism this phase's D-01
   isolation guarantee depends on being production-safe.

Both defects were already correctly identified by this phase's own code review before this
verification ran; neither has since been remediated in the tree. Everything else this phase set
out to deliver — the shared multi-database/multi-role topology, the live VM cutover with proven
isolation, the re-derived HikariCP tuning, the SSH-based CI Flyway gate with a live green run, and
the Neon decommission with an honestly-documented backup-gap regression — is genuinely present,
substantive, and wired, corroborated by verbatim live-infrastructure evidence in each plan's
SUMMARY.md and in `docs/INFRA_RUNBOOK.md`'s dated sections.

**This looks like an oversight, not an accepted trade-off** — both findings were flagged by this
phase's own review process and neither the review nor any SUMMARY.md records an explicit decision
to ship with either defect. If either is in fact an intentional, accepted deviation, add an
override to this file's frontmatter:

```yaml
overrides:
  - must_have: "Postgres runs on an internally consistent, deliberately conservative memory profile (D-08, D-11)"
    reason: "<why shipping shared_buffers > mem_limit is acceptable>"
    accepted_by: "<name>"
    accepted_at: "<ISO timestamp>"
  - must_have: "The first-boot provisioning mechanism is free of injection-shaped defects"
    reason: "<why the unescaped interpolation is acceptable>"
    accepted_by: "<name>"
    accepted_at: "<ISO timestamp>"
```

---

_Verified: 2026-08-26T16:34:39Z_
_Verifier: Claude (gsd-verifier)_
