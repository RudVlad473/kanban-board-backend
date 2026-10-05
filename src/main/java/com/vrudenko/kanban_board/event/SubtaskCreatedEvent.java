package com.vrudenko.kanban_board.event;

import java.time.Instant;

public record SubtaskCreatedEvent(
        String eventId,
        String userId,
        String boardId,
        String taskId,
        String subtaskId,
        Instant timestamp)
        implements ActivityEvent {}
