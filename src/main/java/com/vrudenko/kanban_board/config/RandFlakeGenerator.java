package com.vrudenko.kanban_board.config;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentifierGenerator;

// https://adileo.github.io/awesome-identifiers/
public class RandFlakeGenerator implements IdentifierGenerator {
    // Timestamp occupies the bits above RANDOM_BITS (41 bits at current shift width, giving
    // ~69 years before rollover). Documented here rather than as a constant because nothing in
    // this class needs to reference the timestamp width directly - only RANDOM_BITS is used to
    // compute the shift.
    private static final long RANDOM_BITS = 23L;

    // Custom epoch (January 1, 2023)
    private static final long CUSTOM_EPOCH = 1672531200000L;

    @Override
    public String generate(SharedSessionContractImplementor session, Object object) {
        return generateRandflake();
    }

    public synchronized String generateRandflake() {
        long timestamp = Instant.now().toEpochMilli() - CUSTOM_EPOCH;
        long randomBits = ThreadLocalRandom.current().nextLong(1L << RANDOM_BITS);

        // Combine timestamp and random bits
        long id = (timestamp << RANDOM_BITS) | randomBits;

        // Return as string (either base 10 or base 36 for shorter representation)
        return Long.toString(id, 36); // Base36 for shorter string representation
    }
}
