-- SUPERSEDED (GSD Phase 04.1, D-03): this script is historical reference
-- material only. Do NOT run it by hand anymore. It has been folded into
-- Flyway migration history as
-- src/main/resources/db/migration/V4__add_password_hash_not_null.sql, which
-- is now the sole owner of this schema change (pre-flight NULL-count guard
-- included). This closes the loop the "WHAT THIS IS NOT" section below
-- opened: the manual step it asked to eventually be reflected in migration
-- history, rather than lost, now is. The body below is kept verbatim as
-- provenance for that migration's content and the schema decision it
-- documents -- it is no longer executable guidance.
--
-- Quick task 260803-m3i -- passwordHash NOT NULL: one-off manual DDL bridge
--
-- The `04-` prefix here continues this directory's DDL-script sequence
-- (02, 03, 04) by order of delivery, NOT the epic numbering used by the
-- `.md` plan docs in this same directory. `04-redis.md` is Epic 4 and is
-- unrelated to this script. This bridge comes from a quick task, which has
-- no number in either sequence; continuing the DDL sequence was judged the
-- most legible option available. Do not read this `04-` as Redis.
--
-- WHAT THIS IS
-- The real Postgres profile (src/main/resources/application.properties) has
-- ddl-auto unset, so Hibernate will NOT apply the new
-- `@Column(nullable = false)` constraint added to
-- `UserEntity.passwordHash` automatically. This script adds the equivalent
-- constraint by hand. The H2 test profile is unaffected -- tests build their
-- schema from the entity under ddl-auto=create-drop, so the constraint is
-- already enforced there.
--
-- WHEN TO RUN
-- Run this manually via psql against the REAL Postgres database, immediately
-- before merging/deploying this PR. Unlike 02-optimistic-locking-ddl.sql,
-- this one is safe in either order: the constraint is purely additive and
-- the application already only ever writes a non-null hash (UserService.save
-- is the only userRepository.save call site in src/, and it always resolves
-- through UserMapper's hashing overload), so no request breaks if this lags
-- the merge. Run it before merge anyway, for the same reason as the
-- precedents -- outstanding schema drift is how a bridge step gets
-- forgotten -- not because production is at risk if it lags.
--
-- PRE-FLIGHT (do this first, ideally days before the deploy window)
-- Run this as a standalone query before ever attempting the ALTER below:
--
--   SELECT COUNT(*) FROM users WHERE password_hash IS NULL;
--
-- If the count is non-zero, STOP. Do not run the block below yet. Each such
-- row is an account that can only have gotten there via a bug, a manual DB
-- edit, or an abandoned auth spike -- and, because
-- passwordEncoder.matches(plaintext, null) is permanently false, each one is
-- an account that can never sign in today, constraint or not. Decide what
-- happens to those specific rows (fix, disable, delete) before proceeding;
-- that decision belongs to a human, not to this script.
--
-- WHAT THIS IS NOT
-- This is a one-off manual bridge step for this quick task only. It is NOT a
-- replacement for Epic 3's Flyway migration tooling -- when Epic 3 lands,
-- this manual step must be reflected in migration history (e.g. as a
-- baseline/already-applied migration), not silently re-applied or lost.
--
-- SAFE TO RE-RUN
-- PostgreSQL's ALTER TABLE ... ALTER COLUMN ... SET NOT NULL has no
-- IF NOT EXISTS clause and needs none: applying it to a column that is
-- already NOT NULL is a no-op. So this script looks different from the two
-- ADD COLUMN IF NOT EXISTS precedents in this directory while being equally
-- re-runnable.

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
