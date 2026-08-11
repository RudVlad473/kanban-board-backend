package com.vrudenko.kanban_board.entity;

/**
 * Closed set of activity actions an {@code activity_log} row can record (D-02). Mapped 1:1 from the
 * publishing event's Java class name by {@code ActivityLogConsumer}. Modeled as an enum rather than
 * a bare String per {@code docs/CODE_STYLE.md} rule 1: the compiler enforces the closed set, and
 * the mapping switch that derives this value can be checked for exhaustiveness.
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
