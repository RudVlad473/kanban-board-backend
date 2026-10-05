package com.vrudenko.kanban_board.event;

import java.time.Instant;

public record TaskMovedEvent(
        String eventId,
        String userId,
        String boardId,
        String taskId,
        String sourceColumnId,
        String targetColumnId,
        Instant timestamp)
        implements ActivityEvent {}
