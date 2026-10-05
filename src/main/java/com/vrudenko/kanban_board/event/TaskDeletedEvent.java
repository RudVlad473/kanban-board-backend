package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announce that a task was deleted.
 *
 * <p>Every identifier is captured from the loaded {@code TaskEntity} before the delete runs:
 * afterwards nothing is left to derive {@code boardId} from.
 */
public record TaskDeletedEvent(
        String eventId,
        String userId,
        String boardId,
        String columnId,
        String taskId,
        Instant timestamp)
        implements ActivityEvent {}
