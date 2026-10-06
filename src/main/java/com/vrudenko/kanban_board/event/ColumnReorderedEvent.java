package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announce that a column moved to a new position among its board's siblings, carrying the
 * server-derived sourcePosition/targetPosition pair.
 *
 * Both positions are computed integers, never user-authored text. targetPosition is the
 * effective post-clamp position, not the raw requested value: ColumnService#reorder
 * clamps a request beyond the board's sibling count down to the end.
 */
public record ColumnReorderedEvent(
        String eventId,
        String userId,
        String boardId,
        String columnId,
        int sourcePosition,
        int targetPosition,
        Instant timestamp)
        implements ActivityEvent {}
