-- SUPERSEDED: historical reference only. Do NOT run by hand.
--
-- Folded into Flyway as src/main/resources/db/migration/V4__add_password_hash_not_null.sql, now the sole
-- owner of this schema change (pre-flight NULL-count guard included). The body below is kept verbatim as
-- provenance for that migration's content.
--
-- Decisions:
-- The `04-` prefix continues this directory's DDL-script sequence (02, 03, 04) by order of delivery, NOT
-- the epic numbering of the `.md` plan docs here: `04-redis.md` is unrelated to this script. Do not
-- read this `04-` as Redis.
-- This was a one-off manual DDL bridge making `UserEntity.passwordHash` NOT NULL. The Postgres profile
-- has ddl-auto unset, so Hibernate would NOT apply the new `@Column(nullable = false)` constraint; the
-- script added it by hand via psql against the real database. The H2 test profile of that time already
-- enforced it, building its schema from the entity under ddl-auto=create-drop.
-- Unlike 02-optimistic-locking-ddl.sql, this was safe in either order: the constraint is purely additive
-- and the application only ever wrote a non-null hash (UserService.save was the only
-- userRepository.save call site in src/, and it always resolved through UserMapper's hashing overload).
-- It was run before merge anyway, since outstanding schema drift is how a bridge step gets forgotten.
-- Pre-flight, ideally days before the deploy window, as a standalone query before the ALTER below:
--   SELECT COUNT(*) FROM users WHERE password_hash IS NULL;
-- If the count is non-zero, STOP. Each such row can only have arrived via a bug, a manual DB edit or an
-- abandoned auth spike, and because passwordEncoder.matches(plaintext, null) is permanently false it can
-- never sign in, constraint or not. What happens to those rows (fix, disable, delete) is a human
-- decision, not this script's.
-- Safe to re-run: PostgreSQL's ALTER COLUMN ... SET NOT NULL has no IF NOT EXISTS clause and needs none,
-- since applying it to an already NOT NULL column is a no-op.

DO $$
DECLARE
    null_hash_count bigint;
BEGIN
    SELECT count(*) INTO null_hash_count FROM users WHERE password_hash IS NULL;

    IF null_hash_count > 0 THEN
        RAISE EXCEPTION
            'Aborting: % row(s) in users have a NULL password_hash. Resolve those rows before re-running this script.',
            null_hash_count;
    END IF;

    ALTER TABLE users ALTER COLUMN password_hash SET NOT NULL;
END $$;
