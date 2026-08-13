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

    // Deliberately not synchronized. This generator holds no shared mutable state - it has no
    // instance fields, both constants are static final primitives, ThreadLocalRandom is
    // thread-confined, and every other value here is a local. A lock would protect nothing while
    // serializing every entity insert in the application, since this is the IdentifierGenerator
    // behind @RandFlakeId on BaseEntity. Note that a lock never contributed to id uniqueness
    // either: the low bits are random, not a sequence counter, so same-millisecond collisions
    // were always possible at the same probability. If mutable state (a sequence counter, a
    // last-timestamp field) is ever added here, revisit this.
    // Measured (quick task 260813-ncx, PROBE-FINDINGS.md): 1000 rapid calls collided in 13 of 200
    // trials (6.5%), matching the birthday prediction computed from the observed per-trial
    // millisecond clustering. See EventIdGeneratorTest.GenerateTest's third test for the derived,
    // production-facing tolerance this measurement justifies.
    public String generateRandflake() {
        long timestamp = Instant.now().toEpochMilli() - CUSTOM_EPOCH;
        long randomBits = ThreadLocalRandom.current().nextLong(1L << RANDOM_BITS);

        // Combine timestamp and random bits
        long id = (timestamp << RANDOM_BITS) | randomBits;

        // Return as string (either base 10 or base 36 for shorter representation)
        return Long.toString(id, 36); // Base36 for shorter string representation
    }
}
