package com.vrudenko.kanban_board.event;

import java.time.Instant;

public record TaskUpdatedEvent(
        String eventId,
        String userId,
        String boardId,
        String columnId,
        String taskId,
        Instant timestamp)
        implements ActivityEvent {}
