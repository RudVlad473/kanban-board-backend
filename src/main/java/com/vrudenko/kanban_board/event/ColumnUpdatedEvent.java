package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announce that a column was renamed. Distinct from {@link ColumnReorderedEvent}: a rename and a
 * position change are independently observable mutations.
 */
public record ColumnUpdatedEvent(
        String eventId, String userId, String boardId, String columnId, Instant timestamp)
        implements ActivityEvent {}
