package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announces that a column (and, via the cascade, every task and subtask it held) was deleted. Every
 * identifier is captured from the loaded {@code ColumnEntity} before the delete runs — once the row
 * is gone there is nothing left to derive {@code boardId} from. Carries no {@code taskId}: a column
 * delete names no single task. Carries identifiers, actor, action and timestamp only — never the
 * column's name, per the event package's no-user-authored-content rule.
 */
public record ColumnDeletedEvent(
        String eventId, String userId, String boardId, String columnId, Instant timestamp)
        implements ActivityEvent {}
