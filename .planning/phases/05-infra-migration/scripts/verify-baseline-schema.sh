#!/usr/bin/env bash
set -euo pipefail

# Phase 5, Plan 01, Task 3 -- CORRECTED VERIFICATION.
#
# 05-01-PLAN.md's Task 3 was written to verify a hand-generated
# docs/plans/backend-modernization/01-baseline-schema.sql, produced by a new Hibernate
# schema-export Gradle task, run alongside the three legacy DDL bridge scripts
# (02-optimistic-locking-ddl.sql, 03-activity-log-ddl.sql, 04-password-hash-not-null-ddl.sql).
# That artifact does not exist and this script does not create or check for it. Between this
# plan's research (2026-08-04) and its execution (2026-08-12), Phase 04.1/04.2 replaced the
# manual-bridge-script schema-creation path entirely with Flyway migrations
# (src/main/resources/db/migration/V1__init.sql .. V7__*.sql) and set
# spring.jpa.hibernate.ddl-auto=validate unconditionally (not "unset" as the plan's own must-haves
# assumed -- verified live against this repo's application.properties before writing this script).
# A second, hand-generated baseline artifact duplicating what Flyway's own migration-history table
# already guarantees would be a second, competing source of truth -- exactly what this codebase's
# own conventions (see application.properties' "Division of labor" comment above ddl-auto) reject.
#
# What this script actually proves, using this repo's own docker-compose.yml (the same local
# stack every developer already runs) against a genuinely fresh, empty Postgres volume:
#   1. The app boots successfully and Flyway creates the full application schema from empty.
#   2. All 7 migrations (V1-V7) apply successfully, in order.
#   3. A second boot against the SAME already-migrated database is safe (Flyway idempotency --
#      it validates the existing history and applies nothing new) and the app still boots clean.
#   4. GET /api/actuator/health returns 200 with status UP on both boots.
#   5. Expected tables exist after the run: users, boards, columns, tasks, subtasks, activity_log.
#
# Requires Docker. Uses a dedicated compose project name and a throwaway DB name/credentials so it
# does not collide with a developer's own `docker compose up`. Cleans up every container, network,
# volume and built image it creates, even on failure -- do not run this concurrently with another
# `docker compose` invocation against the SAME project name, since docker-compose.yml's app/
# postgres/redpanda services still bind fixed host ports (8080/5433/9092/8081).

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

PROJECT_NAME="verify-baseline-schema"
DB_NAME_V="verify_baseline_schema"
DB_USER_V="verify_baseline_schema"
DB_PASS_V="verify_baseline_schema_pw"
HEALTH_URL="http://localhost:8080/api/actuator/health"

cleanup() {
  echo "--- Cleaning up ($PROJECT_NAME) ---"
  DB_NAME="$DB_NAME_V" DB_USER="$DB_USER_V" DB_PASS="$DB_PASS_V" \
    docker compose -p "$PROJECT_NAME" down -v --remove-orphans >/dev/null 2>&1 || true
  docker rmi "${PROJECT_NAME}-app:latest" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for_health() {
  local attempt
  for attempt in $(seq 1 30); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$HEALTH_URL" 2>/dev/null || true)" = "200" ]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

echo "--- Run 1: fresh empty database -- proving Flyway creates the full schema ---"
DB_NAME="$DB_NAME_V" DB_USER="$DB_USER_V" DB_PASS="$DB_PASS_V" \
  docker compose -p "$PROJECT_NAME" up -d --build postgres redpanda app

if ! wait_for_health; then
  echo "FAIL: app did not report a healthy /api/actuator/health on run 1"
  docker logs "${PROJECT_NAME}-app-1" || true
  exit 1
fi
STATUS_1="$(curl -s "$HEALTH_URL")"
echo "Run 1 health: $STATUS_1"
case "$STATUS_1" in
  *'"status":"UP"'*) ;;
  *) echo "FAIL: run 1 health status was not UP: $STATUS_1"; exit 1 ;;
esac

echo "--- Asserting expected tables exist ---"
TABLES="$(docker exec "${PROJECT_NAME}-postgres-1" psql -U "$DB_USER_V" -d "$DB_NAME_V" -tAc \
  "SELECT string_agg(tablename, ',' ORDER BY tablename) FROM pg_tables WHERE schemaname = 'public'")"
echo "Tables: $TABLES"
for t in users boards columns tasks subtasks activity_log; do
  case ",$TABLES," in
    *",$t,"*) ;;
    *) echo "FAIL: expected table '$t' missing from: $TABLES"; exit 1 ;;
  esac
done

echo "--- Asserting all 7 Flyway migrations applied successfully ---"
MIGRATION_COUNT="$(docker exec "${PROJECT_NAME}-postgres-1" psql -U "$DB_USER_V" -d "$DB_NAME_V" -tAc \
  "SELECT count(*) FROM flyway_schema_history WHERE success = true")"
echo "Successful migrations: $MIGRATION_COUNT"
if [ "$MIGRATION_COUNT" -lt 7 ]; then
  echo "FAIL: expected at least 7 successful Flyway migrations, found $MIGRATION_COUNT"
  exit 1
fi

# Carried over from the original (pre-Flyway) Task 3 acceptance criteria -- the two specific
# schema-shape facts those checks named still matter, they are just proven against Flyway's own
# migration output (V2, V4) rather than against a hand-run bridge script.
echo "--- Asserting tasks.version exists (V2 migration) ---"
VERSION_COL="$(docker exec "${PROJECT_NAME}-postgres-1" psql -U "$DB_USER_V" -d "$DB_NAME_V" -tAc \
  "SELECT column_name FROM information_schema.columns WHERE table_name='tasks' AND column_name='version'")"
if [ "$VERSION_COL" != "version" ]; then
  echo "FAIL: tasks.version column missing"
  exit 1
fi

echo "--- Asserting users.password_hash is NOT NULL (V4 migration) ---"
PASSWORD_HASH_NULLABLE="$(docker exec "${PROJECT_NAME}-postgres-1" psql -U "$DB_USER_V" -d "$DB_NAME_V" -tAc \
  "SELECT is_nullable FROM information_schema.columns WHERE table_name='users' AND column_name='password_hash'")"
if [ "$PASSWORD_HASH_NULLABLE" != "NO" ]; then
  echo "FAIL: users.password_hash is nullable (expected NOT NULL): $PASSWORD_HASH_NULLABLE"
  exit 1
fi

echo "--- Run 2: restart against the SAME already-migrated database -- proving idempotency ---"
# Line count captured BEFORE the restart, not a timestamp: `docker logs --since` was observed
# (this script's own dry run) to still return run-1's pre-restart lines on this Docker Desktop
# host despite an ISO-8601 UTC cutoff strictly after them -- an unreliable host/container clock
# assumption this check must not depend on. Counting lines and taking only what was appended
# after the restart is timestamp-independent and immune to that clock skew.
LINES_BEFORE_RESTART="$(docker logs "${PROJECT_NAME}-app-1" 2>&1 | wc -l)"
docker compose -p "$PROJECT_NAME" restart app >/dev/null

if ! wait_for_health; then
  echo "FAIL: app did not report a healthy /api/actuator/health on run 2 (idempotency check)"
  docker logs "${PROJECT_NAME}-app-1" || true
  exit 1
fi
STATUS_2="$(curl -s "$HEALTH_URL")"
echo "Run 2 health: $STATUS_2"
case "$STATUS_2" in
  *'"status":"UP"'*) ;;
  *) echo "FAIL: run 2 health status was not UP: $STATUS_2"; exit 1 ;;
esac

REAPPLIED="$(docker logs "${PROJECT_NAME}-app-1" 2>&1 | tail -n "+$((LINES_BEFORE_RESTART + 1))" \
  | grep -c "Creating Schema History table" || true)"
if [ "$REAPPLIED" != "0" ]; then
  echo "FAIL: run 2 recreated the schema history table -- Flyway did not treat the database as already migrated"
  exit 1
fi

echo "--- PASSED: Flyway migrations (V1-V7) build the full schema from a genuinely empty database, are safe to re-run, and the app reaches health UP on both boots. ---"
