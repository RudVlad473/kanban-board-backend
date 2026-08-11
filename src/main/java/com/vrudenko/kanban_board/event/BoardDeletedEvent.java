package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announces that a board was deleted. Carries identifiers, actor and timestamp only. Fired once per
 * board for the directly-requested delete (D-D, fork resolution D1) — the cascaded column, task and
 * subtask deletes underneath it publish nothing of their own; see {@code
 * ColumnService.deleteAllByBoardId} and {@code TaskService.deleteAllByColumn}.
 */
public record BoardDeletedEvent(String eventId, String userId, String boardId, Instant timestamp)
        implements ActivityEvent {}
