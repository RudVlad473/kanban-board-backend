package com.vrudenko.kanban_board.entity;

/**
 * Closed set of activity actions an activity_log row can record, mapped 1:1 from the
 * publishing event's Java class name by ActivityLogConsumer.
 *
 * An enum rather than a bare String per docs/CODE_STYLE.md rule 1: the compiler enforces
 * the closed set, and the mapping switch can be checked for exhaustiveness.
 */
public enum ActivityAction {
    TASK_CREATED,
    TASK_MOVED,
    TASK_DELETED,
    TASK_UPDATED,
    BOARD_CREATED,
    BOARD_UPDATED,
    BOARD_DELETED,
    COLUMN_CREATED,
    COLUMN_DELETED,
    COLUMN_UPDATED,
    COLUMN_REORDERED,
    SUBTASK_CREATED,
    SUBTASK_UPDATED,
    SUBTASK_DELETED
}
