-- SUPERSEDED: historical reference only. Do NOT run by hand.
--
-- Folded into Flyway as src/main/resources/db/migration/V2__add_optimistic_locking_version_columns.sql,
-- now the sole owner of this schema change. The body below is kept verbatim as provenance for that
-- migration's content.
--
-- Decisions:
-- This was a one-off manual DDL bridge for optimistic locking. The Postgres profile has ddl-auto unset,
-- so Hibernate would NOT create the new `version` column (@Version on TaskEntity/ColumnEntity); the
-- script added it by hand via psql against the real database, immediately before merging the PR. The
-- order was one-way: the main branch auto-deployed on every push (.github/workflows/deploy.yml), so a
-- missing column would make every request touching a Task or Column fail with a missing-column SQL
-- error in production. The H2 test profile of that time was unaffected, since tests built their schema
-- from the entities.
-- Safe to re-run: IF NOT EXISTS makes a second run a no-op, and existing rows get a concrete version
-- (0), never NULL, so @Version(nullable = false) never fails against existing data.
-- Annotation 2026-08-17: the EC2 host the auto-deploy reasoning names no longer exists; it was torn down
-- on cost grounds and the auto-deploy target moved to a Netcup VPS (docs/INFRA_RUNBOOK.md). The stale
-- host name has no live operational effect since the script is superseded.

ALTER TABLE tasks ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

ALTER TABLE columns ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
