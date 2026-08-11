package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announces that a subtask was deleted. Carries identifiers, actor and timestamp only — never the
 * subtask's title, per the event package's no-user-authored-content rule.
 */
public record SubtaskDeletedEvent(
        String eventId,
        String userId,
        String boardId,
        String taskId,
        String subtaskId,
        Instant timestamp)
        implements ActivityEvent {}
