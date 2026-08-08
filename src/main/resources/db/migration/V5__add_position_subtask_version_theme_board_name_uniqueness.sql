-- V5__add_position_subtask_version_theme_board_name_uniqueness.sql
-- Phase 6 (Mock-up Feature Gap Closure) database foundation: task/column ordering
-- (GAP-03), subtask optimistic locking (GAP-06), per-user theme persistence
-- (GAP-05), and board-name uniqueness per user (GAP-01, resolving
-- UserService.addBoardByUserId's long-standing TODO).

ALTER TABLE tasks ADD COLUMN position integer NOT NULL DEFAULT 0;
ALTER TABLE columns ADD COLUMN position integer NOT NULL DEFAULT 0;
ALTER TABLE subtasks ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN theme varchar(10) NOT NULL DEFAULT 'LIGHT';

DO $$
DECLARE
    duplicate_name_count bigint;
BEGIN
    SELECT count(*) INTO duplicate_name_count
    FROM (
        SELECT user_id, name FROM boards GROUP BY user_id, name HAVING count(*) > 1
    ) duplicates;

    IF duplicate_name_count > 0 THEN
        RAISE EXCEPTION
            'Aborting: % user/name group(s) in boards have duplicate names. Resolve those rows before re-running this migration.',
            duplicate_name_count;
    END IF;

    ALTER TABLE boards ADD CONSTRAINT uk_boards_user_id_name UNIQUE (user_id, name);
END $$;
