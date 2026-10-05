package com.vrudenko.kanban_board.event;

import java.time.Instant;

public record BoardUpdatedEvent(String eventId, String userId, String boardId, Instant timestamp)
        implements ActivityEvent {}
