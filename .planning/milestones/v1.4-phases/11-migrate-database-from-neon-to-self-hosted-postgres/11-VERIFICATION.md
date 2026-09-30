---
phase: 11-migrate-database-from-neon-to-self-hosted-postgres
verified: 2026-08-26T21:30:00Z
status: passed
score: 11/11 must-haves verified
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 9/11
  gaps_closed:
    - "Postgres runs an internally consistent, deliberately conservative memory/connection profile (D-11), and its mem_limit is a live-measured floor with a genuine failing rung below it (D-08)"
    - "The provisioning mechanism realizing D-01's isolation is free of injection-shaped defects"
  gaps_remaining: []
  regressions: []
---

# Phase 11: Migrate database from Neon to self-hosted Postgres Verification Report

**Phase Goal:** Both production and nonprod run against a single self-hosted PostgreSQL 16
container on the existing Netcup VPS — two databases, two least-privilege roles that cannot reach
each other's data, no host port published — with Neon decommissioned, the pool/JDBC tuning
re-derived for a same-host engine, CI's pre-merge Flyway gate preserved over SSH, and the
resulting loss of point-in-time recovery documented as an acknowledged gap.

**Verified:** 2026-08-26T21:30:00Z
**Status:** passed
**Re-verification:** Yes — after gap closure (plans 11-07, 11-08), plus verification of a new
critical finding raised by the regenerated 11-REVIEW.md and reportedly fixed in commit `8cb3deb`.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A single postgres:16 container provisions BOTH production and nonprod databases, each with one least-privilege role that cannot reach the other's data (D-01, D-02) | ✓ VERIFIED | `docker-compose.prod.yml:94-241` — one `postgres:16` service; `docker/postgres-init/01-create-databases-and-roles.sh:58-66` — `CREATE ROLE`/`CREATE DATABASE`/`REVOKE CONNECT ... FROM PUBLIC`/`GRANT CONNECT` per database, unchanged from prior verification pass. Cross-isolation re-confirmed live in THIS pass (see Behavioral Spot-Checks: `verify-postgres-init-quoting.sh --case all` → 7/7 pass, both roles correctly refused the other's database) |
| 2 | No host port is published for Postgres; both app services reach it only over Docker networks (D-03) | ✓ VERIFIED | `docker-compose.prod.yml` postgres service block re-confirmed to have no `ports:` key |
| 3 | Both production's `app` and nonprod's `app-nonprod` resolve the same shared instance across two separate Compose projects (D-01, D-06) | ✓ VERIFIED | `docker-compose.prod.yml`/`docker-compose.nonprod.yml` both declare/join external `kanban-db` network, unchanged |
| 4 | Postgres runs an internally consistent, deliberately conservative memory/connection profile (D-11), and its mem_limit is a live-measured floor with a genuine failing rung below it (D-08) | ✓ VERIFIED | **Gap 1 closed by plan 11-08.** `docker-compose.prod.yml:178` `mem_limit: 256m`, `:212` `shared_buffers=64MB`. Ran `scripts/verify-postgres-memory-invariant.py` directly against the current file in this verification pass: exits 0, prints "invariants OK: mem_limit=256MB shared_buffers=64MB work_mem=4MB max_connections=25 worst-case=164MB (64.1% of cap)". Independently confirmed the script correctly FAILS against the pre-fix pair by mutating a scratch copy (`mem_limit: 64m` / `shared_buffers=128MB` → exit 1, both I1 and I2 violations reported) — proving the check is a real gate, not a rubber stamp. `docs/INFRA_RUNBOOK.md`'s "Postgres memory profile correction — Plan 11-08" section records a live concurrent-backend re-validation (24 backends, 21 non-idle, anon+shmem ~84MB / 32.8% of the 256m cap) plus a live counter-evidence reproduction of the original OOM-kill under the unfixed pairing |
| 5 | The provisioning mechanism realizing D-01's isolation is free of injection-shaped defects | ✓ VERIFIED | **Gap 2 closed by plan 11-07.** `docker/postgres-init/01-create-databases-and-roles.sh:51-67` now uses `psql -v` with `:"var"`/`:'var'` server-side substitution inside a single-quoted heredoc — no shell interpolation into the SQL literal. Ran `scripts/verify-postgres-init-quoting.sh --case all` directly in this verification pass (live Docker container, hostile apostrophe/SQL-injection-payload/malicious-identifier credential values): 7/7 checks passed, including "no 'pwned' database exists" and both roles' cross-database refusal |
| 6 | Fresh start — no data carried over from Neon; Flyway V1..V8 builds both schemas from scratch (D-04) | ✓ VERIFIED | Unchanged since prior pass — 11-02-SUMMARY.md live evidence |
| 7 | Both production and nonprod public HTTPS health endpoints respond 200, backed by the self-hosted instance, with a write surviving an app restart (D-06) | ✓ VERIFIED | Unchanged since prior pass — 11-02-SUMMARY.md live evidence |
| 8 | HikariCP/JDBC tuning is re-derived for a same-host engine, not carried forward from Neon's cold-start/autosuspend/pooler assumptions (D-09) | ✓ VERIFIED | `src/main/resources/application.properties:197-202` re-confirmed unchanged: `maximum-pool-size=5`, `minimum-idle=2`, `connection-timeout=10000`, `idle-timeout=600000`, `max-lifetime=1800000`, `keepalive-time=120000`. Note: 11-REVIEW.md's WR-02 (deferred, pre-existing from plan 11-04, outside this pass's CR-01/CR-02 scope) flags that the *narrative* in this file's "Decisions" comment misstates the historical before-state (claims `minimum-idle=0`/`keepalive-time=0`; git history shows `1`/`120000`) — a documentation-accuracy defect in prose, not in the shipped config values themselves, and does not affect this truth |
| 9 | The pre-merge Flyway CI verification gate is preserved, reaching the database only through the VM's SSH identity over the internal Docker network — no host port exception (D-03, D-13) | ✓ VERIFIED | Unchanged since prior pass — `.github/workflows/deploy.yml:168-233` re-confirmed, `docker run --network kanban-db postgres:16 pg_isready` / `flyway/flyway:11.7.2 migrate` over the internal network only |
| 10 | Neon is decommissioned — project deleted only after the self-hosted cutover is confirmed working end-to-end, via an explicit recorded human decision (D-07) | ✓ VERIFIED | Unchanged since prior pass — 11-06-SUMMARY.md live control-plane evidence |
| 11 | The loss of Neon's point-in-time recovery is documented as an acknowledged regression, with a written (though untested) manual restore procedure — no automated backup tooling built (D-12) | ✓ VERIFIED | Unchanged since prior pass — `docs/INFRA_RUNBOOK.md:206-264` |

**Score:** 11/11 truths verified (0 present, behavior-unverified)

### New Finding From Regenerated 11-REVIEW.md — Verified Fixed

The regenerated code review (11-REVIEW.md, dated 2026-08-26) found a NEW critical issue during its
re-check of the two closed gaps: `docker-compose.prod.yml`'s comment and the matching
`docs/INFRA_RUNBOOK.md` passage both claimed the memory invariant was "mechanically enforced ...
by this plan's verify script," but no such script existed anywhere in the repository — the actual
check had run only once as an uncommitted heredoc during plan 11-08's execution. This is exactly
the CR-02 failure class (an unenforced claim) reintroduced silently.

**Verified fixed, independently, in this pass — not merely trusted from the review's `resolution:`
annotation:**

- `scripts/verify-postgres-memory-invariant.py` exists (`git show --stat 8cb3deb` confirms it was
  added in that commit), is executable (`-rwxr-xr-x`), and is substantive (91 lines, parses
  `docker-compose.prod.yml`'s live `postgres` service block via PyYAML, recomputes both I1/I2
  invariants).
- Ran directly against the current `docker-compose.prod.yml`: **exit 0**, "invariants OK:
  mem_limit=256MB shared_buffers=64MB work_mem=4MB max_connections=25 worst-case=164MB (64.1% of
  cap)".
- Ran against a scratch copy mutated to the pre-fix pair (`mem_limit: 64m`,
  `shared_buffers=128MB`): **exit 1**, correctly reports both "I1 violated" and "I2 violated" —
  confirming the script is a real, discriminating gate rather than an always-pass stub.
- Both `docker-compose.prod.yml`'s comment and `docs/INFRA_RUNBOOK.md`'s "Postgres memory profile
  correction — Plan 11-08" section now point at this committed script by name and no longer claim
  an enforcement mechanism that doesn't exist.

This finding is now resolved and does not block phase completion.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `docker/postgres-init/01-create-databases-and-roles.sh` | First-boot provisioning of 2 databases + 2 roles, injection-safe | ✓ VERIFIED | Uses `psql -v` `:"var"`/`:'var'` substitution; live adversarial harness run in this pass, 7/7 pass |
| `docker-compose.prod.yml` | `postgres` service with internally consistent memory profile | ✓ VERIFIED | `mem_limit: 256m` / `shared_buffers=64MB`; live-run invariant script confirms consistency and correctly discriminates the pre-fix pair |
| `scripts/verify-postgres-memory-invariant.py` | Committed, re-runnable memory-invariant check | ✓ VERIFIED | Exists, executable, substantive, run directly in this pass against both the current and a scratch pre-fix compose file with correct pass/fail results in both directions |
| `scripts/verify-postgres-init-quoting.sh` | Committed, re-runnable SQL-injection adversarial harness | ✓ VERIFIED | Exists, executable, 222 lines; run directly in this pass with `--case all`, 7/7 pass |
| `docker-compose.nonprod.yml` | `kanban-db` network declared/joined, stale postgres-floor cross-reference corrected | ✓ VERIFIED | Line 140 now reads "then-adopted postgres floor (64m, ...; later corrected to 256m by Plan 11-08)" — historically accurate, not stale |
| `.github/workflows/deploy.yml` | SCP + SSH-based Flyway gate preserved | ✓ VERIFIED | Unchanged since prior pass |
| `src/main/resources/application.properties` | Re-derived HikariCP block | ✓ VERIFIED | Values unchanged since prior pass; see WR-02 note above (documentation-only defect, deferred, out of this pass's scope) |
| `docs/INFRA_RUNBOOK.md` | Dated sections including "Provisioning script hardening — Plan 11-07" and "Postgres memory profile correction — Plan 11-08" | ✓ VERIFIED | Both sections present, cross-reference the correct committed scripts by name |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `shared_buffers=64MB` (postgres `command:`) | `mem_limit: 256m` (postgres cgroup cap) | Compose resource cap vs. engine startup flag, recomputed by `scripts/verify-postgres-memory-invariant.py` | ✓ CONSISTENT | Script run live in this pass, exit 0 |
| `docker/postgres-init/01-create-databases-and-roles.sh`'s `-v prod_pass=...`/`:'prod_pass'` | psql server-side SQL-literal quoting | `psql -v` variable substitution | ✓ WIRED, INJECTION-SAFE | `verify-postgres-init-quoting.sh --case all` run live in this pass, 7/7 pass including hostile apostrophe/injection-payload/malicious-identifier cases |
| `docker-compose.prod.yml`/`docs/INFRA_RUNBOOK.md`'s "mechanically enforced" claim | `scripts/verify-postgres-memory-invariant.py` | Named cross-reference to a committed file | ✓ WIRED | Both files now name the actual committed script; script itself verified functional above |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Memory invariant holds for current adopted pair | `python3 scripts/verify-postgres-memory-invariant.py` | exit 0, "invariants OK: mem_limit=256MB shared_buffers=64MB ... worst-case=164MB (64.1% of cap)" | ✓ PASS |
| Memory invariant script correctly discriminates (fails on pre-fix pair) | Same script run against a scratch copy with `mem_limit: 64m` / `shared_buffers=128MB` | exit 1, "I1 violated" and "I2 violated" | ✓ PASS |
| Provisioning script is injection-safe under hostile credentials | `bash scripts/verify-postgres-init-quoting.sh --case all` (live Docker container) | 7/7 checks passed: both databases exist, no `pwned` database, both roles authenticate with exact (hostile) passwords, both roles correctly refused cross-database connection | ✓ PASS |

No server startup or state mutation against the real project's own database was performed — both
spot-checks either read-only inspect the compose manifest or boot fully disposable, self-cleaning
throwaway Docker containers (`pg-init-quoting-test`, confirmed removed by the harness itself after
the run).

### Requirements Coverage

All of D-01 through D-13 (per 11-CONTEXT.md; no REQUIREMENTS.md exists for this milestone):

| Requirement | Source Plan | Status | Evidence |
|-------------|-------------|--------|----------|
| D-01 | 11-01, 11-02, 11-07 | ✓ SATISFIED | Two-database/two-role isolation, live-proven refusal both directions, now also injection-safe (re-verified live this pass) |
| D-02 | 11-01 | ✓ SATISFIED | `postgres:16` image, matches dev/test |
| D-03 | 11-01, 11-05 | ✓ SATISFIED | No `ports:` entry anywhere; CI reaches via SSH |
| D-04 | 11-02 | ✓ SATISFIED | Fresh start, 8/8 Flyway on both DBs |
| D-05 | 11-02 | ✓ SATISFIED | No downtime constraint, cutover proceeded on already-down production |
| D-06 | 11-01, 11-02 | ✓ SATISFIED | Both environments cut over together |
| D-07 | 11-06 | ✓ SATISFIED | Neon deleted only after live gate + explicit human decision |
| D-08 | 11-03, 11-08 | ✓ SATISFIED | Restart-ladder floor measured, then corrected and re-validated under real concurrent load (24 backends); gap 1 closed |
| D-09 | 11-04 | ✓ SATISFIED | Full HikariCP re-derivation; values confirmed unchanged and correct (WR-02's narrative-accuracy defect is documentation-only, deferred) |
| D-10 | 11-03 | ✓ SATISFIED | App/app-nonprod mem_limits re-measured, cross-references now correctly point at the corrected 256m floor |
| D-11 | 11-01, 11-03, 11-08 | ✓ SATISFIED | Conservative profile, now internally consistent and mechanically re-checkable; gap 1 closed |
| D-12 | 11-06 | ✓ SATISFIED | Backup gap documented, written untested restore procedure, automation explicitly deferred |
| D-13 | 11-05 | ✓ SATISFIED | Gate preserved over SSH, live green run |

No orphaned requirements found.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `src/main/resources/application.properties` | 100-124 | "Decisions" comment narrates a before-state (`minimum-idle=0`/`keepalive-time=0`) that git history does not support (actual prior value: `1`/`120000`) | ⚠️ Warning (WR-02, deferred) | Documentation-accuracy issue in a decision record, pre-existing from plan 11-04, outside this gap-closure session's CR-01/CR-02 scope. Does not affect the shipped config values (confirmed correct above) or any must-have truth |
| `.env.prod.example` | comment above `POSTGRES_SUPERUSER_PASS` | Apostrophe-hazard warning is now stale after the CR-01/11-07 fix (script handles arbitrary characters safely) | ⚠️ Warning (WR-03, blocked — file outside this session's Read/Edit permission scope) | Cosmetic; the underlying provisioning script itself is verified safe regardless of what this comment says |
| `.github/workflows/deploy.yml` | 168-233, 239-285 | No `concurrency:` guard on `flyway-verify`/`flyway-verify-nonprod` (unlike the deploy jobs) | ⚠️ Warning (carried forward, unrelated to this pass's scope) | Two rapid pushes could interleave SCP+read against the same shared VM staging directory; does not block the phase goal |

No debt markers (`TBD`/`FIXME`/`XXX`) found in any file modified by this phase (checked directly
in this pass across all phase-touched files).

### Human Verification Required

None. Both previously-failed truths were closed with committed, re-runnable verification artifacts
that this pass executed directly (not merely read as claims) and confirmed produce correct
pass/fail results in both directions. The new critical finding from the regenerated code review
(false "mechanically enforced" claim) was likewise independently confirmed fixed by running the
now-committed script.

### Gaps Summary

No gaps remain. Both BLOCKER-severity findings from the prior verification pass are closed:

1. **Gap 1 / CR-02 (mem_limit inconsistency)** — closed by plan 11-08. `mem_limit: 256m` /
   `shared_buffers=64MB` is now internally consistent, re-verified live under concurrent load (24
   backends), and mechanically re-checkable via `scripts/verify-postgres-memory-invariant.py`,
   which this verification pass ran directly and confirmed both passes on the adopted pair and
   correctly fails on the pre-fix pair.

2. **Gap 2 / CR-01 (SQL injection-shaped defect)** — closed by plan 11-07. The provisioning
   script now uses `psql -v` server-side quoting; this verification pass ran the committed
   adversarial harness (`scripts/verify-postgres-init-quoting.sh --case all`) live against real
   Docker containers with hostile credential values and confirmed 7/7 checks pass, including no
   rogue database creation and correct cross-database refusal for both roles.

A third issue — the regenerated 11-REVIEW.md's own new critical finding that the "mechanically
enforced" claim referenced a script that didn't exist — was also verified fixed in this pass: the
script (`scripts/verify-postgres-memory-invariant.py`, commit `8cb3deb`) is now genuinely
committed, executable, and independently confirmed to discriminate correctly between the pre-fix
and adopted configurations.

Two low-severity documentation warnings remain open (WR-02: a decision-record narrative in
`application.properties` misstates its own prior-state history; WR-03: a stale apostrophe-hazard
comment in a permission-restricted `.env.prod.example` file), both explicitly deferred by the
review as pre-existing or access-blocked, and neither affects a phase must-have truth or the
shipped configuration's actual behavior.

Everything the phase set out to deliver is genuinely present, substantive, wired, and — for the
two previously-failed truths plus the review's new finding — independently re-executed in this
verification pass rather than trusted from SUMMARY.md or REVIEW.md narration alone.

---

_Verified: 2026-08-26T21:30:00Z_
_Verifier: Claude (gsd-verifier)_
