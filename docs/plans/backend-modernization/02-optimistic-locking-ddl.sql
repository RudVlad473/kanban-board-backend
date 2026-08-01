-- Epic 2 — Optimistic Locking: one-off manual DDL bridge
--
-- WHAT THIS IS
-- The real Postgres profile (src/main/resources/application.properties) has
-- ddl-auto unset, so Hibernate will NOT create the new `version` column added
-- to TaskEntity/ColumnEntity (@Version) automatically. This script adds it by
-- hand. The H2 test profile is unaffected — tests create their schema from
-- the entities, so this script has no bearing on the test suite.
--
-- WHEN TO RUN
-- Run this manually via psql against the REAL Postgres database, immediately
-- before merging/deploying this phase's PR. This is one-way (D-06): master
-- auto-deploys to EC2 on every push (.github/workflows/deploy.yml), so if
-- this column is missing when the new code ships, every request touching a
-- Task or Column hits a missing-column SQL error in production. Do not merge
-- the PR before running this.
--
-- WHAT THIS IS NOT
-- This is a one-off manual bridge step for Epic 2 only. It is NOT a
-- replacement for Epic 3's Flyway migration tooling — when Epic 3 lands, this
-- manual step must be reflected in migration history (e.g. as a baseline/
-- already-applied migration), not silently re-applied or lost.
--
-- SAFE TO RE-RUN: uses IF NOT EXISTS, so accidentally running this twice is a
-- no-op the second time. Pre-existing rows get a concrete version (0), never
-- NULL, so @Version(nullable = false) never fails against existing data.

ALTER TABLE tasks ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

ALTER TABLE columns ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
