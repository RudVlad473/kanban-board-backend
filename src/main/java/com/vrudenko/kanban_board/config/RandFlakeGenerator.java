package com.vrudenko.kanban_board.config;

import java.util.concurrent.atomic.AtomicLong;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.EventType;
import org.hibernate.id.IdentifierGenerator;

/**
 * Generate base36 ids from a Snowflake-shaped packed long: 1 unused sign bit, 41 timestamp
 * bits (lasting until 2087-09-07), 22 sequence bits.
 *
 * See https://adileo.github.io/awesome-identifiers/.
 *
 * Decisions:
 *
 * Uniqueness is per-JVM, and that is the whole guarantee: two JVMs sharing a database would
 * collide on the shared sequence space, since no machine-id field separates them. Accepted
 * deliberately because the app runs as a single replica (k8s/base/app/app.yaml). The old
 * random-low-bits design was equally unsafe across instances, only probabilistically.
 */
public class RandFlakeGenerator implements IdentifierGenerator {
    private static final long SEQUENCE_BITS = 22L;

    // Custom epoch, 2018-01-01. Moved back from 2023-01-01 so new ids keep sorting above legacy
    // ids.
    //
    // Decisions:
    // Narrowing the low field from 23 to 22 bits alone would have halved every new id's magnitude,
    // so new ids would sort below every id already in the live database (inverting @OrderBy("id")
    // on BoardEntity.column, ColumnEntity.task and TaskEntity.subtasks) until 2030-03-26. The 2018
    // epoch keeps every id from this layout above the highest the legacy (23-random-bit,
    // 2023-01-01) layout could produce. Accepted cost: the constant no longer decodes a historical
    // id to its true creation time; no production code decodes an id.
    private static final long CUSTOM_EPOCH = 1514764800000L;

    // Last issued (timestamp << SEQUENCE_BITS | sequence) payload, shared JVM-wide. MUST be static:
    // Hibernate builds one generator per @RandFlakeId mapping and EventIdGenerator builds its own,
    // so per-instance state would let two instances emit identical ids in the same millisecond.
    private static final AtomicLong LAST_ID = new AtomicLong();

    @Override
    public String generate(SharedSessionContractImplementor session, Object object) {
        return generateRandflake();
    }

    // Keep an id the caller assigned before persist, instead of generating over it.
    //
    // Decisions:
    // Verified against hibernate-core-6.6.53.Final.jar on 2026-09-08: TWO overrides are both
    // required, and the first alone is not enough (a real e2e run proved it).
    // 1. allowAssignedIdentifiers(): Generator's inherited default is false, and
    //    AbstractSaveEventListener#generateId reads it to decide whether to pass the entity's
    //    already-set id into generate(...) as currentValue; false means currentValue is always
    // null.
    // 2. The 4-arg generate(session, owner, currentValue, eventType) below: IdentifierGenerator's
    //    default ignores currentValue and forwards to the legacy 2-arg generate, so (1) achieves
    //    nothing unless this class acts on it. AbstractSaveEventListener#saveWithGeneratedId calls
    //    generate(...) unconditionally for every insert (Assigned-strategy generators excepted).
    // Both are inherited by every entity using this generator but change behaviour only where an id
    // is assigned before persist: today BoardService#save alone.
    @Override
    public boolean allowAssignedIdentifiers() {
        return true;
    }

    @Override
    public Object generate(
            SharedSessionContractImplementor session,
            Object owner,
            Object currentValue,
            EventType eventType) {
        return currentValue != null ? currentValue : generateRandflake();
    }

    // Generate the next id from one packed long updated by a lock-free CAS loop.
    //
    // Decisions:
    // Lock-free by construction: packing (timestamp, sequence) into one long makes the pair atomic,
    // where two fields could not be updated together without a lock. Contending threads retry
    // against a fresher read instead of blocking.
    // Math.max(candidate, previous + 1) does three jobs: (1) a fresh millisecond resets the
    // sequence for free, since candidate wins once the tick advances; (2) same-millisecond calls
    // increment previous, which is the sequence counter; (3) sequence exhaustion (more than
    // 2^SEQUENCE_BITS = 4,194,304 ids in one millisecond) and a backward clock step (NTP
    // correction) take the same previous + 1 branch, borrowing into the next millisecond's bits
    // instead of spin-waiting (Sonyflake, parks a thread) or throwing (reference Snowflake, fails
    // an insert). Cost: bounded clock drift under a sustained rate this single-instance app cannot
    // produce.
    // Measured: the prior 23-random-bit design collided in 13 of 200 trials (6.5%) of 1000 rapid
    // calls, matching the birthday prediction from the observed per-trial millisecond clustering.
    // Same-millisecond collisions are now structurally impossible.
    public String generateRandflake() {
        long payload =
                LAST_ID.updateAndGet(
                        previous ->
                                Math.max(
                                        (System.currentTimeMillis() - CUSTOM_EPOCH)
                                                << SEQUENCE_BITS,
                                        previous + 1));

        return Long.toString(payload, 36); // Base36 for shorter string representation
    }
}
