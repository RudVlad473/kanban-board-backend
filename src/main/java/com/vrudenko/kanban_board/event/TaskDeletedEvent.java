package com.vrudenko.kanban_board.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Announces that a task was deleted. Every identifier is captured from the loaded {@code
 * TaskEntity} before the delete runs — once the row is gone there is nothing left to derive {@code
 * boardId} from. Carries identifiers, actor, action and timestamp only — never the task's title or
 * description, per the event package's no-user-authored-content rule.
 */
public record TaskDeletedEvent(
        UUID eventId,
        String userId,
        String boardId,
        String columnId,
        String taskId,
        Instant timestamp)
        implements ActivityEvent {}
