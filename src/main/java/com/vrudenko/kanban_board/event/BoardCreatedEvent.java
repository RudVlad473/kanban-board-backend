package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announces that a board was created. Carries identifiers, actor and timestamp only — never the
 * board's name, per the event package's no-user-authored-content rule.
 */
public record BoardCreatedEvent(String eventId, String userId, String boardId, Instant timestamp)
        implements ActivityEvent {}
