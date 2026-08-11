package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announces that a column moved to a new position among its board's siblings (fork D-A, resolved
 * A1: dedicated event, carrying positions). Carries identifiers, actor, timestamp and the
 * server-derived {@code sourcePosition}/{@code targetPosition} pair only — both are computed
 * integer positions, never user-authored text, so they remain admissible under the event package's
 * no-user-authored-content rule (D-01). {@code targetPosition} is the column's <b>effective</b>
 * post-clamp position, not the raw requested value ({@code ColumnService#reorder} clamps a request
 * beyond the board's sibling count down to the end).
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
