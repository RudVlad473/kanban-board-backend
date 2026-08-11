package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announces that a column was renamed. Carries identifiers, actor and timestamp only — never the
 * column's new (or old) name, per the event package's no-user-authored-content rule. Distinct from
 * {@link ColumnReorderedEvent}: a rename and a position change are different, independently
 * observable mutations (fork D-A).
 */
public record ColumnUpdatedEvent(
        String eventId, String userId, String boardId, String columnId, Instant timestamp)
        implements ActivityEvent {}
