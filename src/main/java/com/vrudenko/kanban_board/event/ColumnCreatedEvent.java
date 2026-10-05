package com.vrudenko.kanban_board.event;

import java.time.Instant;

public record ColumnCreatedEvent(
        String eventId, String userId, String boardId, String columnId, Instant timestamp)
        implements ActivityEvent {}
