-- V2__add_optimistic_locking_version_columns.sql
-- Epic 2's optimistic locking, previously applied by hand via
-- docs/plans/backend-modernization/02-optimistic-locking-ddl.sql.

ALTER TABLE tasks ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE columns ADD COLUMN version bigint NOT NULL DEFAULT 0;
