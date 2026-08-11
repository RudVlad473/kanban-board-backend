package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announces that a board was renamed. Carries identifiers, actor and timestamp only — never the
 * board's new (or old) name, per the event package's no-user-authored-content rule.
 */
public record BoardUpdatedEvent(String eventId, String userId, String boardId, Instant timestamp)
        implements ActivityEvent {}
