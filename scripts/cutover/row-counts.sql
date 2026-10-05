-- Row-count parity check: run it on the Compose Postgres (right after the app stops) and the
-- in-cluster Postgres (after pg_dump/pg_restore), then diff; equal output means no row was lost or duplicated.
--
-- Usage: psql -XAt -F'|' -f scripts/cutover/row-counts.sql
--   -X   ignore ~/.psqlrc (deterministic output regardless of the invoking user's local config)
--   -A   unaligned output (no padding/borders, stable for a byte-for-byte diff)
--   -t   tuples only (no column headers/row-count footer)
--   -F'|' field separator, matching verify-postgres-init-quoting.sh's convention
--
-- Decisions:
-- ORDER BY t makes the six-line output deterministic regardless of query plan, so a plain `diff` of the
-- before/after runs is meaningful without sorting first.
SELECT 'users' AS t, count(*) FROM users
UNION ALL
SELECT 'boards' AS t, count(*) FROM boards
UNION ALL
SELECT 'columns' AS t, count(*) FROM columns
UNION ALL
SELECT 'tasks' AS t, count(*) FROM tasks
UNION ALL
SELECT 'subtasks' AS t, count(*) FROM subtasks
UNION ALL
SELECT 'activity_log' AS t, count(*) FROM activity_log
ORDER BY t;
