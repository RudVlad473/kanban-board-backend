package com.vrudenko.kanban_board.config;

import org.springframework.stereotype.Component;

/**
 * Expose RandFlakeGenerator's id algorithm to callers that are not a JPA entity primary
 * key, currently every ActivityEvent's eventId.
 *
 * RandFlakeGenerator implements Hibernate's IdentifierGenerator and is wired
 * only through @RandFlakeId, so it cannot be injected into a @Service. This thin
 * wrapper delegates to RandFlakeGenerator.generateRandflake(), which is thread-safe. A
 * second implementation of the algorithm anywhere else is a defect, not an alternative.
 */
@Component
public class EventIdGenerator {
    private final RandFlakeGenerator randFlakeGenerator = new RandFlakeGenerator();

    public String generate() {
        return randFlakeGenerator.generateRandflake();
    }
}
