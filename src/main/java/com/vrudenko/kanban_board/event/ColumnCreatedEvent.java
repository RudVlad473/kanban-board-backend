package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announces that a column was created on a board. Carries identifiers, actor and timestamp only —
 * never the column's name, per the event package's no-user-authored-content rule.
 */
public record ColumnCreatedEvent(
        String eventId, String userId, String boardId, String columnId, Instant timestamp)
        implements ActivityEvent {}
