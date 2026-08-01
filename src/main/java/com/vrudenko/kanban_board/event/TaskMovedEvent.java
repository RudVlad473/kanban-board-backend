package com.vrudenko.kanban_board.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Announces that a task was reassigned to a different column. Carries identifiers, actor, action
 * and timestamp only — never the task's title or description, per the event package's
 * no-user-authored-content rule.
 */
public record TaskMovedEvent(
        UUID eventId,
        String userId,
        String boardId,
        String taskId,
        String sourceColumnId,
        String targetColumnId,
        Instant timestamp)
        implements ActivityEvent {}
