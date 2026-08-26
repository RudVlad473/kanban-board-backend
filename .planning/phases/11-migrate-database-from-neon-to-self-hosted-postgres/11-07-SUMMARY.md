---
phase: 11-migrate-database-from-neon-to-self-hosted-postgres
plan: 07
subsystem: infra
tags: [postgres, provisioning, security, sql-injection, gap-closure]
status: complete
dependency-graph:
  requires: []
  provides:
    - "scripts/verify-postgres-init-quoting.sh (adversarial provisioning harness)"
    - "docker/postgres-init/01-create-databases-and-roles.sh (psql-variable-quoted rewrite)"
  affects:
    - "11-VERIFICATION.md gap 2 / 11-REVIEW.md CR-01 (closed)"
tech-stack:
  added: []
  patterns:
    - "psql -v variable assignments + :\"var\"/:'var' server-side quoting instead of shell string concatenation into SQL"
key-files:
  created:
    - scripts/verify-postgres-init-quoting.sh
  modified:
    - docker/postgres-init/01-create-databases-and-roles.sh
    - docs/INFRA_RUNBOOK.md
decisions:
  - "Chose psql -v / :\"var\" / :'var' server-side quoting (design alternative A) over shell-escaping (B, narrows but doesn't remove the concatenation defect) or input validation (C, hardens the convention rather than removing the dependency on it)."
  - "Harness lives in scripts/, not docker/postgres-init/, since the postgres image sources every file in the mounted init directory as superuser on first boot."
  - "Harness is manually invoked only -- not wired into CI or Gradle, a deliberate scope boundary (this plan closes a defect, it does not extend the CI surface)."
metrics:
  duration: "45min"
  completed: 2026-08-26
actuals:
  tokens: 4970
  tasks: 3
  commits: 3
---

# Phase 11 Plan 07: Provisioning script SQL-injection hardening Summary

Closed 11-REVIEW.md CR-01 / 11-VERIFICATION.md gap 2: the first-boot Postgres provisioning script
that realizes D-01's two-database/two-role isolation now quotes every credential value through
psql's own server-side substitution instead of splicing it into a SQL heredoc by shell string
concatenation, proven by a purpose-built adversarial harness that fails against the pre-fix script
and passes against the fixed one.

## What Was Built

**Task 1 — `scripts/verify-postgres-init-quoting.sh`.** A self-contained bash harness that boots a
throwaway `postgres:16` container against a given `--init-dir` (default
`docker/postgres-init`) with deliberately hostile credential values and asserts what safe
provisioning looks like: both databases exist, no injected `pwned` database exists, each role
authenticates over forced TCP with its exact hostile password, and cross-database connection is
refused in both directions. Supports `--case breaking|injection|identifier|all` (default `all`).
Force-removes its container (`pg-init-quoting-test`) and anonymous data volume on every exit path
via a trap. Deliberately lives outside `docker/postgres-init/` -- the official postgres image
sources every file it finds in that mounted directory as the superuser on first boot, so a harness
placed there would run against a real database.

Ran the harness against a `git show`-materialized copy of the pre-fix script for all three attack
cases; all three failed as required (verbatim output below), proving the harness can fail before
it is trusted to prove the fix.

**Task 2 — rewrote `docker/postgres-init/01-create-databases-and-roles.sh`'s single `psql`
invocation.** Six credential values (`PROD_DB_USER`/`PROD_DB_PASS`/`PROD_DB_NAME`/
`NONPROD_DB_USER`/`NONPROD_DB_PASS`/`NONPROD_DB_NAME`) now reach `psql` as `-v` variable
assignments and are referenced inside a single-quoted heredoc (`<<-'EOSQL'`) using `:"var"`
(identifier form, for role/database names) and `:'var'` (SQL-literal form, for the two passwords).
Quoting happens server-side against the value as received, so no convention about how a password
is generated is load-bearing any more. Everything else in the file is unchanged: the shebang, the
`set -eo pipefail` comment explaining the deliberate absence of `-u`, all six `: "${VAR:?...}"`
guards, the load-bearing `REVOKE CONNECT ... FROM PUBLIC` / `GRANT CONNECT` statement pairs, and
the file's dated `Decisions` comment block (extended, not replaced, with a new 2026-08-26 entry
describing the fix and naming the harness as its falsifiable check). `git diff` confirms the
change touches only the `psql` invocation, the heredoc, and comment lines.

Verified with `scripts/verify-postgres-init-quoting.sh --case all` against the fixed script: all 7
assertions passed, including both hostile passwords authenticating correctly and D-01's
cross-database isolation still holding after the identifier-quoting rewrite.

**Task 3 — `docs/INFRA_RUNBOOK.md`.** Appended a dated `## Provisioning script hardening — Plan
11-07 (2026-08-26)` section with the six required subsections (`What was wrong`, `What changed`,
`Evidence: the defect reproduced, then closed`, `Scope: what this fix does NOT cover`,
`Re-running the check`, `Hardening date`), pasting the three pre-fix failing runs and the
post-fix passing run verbatim. Added a one-line cross-reference from the `## Database —
self-hosted PostgreSQL` current-state section pointing at the new hardening record. No
credential, role password, or superuser value appears anywhere in the new text.

## Evidence: the defect reproduced, then closed (captured verbatim)

**`breaking` (apostrophe in the production role's password) — pre-fix script, FAILS:**

```
FAIL: container 'pg-init-quoting-test' exited before becoming ready (case=breaking)
----- container logs (case=breaking) -----
...
/usr/local/bin/docker-entrypoint.sh: sourcing /docker-entrypoint-initdb.d/01-create-databases-and-roles.sh
ERROR:  unrecognized role option "b"
LINE 1: ...EATE ROLE "qtest_prod_app" WITH LOGIN PASSWORD 'a'b"c\d$e ';
                                                             ^
2026-08-26 17:54:17.963 UTC [83] ERROR:  unrecognized role option "b" at character 53
2026-08-26 17:54:17.963 UTC [83] STATEMENT:  CREATE ROLE "qtest_prod_app" WITH LOGIN PASSWORD 'a'b"c\d$e ';
----- end container logs -----
TOTAL: 0 passed, 1 failed (case=breaking)
```

**`injection` (SQL-injection-shaped password) — pre-fix script, FAILS (the reproducible proof
this was a live defect):**

```
PASS: container became ready (case=injection)
PASS: both 'qtest_prod' and 'qtest_nonprod' exist
FAIL: database 'pwned' exists -- injected SQL executed as the Postgres superuser
PASS: prod role (qtest_prod_app) authenticates over TCP with its exact password
PASS: nonprod role (qtest_np_app) authenticates over TCP with its exact password
PASS: prod role (qtest_prod_app) correctly refused connection to 'qtest_nonprod'
PASS: nonprod role (qtest_np_app) correctly refused connection to 'qtest_prod'
----- container logs (case=injection) -----
...
CREATE ROLE
CREATE DATABASE
REVOKE
GRANT
CREATE ROLE
CREATE DATABASE
CREATE DATABASE
REVOKE
GRANT
----- end container logs -----
TOTAL: 6 passed, 1 failed (case=injection)
```

**`identifier` (double quote in the nonprod role name) — pre-fix script, FAILS:**

```
FAIL: container 'pg-init-quoting-test' exited before becoming ready (case=identifier)
----- container logs (case=identifier) -----
...
CREATE ROLE
CREATE DATABASE
REVOKE
GRANT
2026-08-26 17:54:25.637 UTC [71] ERROR:  unrecognized role option "np_app" at character 20
2026-08-26 17:54:25.637 UTC [71] STATEMENT:  CREATE ROLE "qtest"np_app" WITH LOGIN PASSWORD 'benign-np-value';
----- end container logs -----
TOTAL: 0 passed, 1 failed (case=identifier)
```

**Closed — every hostile value applied at once (`--case all`) against the fixed script, PASSES:**

```
PASS: container became ready (case=all)
PASS: both 'qtest_prod' and 'qtest_nonprod' exist
PASS: no 'pwned' database exists
PASS: prod role (qtest_prod_app) authenticates over TCP with its exact password
PASS: nonprod role (qtest"np_app) authenticates over TCP with its exact password
PASS: prod role (qtest_prod_app) correctly refused connection to 'qtest_nonprod'
PASS: nonprod role (qtest"np_app) correctly refused connection to 'qtest_prod'
TOTAL: 7 passed, 0 failed (case=all)
```

## Verification Performed

1. `scripts/verify-postgres-init-quoting.sh --case all` exits 0 against the committed, fixed
   init script -- confirmed (7/7 assertions passed).
2. The harness exits non-zero for all three attack cases (`breaking`, `injection`, `identifier`)
   against a `git show`-materialized copy of the pre-fix script, and the `injection` run's output
   names the injected `pwned` database -- confirmed (re-ran independently against the commit
   immediately preceding Task 2, not just during Task 1's own build).
3. `docker/postgres-init/` contains exactly one file -- confirmed.
4. The rewritten script keeps all six variable guards, one `ON_ERROR_STOP=1` psql call, both
   REVOKE and both GRANT statements, and a heredoc body with no shell expansion character --
   confirmed via the plan's structural Python check.
5. `docs/INFRA_RUNBOOK.md` carries the dated hardening section including the explicit scope
   caveat, cross-referenced from the current-state Database section -- confirmed.
6. `./gradlew spotlessCheck` and `./gradlew test` both pass (exit 0) -- confirmed; this plan
   touches no Java source, and both runs completed clean.
7. No container, volume, or temporary directory left behind by any harness run, across every
   invocation performed during this plan (Task 1's three red runs, Task 2's green run, this
   verification's re-run of all four cases) -- confirmed via `docker ps -a` / `docker volume ls`
   checks after each.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking issue] `mktemp -d`'s default 0700 permissions blocked the container's
non-root process from reading the mounted init directory**
- **Found during:** Task 1's red-proof step (running the harness against a `git show`-materialized
  copy of the pre-fix script).
- **Issue:** `mktemp -d` on this machine creates directories mode `0700` (owner-only). The
  `postgres:16` image's entrypoint process runs as a container-internal, non-host `postgres` user,
  which is treated as "other" for bind-mount permission purposes on this Docker/WSL2 setup --
  `pg_isready`/container startup failed with `ls: cannot open directory
  '/docker-entrypoint-initdb.d/': Permission denied` before any SQL ran at all, which is not one of
  the three intended failure modes and would have produced a false-negative "proof."
- **Fix:** `chmod 755` the temporary directory immediately after `mktemp -d`, both when manually
  reproducing the red proof and when running the plan's own `<verify>` automated block (which also
  creates its temp dir via bare `mktemp -d`). The harness script itself (`scripts/
  verify-postgres-init-quoting.sh`) does not chmod its caller-supplied `--init-dir` -- that
  directory's permissions are the caller's responsibility, and chmod'ing an arbitrary
  caller-supplied path (which could be the real production directory) would be inappropriate for
  the harness to do unconditionally.
- **Files modified:** None (adjustment to my own verification invocation only, not to any
  committed file).
- **Commit:** N/A (no code change; noted here for anyone re-running this plan's `<verify>` blocks
  on a similarly-configured machine).

No other deviations. Both design-alternatives-B and -C from the plan's own analysis were correctly
rejected in favor of A (psql server-side quoting) exactly as the plan specified; no additional
architectural decisions were needed.

## Known Stubs

None.

## Threat Flags

None. All threat register entries (T-11-37 through T-11-42) in the plan's `<threat_model>` were
mitigated exactly as planned: Task 2's rewrite closes T-11-37/T-11-38/T-11-39, Task 1's harness
placement and file-count assertion closes T-11-40, the harness's non-credential-shaped literals
and gitleaks scan (no allowlist entry was needed -- the scan passed clean on all three commits)
close T-11-41, and Task 3's `Scope` subsection closes T-11-42.

## Self-Check: PASSED

- `scripts/verify-postgres-init-quoting.sh` exists, is executable, passes `bash -n` -- confirmed.
- `docker/postgres-init/01-create-databases-and-roles.sh` diff matches the described rewrite --
  confirmed via `git diff 87759bc..HEAD`.
- `docs/INFRA_RUNBOOK.md` contains the new section and cross-reference -- confirmed.
- Commit hashes `b47b14c`, `8bc9ea2`, `7ba2ea3` all present in `git log --oneline` -- confirmed.
- No leftover `pg-init-quoting-test` container or volume -- confirmed.
