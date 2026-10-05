package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announce that a board was deleted, once per directly-requested delete.
 *
 * <p>The cascaded column, task and subtask deletes publish nothing of their own; see {@code
 * ColumnService.deleteAllByBoardId} and {@code TaskService.deleteAllByColumn}.
 */
public record BoardDeletedEvent(String eventId, String userId, String boardId, Instant timestamp)
        implements ActivityEvent {}
