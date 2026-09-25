# Provisioning script hardening — Plan 11-07 (2026-08-26)

## What was wrong

`docker/postgres-init/01-create-databases-and-roles.sh` — the first-boot script that realizes
D-01's two-database/two-role isolation — assembled its SQL by string concatenation of credential
values: `CREATE ROLE "${PROD_DB_USER}" WITH LOGIN PASSWORD '${PROD_DB_PASS}';`. A password value
containing a single quote closed that SQL literal early and aborted first boot with a syntax
error; a password value shaped like a SQL statement closed the literal early and then *executed*
as the Postgres superuser. Closes 11-REVIEW.md CR-01 and 11-VERIFICATION.md gap 2. The only thing
standing between this repository and that outcome was an unenforced convention about how
passwords happened to be generated — nothing in the script itself checked or prevented it.

## What changed

Credential values now reach the database as `psql -v` variable assignments and are referenced
inside a single-quoted heredoc with `:"var"` (identifier form) for role/database names and
`:'var'` (SQL-literal form) for the two passwords, so quoting happens server-side against the
value as received rather than by shell concatenation. The environment-variable contract with
`docker-compose.prod.yml` did not change — the same six `PROD_DB_*`/`NONPROD_DB_*` variables are
consumed the same way, so no `.env` file and no deploy step is affected by this fix.

## Evidence: the defect reproduced, then closed

Reproduced by materializing the pre-fix script into a fresh directory (`git show HEAD:` at the
commit before this plan) and running `scripts/verify-postgres-init-quoting.sh` against it with
each attack case in turn.

**`breaking` — an apostrophe in the production role's password aborts first boot:**

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
	    CREATE DATABASE "qtest_prod" OWNER "qtest_prod_app";
	    REVOKE CONNECT ON DATABASE "qtest_prod" FROM PUBLIC;
	    GRANT CONNECT ON DATABASE "qtest_prod" TO "qtest_prod_app";
	
	    CREATE ROLE "qtest_np_app" WITH LOGIN PASSWORD 'benign-np-value';
	    CREATE DATABASE "qtest_nonprod" OWNER "qtest_np_app";
	    REVOKE CONNECT ON DATABASE "qtest_nonprod" FROM PUBLIC;
	    GRANT CONNECT ON DATABASE "qtest_nonprod" TO "qtest_np_app";
----- end container logs -----
TOTAL: 0 passed, 1 failed (case=breaking)
```

**`injection` — a payload-shaped password creates an extra database as the superuser (the
reproducible proof this was a live defect, not a theoretical one):**

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
/usr/local/bin/docker-entrypoint.sh: sourcing /docker-entrypoint-initdb.d/01-create-databases-and-roles.sh
CREATE ROLE
CREATE DATABASE
REVOKE
GRANT
CREATE ROLE
CREATE DATABASE
CREATE DATABASE
REVOKE
GRANT
...
----- end container logs -----
TOTAL: 6 passed, 1 failed (case=injection)
```

The extra `CREATE DATABASE` in the log (three total instead of the intended two) is the injected
`pwned` database — the nonprod role's password `x'; CREATE DATABASE pwned; --` closed the SQL
string after `x`, ran `CREATE DATABASE pwned` as the superuser, and commented out the remainder of
the line. The role's actual stored password ended up being the single character `x`, not the
intended string, and the injected database is what the harness's assertion 2 catches.

**`identifier` — a double quote in the nonprod role name aborts first boot:**

```
FAIL: container 'pg-init-quoting-test' exited before becoming ready (case=identifier)
----- container logs (case=identifier) -----
...
/usr/local/bin/docker-entrypoint.sh: sourcing /docker-entrypoint-initdb.d/01-create-databases-and-roles.sh
CREATE ROLE
CREATE DATABASE
REVOKE
GRANT
2026-08-26 17:54:25.637 UTC [71] ERROR:  unrecognized role option "np_app" at character 20
2026-08-26 17:54:25.637 UTC [71] STATEMENT:  CREATE ROLE "qtest"np_app" WITH LOGIN PASSWORD 'benign-np-value';
	    CREATE DATABASE "qtest_nonprod" OWNER "qtest"np_app";
ERROR:  unrecognized role option "np_app"
LINE 1: CREATE ROLE "qtest"np_app" WITH LOGIN PASSWORD 'benign-np-va...
                           ^
----- end container logs -----
TOTAL: 0 passed, 1 failed (case=identifier)
```

**Closed — every hostile value applied simultaneously (`--case all`) against the fixed script:**

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

## Scope: what this fix does NOT cover

The live `postgres-data` volume was provisioned on 2026-08-26 by the pre-fix script (see
"Self-hosted Postgres cutover — Plan 11-02" above). This change does not re-run against that
volume, does not alter any existing role's password, and does not retroactively make that
provisioning run safe — the script only executes once, on an empty data directory, at first
container boot. It takes effect on the *next* provisioning only: a fresh volume, a rebuilt VM, or
a disaster-recovery restore (see "Backups and restore" above — restoring from a dump is exactly
the path that re-runs this script). Separately: rotating an existing role's password on the live
instance is not addressed by this fix and still requires acting directly against the running
container.

## Re-running the check

```bash
scripts/verify-postgres-init-quoting.sh              # defaults: docker/postgres-init, --case all
scripts/verify-postgres-init-quoting.sh --case breaking
scripts/verify-postgres-init-quoting.sh --init-dir /path/to/other/init/dir --case injection
```

Requires a working Docker daemon and pulls `postgres:16` (already in use by production, local dev,
and the Testcontainers test suite). It starts a throwaway, unpublished-port container and removes
it — with its anonymous data volume — on every exit path, success or failure. It is **not** wired
into CI or Gradle; this is a manual, on-demand check by design, not an omission.

## Hardening date

2026-08-26.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._
