package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announces that a task was created in a column. Carries identifiers, actor, action and timestamp
 * only — never the task's title or description, per the event package's no-user-authored-content
 * rule.
 */
public record TaskCreatedEvent(
        String eventId,
        String userId,
        String boardId,
        String columnId,
        String taskId,
        Instant timestamp)
        implements ActivityEvent {}
