package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announce that a column, and via the cascade every task and subtask it held, was deleted.
 *
 * <p>Every identifier is captured from the loaded {@code ColumnEntity} before the delete runs:
 * afterwards nothing is left to derive {@code boardId} from. There is no {@code taskId}: a column
 * delete names no single task.
 */
public record ColumnDeletedEvent(
        String eventId, String userId, String boardId, String columnId, Instant timestamp)
        implements ActivityEvent {}
