-- SUPERSEDED: historical reference only. Do NOT run by hand.
--
-- Folded into Flyway as src/main/resources/db/migration/V3__add_activity_log.sql, now the sole owner of
-- this schema change. The body below is kept verbatim as provenance for that migration's content.
--
-- Decisions:
-- This was a one-off manual DDL bridge creating the activity log. The Postgres profile has ddl-auto
-- unset, so Hibernate would NOT create `activity_log`, a brand-new table with no automatic path to
-- production; the script created it by hand via psql against the real database, immediately before
-- merging the PR. The order was one-way: the main branch auto-deployed on every push
-- (.github/workflows/deploy.yml), so with the table missing every consumed Kafka event would exhaust its
-- retries and land on the dead-letter topic instead of being persisted, a total feature outage that
-- superficially looks like "the dead-letter path works" (it does; the feature beneath it does not). The
-- H2 test profile of that time was unaffected, since tests built their schema from the entities.
-- Safe to re-run: every statement uses IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS activity_log (
    id varchar(255) PRIMARY KEY,
    board_id varchar(255) NOT NULL,
    user_id varchar(255) NOT NULL,
    action varchar(255) NOT NULL,
    detail varchar(2000) NOT NULL,
    event_id uuid NOT NULL CONSTRAINT uk_activity_log_event_id UNIQUE,
    created_at timestamp(6) with time zone NOT NULL
);

-- Serves the paginated per-board read as an index scan rather than a sort of the board's whole history,
-- which grows unbounded (the feed has no retention policy).
CREATE INDEX IF NOT EXISTS idx_activity_log_board_created_id
    ON activity_log (board_id, created_at DESC, id DESC);
