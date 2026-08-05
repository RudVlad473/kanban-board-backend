-- V4__add_password_hash_not_null.sql
-- Quick task 260803-m3i's password_hash NOT NULL constraint, previously
-- applied by hand via
-- docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql. That
-- script's "WHAT THIS IS NOT" section explicitly anticipated this moment:
-- when Flyway landed, this manual step had to be reflected in migration
-- history rather than silently re-applied or lost. This migration is that
-- reflection.

DO $$
DECLARE
    null_hash_count bigint;
BEGIN
    SELECT count(*) INTO null_hash_count FROM users WHERE password_hash IS NULL;

    IF null_hash_count > 0 THEN
        RAISE EXCEPTION
            'Aborting: % row(s) in users have a NULL password_hash. Resolve those rows before re-running this migration.',
            null_hash_count;
    END IF;

    ALTER TABLE users ALTER COLUMN password_hash SET NOT NULL;
END $$;
