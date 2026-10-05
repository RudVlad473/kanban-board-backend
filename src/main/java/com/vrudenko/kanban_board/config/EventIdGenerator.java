package com.vrudenko.kanban_board.config;

import org.springframework.stereotype.Component;

/**
 * Expose {@link RandFlakeGenerator}'s id algorithm to callers that are not a JPA entity primary
 * key, currently every {@code ActivityEvent}'s {@code eventId}.
 *
 * <p>{@link RandFlakeGenerator} implements Hibernate's {@code IdentifierGenerator} and is wired
 * only through {@code @RandFlakeId}, so it cannot be injected into a {@code @Service}. This thin
 * wrapper delegates to {@link RandFlakeGenerator#generateRandflake()}, which is thread-safe. A
 * second implementation of the algorithm anywhere else is a defect, not an alternative.
 */
@Component
public class EventIdGenerator {
    private final RandFlakeGenerator randFlakeGenerator = new RandFlakeGenerator();

    public String generate() {
        return randFlakeGenerator.generateRandflake();
    }
}
