package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announce that a task was deleted.
 *
 * Every identifier is captured from the loaded TaskEntity before the delete runs:
 * afterwards nothing is left to derive boardId from.
 */
public record TaskDeletedEvent(
        String eventId,
        String userId,
        String boardId,
        String columnId,
        String taskId,
        Instant timestamp)
        implements ActivityEvent {}
