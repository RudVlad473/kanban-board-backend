package com.vrudenko.kanban_board.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared contract for every domain event this application publishes to {@code kanban.activity}.
 * Plain, dependency-free records only — no Lombok, no JPA, no Spring annotations — because this
 * package is shared by the producer (this phase) and Phase 3's consumer.
 *
 * <p>{@code boardId} is mandatory on every event, not derived by the consumer: Phase 3's listener
 * runs with no {@code SecurityContext} and never re-verifies ownership, so whatever this
 * interface's implementations carry is trusted downstream as-is.
 */
public sealed interface ActivityEvent permits TaskCreatedEvent, TaskMovedEvent, TaskDeletedEvent {
    UUID eventId();

    String userId();

    String boardId();

    Instant timestamp();
}
