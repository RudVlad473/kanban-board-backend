-- V8__add_boards_created_at.sql
-- Gives boards a creation timestamp -- boards was the only user-visible resource with no record of
-- when it came into existence (activity_log can say a board *was* created, but the board itself
-- could not say when). Two statements:
-- (a) Existing rows are backfilled to migration time because no real historical creation time
--     exists to recover for boards created before this column existed.
-- (b) `now()` is STABLE, not VOLATILE, so PostgreSQL evaluates it once and stores the result as a
--     single pg_attribute.attmissingval instead of rewriting the table -- the same catalog-only-
--     change property V7's own header claims for `DEFAULT 0`.
-- (c) The default is dropped immediately afterward so the application stays the single writer of
--     this column: a future insert path that omits the value is rejected by the NOT NULL
--     constraint rather than quietly receiving insert time from a permanent DB default.

ALTER TABLE boards ADD COLUMN created_at timestamp(6) with time zone NOT NULL DEFAULT now();
ALTER TABLE boards ALTER COLUMN created_at DROP DEFAULT;
