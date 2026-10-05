#!/usr/bin/env bash
# Create the least-privilege `monitoring` role this shared Postgres instance's exporter needs
# (docs/INFRA_RUNBOOK.md "The monitoring role, and why it is not an init script").
#
# Decisions:
# Same mount/execution contract as 01-create-databases-and-roles.sh: sourced by the official postgres image's
# entrypoint from /docker-entrypoint-initdb.d, runs exactly once, only against an empty PGDATA on first boot.
# This IS an init script here, unlike the Compose-era role, which the runbook section above documents as
# created live via docker exec against an already-populated volume: this StatefulSet's PVC is empty on first
# boot, so there is no populated-volume ordering problem to route around. The earlier live-docker-exec
# workaround existed only because the Compose volume was already populated when the role was introduced; on a
# fresh k3s PVC the role is created the normal way, here, alongside 01's own two roles, before any restore.
# set -eo pipefail, deliberately WITHOUT -u, as in 01's header: a leaked `set -u` from a sourced script can abort
# the image's own entrypoint later on an unrelated, legitimately-optional variable. Explicit `: "${VAR:?...}"`
# guards below replace -u's protection for the three variables this script requires.
set -eo pipefail

: "${MONITORING_DB_PASS:?MONITORING_DB_PASS must be set}"
: "${PROD_DB_NAME:?PROD_DB_NAME must be set}"
: "${NONPROD_DB_NAME:?NONPROD_DB_NAME must be set}"

# Same quoting convention as 01-create-databases-and-roles.sh: credential values reach the query tool as `-v`
# variable assignments and are referenced with `:"var"` (identifier form) or `:'var'` (SQL-literal form) inside
# the heredoc, so quoting happens server-side against the value as received.
#
# The heredoc delimiter is single-quoted deliberately and must stay that way: an unquoted delimiter lets the
# shell expand the body again, silently reintroducing string-built SQL. Falsifiable:
# scripts/verify-postgres-init-quoting.sh boots a throwaway container with hostile credential values against this
# directory and fails if this regresses.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v monitoring_pass="$MONITORING_DB_PASS" \
    -v prod_db="$PROD_DB_NAME" \
    -v nonprod_db="$NONPROD_DB_NAME" <<-'EOSQL'
    CREATE ROLE monitoring WITH LOGIN PASSWORD :'monitoring_pass';
    GRANT CONNECT ON DATABASE :"prod_db" TO monitoring;
    GRANT CONNECT ON DATABASE :"nonprod_db" TO monitoring;
    GRANT pg_monitor TO monitoring;
EOSQL
