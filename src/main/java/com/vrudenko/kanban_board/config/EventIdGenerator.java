package com.vrudenko.kanban_board.config;

import org.springframework.stereotype.Component;

/**
 * Exposes {@link RandFlakeGenerator}'s time-ordered, Base36 id algorithm to callers that need a new
 * id but are not a JPA entity primary key — currently, every {@code ActivityEvent}'s {@code
 * eventId} (GAP-07).
 *
 * <p>{@link RandFlakeGenerator} itself implements Hibernate's {@code IdentifierGenerator} and is
 * wired only through the {@code @RandFlakeId} annotation mechanism, not Spring's {@code @Autowired}
 * dependency injection, so it cannot be injected directly into a {@code @Service}. This class is a
 * thin, injectable wrapper delegating to {@link RandFlakeGenerator#generateRandflake()}, which is
 * safe to call from anywhere — its own Javadoc records that it holds no shared mutable state.
 * {@code RandFlakeGenerator} is the single source of the timestamp-plus-random-bits algorithm in
 * this codebase; a second implementation of that same logic anywhere else is a defect, not an
 * acceptable alternative.
 */
@Component
public class EventIdGenerator {
    private final RandFlakeGenerator randFlakeGenerator = new RandFlakeGenerator();

    public String generate() {
        return randFlakeGenerator.generateRandflake();
    }
}
