-- D-08.2 row-count parity check: run this identical query against the Compose Postgres
-- (immediately after the app stops, per D-05) and again against the in-cluster Postgres once
-- the D-03 pg_dump/pg_restore migration completes, then diff the two outputs -- an exact match
-- proves no row was dropped or duplicated in transit.
--
-- Usage: psql -XAt -F'|' -f scripts/cutover/row-counts.sql
--   -X   ignore ~/.psqlrc (deterministic output regardless of the invoking user's local config)
--   -A   unaligned output (no padding/borders -- stable for a byte-for-byte diff)
--   -t   tuples only (no column headers/row-count footer)
--   -F'|' field separator, matching this repo's own verify-postgres-init-quoting.sh convention
--
-- ORDER BY t makes the six-line output deterministic regardless of query-plan choice, which is
-- what makes a plain `diff` between the before/after runs meaningful rather than needing to be
-- sorted first.
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
