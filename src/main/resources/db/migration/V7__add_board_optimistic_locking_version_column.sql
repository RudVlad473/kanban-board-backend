-- V7__add_board_optimistic_locking_version_column.sql
-- Closes Phase 07.1's Board/Column/Task/Subtask optimistic-locking asymmetry (D-13): boards was
-- the only resource in the hierarchy without a version column. DEFAULT 0 satisfies NOT NULL for
-- every pre-existing row with no data-migration step, and (like V2's tasks/columns precedent) a
-- constant, non-volatile default keeps this a catalog-only change on PostgreSQL 10+, no full table
-- rewrite.

ALTER TABLE boards ADD COLUMN version bigint NOT NULL DEFAULT 0;
