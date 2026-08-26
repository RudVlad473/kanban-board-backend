#!/usr/bin/env bash
# Adversarial harness for docker/postgres-init/01-create-databases-and-roles.sh (D-01, plan
# 11-07 / 11-REVIEW.md CR-01). Boots a throwaway postgres:16 container against a given init
# directory with deliberately hostile credential values and asserts what correct, injection-safe
# provisioning looks like. This file is what makes the fix falsifiable -- it must fail against the
# pre-fix script and pass against the fixed one; see the plan's Task 1 for the recorded proof.
#
# Deliberately lives in scripts/, NOT docker/postgres-init/ -- the official postgres image sources
# every file it finds in the mounted init directory as the superuser on first boot, so a harness
# placed there would execute against a real database rather than a throwaway one.
#
# Manually invoked only -- not wired into CI or Gradle by design (see the plan's Task 1 action).
set -eo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
INIT_DIR="${REPO_ROOT}/docker/postgres-init"
CASE_NAME="all"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --init-dir)
      INIT_DIR="$2"
      shift 2
      ;;
    --case)
      CASE_NAME="$2"
      shift 2
      ;;
    *)
      echo "FAIL: unknown argument '$1' (expected --init-dir DIR or --case NAME)" >&2
      exit 2
      ;;
  esac
done

case "$CASE_NAME" in
  breaking|injection|identifier|all) ;;
  *)
    echo "FAIL: unknown case '$CASE_NAME' (expected one of breaking, injection, identifier, all)" >&2
    exit 2
    ;;
esac

if [[ ! -d "$INIT_DIR" ]]; then
  echo "FAIL: init directory '$INIT_DIR' does not exist" >&2
  exit 2
fi

CONTAINER_NAME="pg-init-quoting-test"

# --- Fixed (benign) values -- throwaway names that cannot be confused with real credentials. ---
SUPERUSER="qtest_super"
SUPERUSER_PASS="not-a-secret-local-harness"
MAINT_DB="postgres"
PROD_DB="qtest_prod"
NONPROD_DB="qtest_nonprod"
PROD_USER="qtest_prod_app"
NONPROD_USER="qtest_np_app"
PROD_PASS="benign-prod-value"
NONPROD_PASS="benign-np-value"

# --- Hostile values, one per attack case. Named for their role in the test, not as credentials. ---
# breaking: apostrophe + double quote + backslash + dollar + trailing space -- the current script's
# own comment admits it cannot survive a password shaped like this.
HOSTILE_LITERAL_PUNCTUATION=$'a\'b"c\\d$e '
# injection: closes the SQL string literal early, runs CREATE DATABASE as superuser, comments out
# the rest of the line -- the direct, reproducible proof that arbitrary SQL executes today.
HOSTILE_LITERAL_PAYLOAD="x'; CREATE DATABASE pwned; --"
# identifier: a double quote in the middle of a role name terminates a quoted identifier early.
HOSTILE_IDENTIFIER='qtest"np_app'

case "$CASE_NAME" in
  breaking)
    PROD_PASS="$HOSTILE_LITERAL_PUNCTUATION"
    ;;
  injection)
    NONPROD_PASS="$HOSTILE_LITERAL_PAYLOAD"
    ;;
  identifier)
    NONPROD_USER="$HOSTILE_IDENTIFIER"
    ;;
  all)
    PROD_PASS="$HOSTILE_LITERAL_PUNCTUATION"
    NONPROD_PASS="$HOSTILE_LITERAL_PAYLOAD"
    NONPROD_USER="$HOSTILE_IDENTIFIER"
    ;;
esac

PASS_COUNT=0
FAIL_COUNT=0

pass() {
  echo "PASS: $1"
  PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
  echo "FAIL: $1"
  FAIL_COUNT=$((FAIL_COUNT + 1))
}

# Runs a single-statement psql query inside the container over forced TCP (127.0.0.1), supplying
# the password via docker exec -e so it never touches this shell's own environment or history.
# Forcing TCP matters: the image's default pg_hba.conf trusts local socket connections, so a
# socket-based check would pass without ever exercising password authentication.
run_psql() {
  local user="$1" pw="$2" db="$3" sql="$4"
  docker exec -e PGPASSWORD="$pw" "$CONTAINER_NAME" \
    psql -h 127.0.0.1 -U "$user" -d "$db" -Atc "$sql" 2>&1
}

dump_logs_and_exit() {
  echo "----- container logs (case=$CASE_NAME) -----"
  docker logs "$CONTAINER_NAME" 2>&1 || true
  echo "----- end container logs -----"
  echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed (case=$CASE_NAME)"
  exit 1
}

cleanup() {
  # Force-remove the container and its anonymous data volume on every exit path -- success or
  # failure -- so no container, volume, or state from this run ever survives.
  docker rm -f -v "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Remove any leftover container from a prior crashed run before starting a fresh one.
docker rm -f -v "$CONTAINER_NAME" >/dev/null 2>&1 || true

docker run -d \
  --name "$CONTAINER_NAME" \
  -v "${INIT_DIR}:/docker-entrypoint-initdb.d:ro" \
  -e POSTGRES_USER="$SUPERUSER" \
  -e POSTGRES_PASSWORD="$SUPERUSER_PASS" \
  -e POSTGRES_DB="$MAINT_DB" \
  -e PROD_DB_NAME="$PROD_DB" \
  -e PROD_DB_USER="$PROD_USER" \
  -e PROD_DB_PASS="$PROD_PASS" \
  -e NONPROD_DB_NAME="$NONPROD_DB" \
  -e NONPROD_DB_USER="$NONPROD_USER" \
  -e NONPROD_DB_PASS="$NONPROD_PASS" \
  postgres:16 >/dev/null

# Readiness: poll up to 60s. Treat readiness as pg_isready succeeding over forced TCP. Abort early
# if the container has already exited -- a container that died during init would otherwise waste
# the full timeout. A readiness timeout or an exited container is a legitimate FAIL, not a harness
# error: it is exactly what the breaking and identifier cases are expected to produce against the
# pre-fix script.
READY=0
for _ in $(seq 1 60); do
  STATE="$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null || echo "false")"
  if [[ "$STATE" != "true" ]]; then
    fail "container '$CONTAINER_NAME' exited before becoming ready (case=$CASE_NAME)"
    dump_logs_and_exit
  fi
  if docker exec "$CONTAINER_NAME" pg_isready -h 127.0.0.1 -U "$SUPERUSER" -d "$MAINT_DB" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 1
done

if [[ "$READY" -ne 1 ]]; then
  fail "container did not become ready within 60s (case=$CASE_NAME)"
  dump_logs_and_exit
fi
pass "container became ready (case=$CASE_NAME)"

# --- Assertions, once ready. ---

# 1. Both databases exist.
if DB_LIST="$(run_psql "$SUPERUSER" "$SUPERUSER_PASS" "$MAINT_DB" "SELECT datname FROM pg_database")"; then
  if echo "$DB_LIST" | grep -qx "$PROD_DB" && echo "$DB_LIST" | grep -qx "$NONPROD_DB"; then
    pass "both '$PROD_DB' and '$NONPROD_DB' exist"
  else
    fail "expected databases missing from pg_database: $DB_LIST"
  fi
else
  fail "could not query pg_database as superuser: $DB_LIST"
  DB_LIST=""
fi

# 2. No 'pwned' database exists -- proves injected SQL did not run.
if echo "$DB_LIST" | grep -qx "pwned"; then
  fail "database 'pwned' exists -- injected SQL executed as the Postgres superuser"
else
  pass "no 'pwned' database exists"
fi

# 3. Each role authenticates over TCP with its own exact password for this case.
check_auth() {
  local user="$1" pw="$2" db="$3" label="$4"
  local out
  if out="$(run_psql "$user" "$pw" "$db" "SELECT 1")" && [[ "$out" == "1" ]]; then
    pass "$label authenticates over TCP with its exact password"
  else
    fail "$label failed to authenticate over TCP with its exact password: $out"
  fi
}
check_auth "$PROD_USER" "$PROD_PASS" "$PROD_DB" "prod role ($PROD_USER)"
check_auth "$NONPROD_USER" "$NONPROD_PASS" "$NONPROD_DB" "nonprod role ($NONPROD_USER)"

# 4. Cross-database connection is refused in both directions -- re-proves D-01's isolation across
# the identifier-quoting rewrite rather than assuming the REVOKE CONNECT lines still bind.
check_cross_refused() {
  local user="$1" pw="$2" other_db="$3" label="$4"
  local out
  if out="$(run_psql "$user" "$pw" "$other_db" "SELECT 1")"; then
    fail "$label unexpectedly connected to '$other_db' -- cross-database isolation broken"
  else
    pass "$label correctly refused connection to '$other_db'"
  fi
}
check_cross_refused "$PROD_USER" "$PROD_PASS" "$NONPROD_DB" "prod role ($PROD_USER)"
check_cross_refused "$NONPROD_USER" "$NONPROD_PASS" "$PROD_DB" "nonprod role ($NONPROD_USER)"

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  dump_logs_and_exit
fi

echo "TOTAL: $PASS_COUNT passed, $FAIL_COUNT failed (case=$CASE_NAME)"
exit 0
