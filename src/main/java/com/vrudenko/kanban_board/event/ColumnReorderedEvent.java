package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announce that a column moved to a new position among its board's siblings, carrying the
 * server-derived {@code sourcePosition}/{@code targetPosition} pair.
 *
 * <p>Both positions are computed integers, never user-authored text. {@code targetPosition} is the
 * <b>effective</b> post-clamp position, not the raw requested value: {@code ColumnService#reorder}
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
