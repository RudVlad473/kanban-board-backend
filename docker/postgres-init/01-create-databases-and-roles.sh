#!/usr/bin/env bash
# Provisions the two databases and two least-privilege roles this shared Postgres instance serves
# (production + nonprod, D-01). Mounted read-only at /docker-entrypoint-initdb.d -- the official
# postgres:16 image *sources* every non-executable .sh file it finds there into its own entrypoint
# shell (github.com/docker-library/postgres), rather than executing it as a separate process.
#
# set -eo pipefail, deliberately WITHOUT -u: a leaked `set -u` from a sourced script can abort the
# image's own entrypoint later on an unrelated, legitimately-optional variable it references after
# this script returns. Explicit `: "${VAR:?...}"` guards below replace -u's protection for the six
# variables this script actually requires, and fail loudly with the missing variable's name instead
# of a bare "unbound variable" -- a materially better first-boot diagnostic.
set -eo pipefail

: "${PROD_DB_NAME:?PROD_DB_NAME must be set}"
: "${PROD_DB_USER:?PROD_DB_USER must be set}"
: "${PROD_DB_PASS:?PROD_DB_PASS must be set}"
: "${NONPROD_DB_NAME:?NONPROD_DB_NAME must be set}"
: "${NONPROD_DB_USER:?NONPROD_DB_USER must be set}"
: "${NONPROD_DB_PASS:?NONPROD_DB_PASS must be set}"

# Decisions ----------------------------------------------------------------------------------
# 2026-08-26: the two REVOKE lines below are load-bearing, not defensive boilerplate. PostgreSQL
# grants CONNECT (and TEMPORARY) on every database to the implicit PUBLIC pseudo-role by default
# (postgresql.org/docs/17/ddl-priv.html) -- so CREATE ROLE + CREATE DATABASE ... OWNER alone does
# NOT stop the nonprod role from connecting to the production database, or vice versa. Without the
# explicit REVOKE, D-01's isolation requirement fails silently: every database/role exists, every
# app boots fine, and the only symptom is that isolation was never actually in effect.
#
# This script runs exactly once, only when /var/lib/postgresql/data is empty on first container
# boot (the official image's documented behavior, github.com/docker-library/postgres). A failed or
# partial first run leaves a non-empty data directory, and every later `docker compose up` silently
# skips this script again -- editing and retrying does NOT reprovision anything. The only correct
# recovery from a failed first boot is `docker compose down -v` (removing the postgres-data named
# volume) followed by a fresh `up`, not a patch-and-retry loop against the same volume.
#
# Falsifiable: if a future postgres-init run leaves either role able to connect to the other's
# database, the two REVOKE lines below either did not run or were reverted -- check `\l` in psql
# for a per-database ACL override (an unmodified `=Tc/<owner>` entry means the revoke never ran).
#
# 2026-08-26: credential values below reach the query tool as `-v` variable assignments and are
# referenced with `:"var"` (identifier form) or `:'var'` (SQL-literal form) inside the heredoc --
# quoting happens server-side against the value as received, so no convention about how a
# password happens to be generated is load-bearing any more (closes 11-REVIEW.md CR-01 /
# 11-VERIFICATION.md gap 2). The heredoc delimiter is single-quoted deliberately and must stay
# that way: an unquoted delimiter lets the shell expand the body again, silently reintroducing
# string-built SQL. Falsifiable: scripts/verify-postgres-init-quoting.sh boots a throwaway
# container with hostile credential values (an apostrophe, a SQL-injection-shaped payload, a
# double-quoted identifier) and fails if this regresses.
# ---------------------------------------------------------------------------------------------

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v prod_user="$PROD_DB_USER" \
    -v prod_pass="$PROD_DB_PASS" \
    -v prod_db="$PROD_DB_NAME" \
    -v nonprod_user="$NONPROD_DB_USER" \
    -v nonprod_pass="$NONPROD_DB_PASS" \
    -v nonprod_db="$NONPROD_DB_NAME" <<-'EOSQL'
    CREATE ROLE :"prod_user" WITH LOGIN PASSWORD :'prod_pass';
    CREATE DATABASE :"prod_db" OWNER :"prod_user";
    REVOKE CONNECT ON DATABASE :"prod_db" FROM PUBLIC;
    GRANT CONNECT ON DATABASE :"prod_db" TO :"prod_user";

    CREATE ROLE :"nonprod_user" WITH LOGIN PASSWORD :'nonprod_pass';
    CREATE DATABASE :"nonprod_db" OWNER :"nonprod_user";
    REVOKE CONNECT ON DATABASE :"nonprod_db" FROM PUBLIC;
    GRANT CONNECT ON DATABASE :"nonprod_db" TO :"nonprod_user";
EOSQL
