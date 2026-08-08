-- V6__change_activity_log_event_id_to_varchar.sql
-- GAP-07 (folded todo): switches activity_log.event_id from a random uuid to a Base36
-- Snowflake-style string produced by the project's existing RandFlakeGenerator, reused via the
-- new EventIdGenerator wrapper. PostgreSQL cannot implicitly cast uuid to a character type, so
-- the ALTER COLUMN TYPE below needs an explicit USING cast. The unique constraint is dropped and
-- recreated under its original name around the type change, since a type change cannot happen
-- while the constraint that depends on the column is still in place.
--
-- Existing rows keep their UUID string form after this migration; only newly-inserted rows carry
-- the new Base36 form. The column holds a mix of both shapes going forward, which is expected and
-- harmless — event_id is a dedupe key compared for equality only, never parsed.

ALTER TABLE activity_log DROP CONSTRAINT uk_activity_log_event_id;

ALTER TABLE activity_log ALTER COLUMN event_id TYPE varchar(255) USING event_id::varchar(255);

ALTER TABLE activity_log ADD CONSTRAINT uk_activity_log_event_id UNIQUE (event_id);
