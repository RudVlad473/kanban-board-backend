package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announces that a task was updated (title and/or description). Carries identifiers, actor and
 * timestamp only — never the task's new (or old) title or description, per the event package's
 * no-user-authored-content rule.
 */
public record TaskUpdatedEvent(
        String eventId,
        String userId,
        String boardId,
        String columnId,
        String taskId,
        Instant timestamp)
        implements ActivityEvent {}
