-- V3__add_activity_log.sql
-- v1.1 Phase 3's activity log, previously applied by hand via
-- docs/plans/backend-modernization/03-activity-log-ddl.sql.

CREATE TABLE activity_log (
    id varchar(255) PRIMARY KEY,
    board_id varchar(255) NOT NULL,
    user_id varchar(255) NOT NULL,
    action varchar(255) NOT NULL,
    detail varchar(2000) NOT NULL,
    event_id uuid NOT NULL CONSTRAINT uk_activity_log_event_id UNIQUE,
    created_at timestamp(6) with time zone NOT NULL
);

-- Covers the paginated per-board read this table is built to serve so that
-- query is an index scan rather than a sort of the whole board's history as
-- the log grows unbounded (there is no retention policy on this feed).
CREATE INDEX idx_activity_log_board_created_id
    ON activity_log (board_id, created_at DESC, id DESC);
