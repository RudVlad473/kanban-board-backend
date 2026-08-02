-- Phase 3 (v1.1) — Activity Log: one-off manual DDL bridge
--
-- WHAT THIS IS
-- The real Postgres profile (src/main/resources/application.properties) has
-- ddl-auto unset, so Hibernate will NOT create the new `activity_log` table
-- automatically. `activity_log` is a brand-new table, not a new column on an
-- existing one, so there is no automatic path to it in production at all.
-- This script creates it by hand. The H2 test profile is unaffected --
-- tests create their schema from the entities, so this script has no
-- bearing on the test suite.
--
-- WHEN TO RUN
-- Run this manually via psql against the REAL Postgres database, immediately
-- before merging/deploying this phase's PR. This is one-way: master
-- auto-deploys to EC2 on every push (.github/workflows/deploy.yml), so if
-- this table is missing when the new code ships, every consumed Kafka event
-- exhausts its retries and lands on the dead-letter topic instead of ever
-- being persisted -- a total feature outage that superficially looks like
-- "the dead-letter path works" (it does; the feature underneath it does
-- not). Do not merge the PR before running this.
--
-- WHAT THIS IS NOT
-- This is a one-off manual bridge step for this phase only. It is NOT a
-- replacement for Epic 3's Flyway migration tooling -- when Epic 3 lands,
-- this manual step must be reflected in migration history (e.g. as a
-- baseline/already-applied migration), not silently re-applied or lost.
--
-- SAFE TO RE-RUN: every statement below uses IF NOT EXISTS, so accidentally
-- running this script twice is a no-op the second time.

CREATE TABLE IF NOT EXISTS activity_log (
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
CREATE INDEX IF NOT EXISTS idx_activity_log_board_created_id
    ON activity_log (board_id, created_at DESC, id DESC);
