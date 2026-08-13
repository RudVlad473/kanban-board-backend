package com.vrudenko.kanban_board.config;

import java.util.concurrent.atomic.AtomicLong;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentifierGenerator;

// https://adileo.github.io/awesome-identifiers/
public class RandFlakeGenerator implements IdentifierGenerator {
    // Timestamp occupies the bits above SEQUENCE_BITS (41 bits at current shift width, giving
    // ~69 years before rollover, exhausting 2087-09-07). Documented here rather than as a
    // constant because nothing in this class needs to reference the timestamp width directly -
    // only SEQUENCE_BITS is used to compute the shift. Bit 63 (the sign bit) is never written,
    // so the packed value is always a positive long: 1 sign + 41 timestamp + 22 sequence = 64.
    private static final long SEQUENCE_BITS = 22L;

    // Custom epoch (January 1, 2018). Moved back from the original 2023-01-01 in the same change
    // that narrowed the low field from 23 to 22 bits (quick task 260813-os9): narrowing the low
    // field alone would have halved every future id's magnitude, making new ids sort *below*
    // every id already in the live database (inverting @OrderBy("id") on BoardEntity.column,
    // ColumnEntity.task and TaskEntity.subtasks) until 2030-03-26. Moving the epoch back to
    // 2018-01-01 keeps every id generated under this layout numerically greater than the highest
    // id the legacy (23-random-bit, 2023-01-01-epoch) layout could ever have produced, so
    // creation-order collection ordering survives the deploy boundary. This means the constant no
    // longer decodes a *historical* id (one persisted before this change) to its true creation
    // time - accepted, since no production code decodes an id; only a throwaway probe did.
    private static final long CUSTOM_EPOCH = 1514764800000L;

    // Holds the last issued (timestamp << SEQUENCE_BITS | sequence) payload, shared by every
    // caller in the JVM. MUST be static: Hibernate constructs one RandFlakeGenerator instance per
    // @RandFlakeId mapping, and EventIdGenerator constructs its own with `new
    // RandFlakeGenerator()`. A non-static field would give each instance its own sequence, so two
    // instances ticking in the same millisecond would emit identical ids - converting the old
    // design's ~6.5%-per-1000-calls probabilistic collision (quick task 260813-ncx measured this)
    // into a deterministic one.
    private static final AtomicLong LAST_ID = new AtomicLong();

    @Override
    public String generate(SharedSessionContractImplementor session, Object object) {
        return generateRandflake();
    }

    // Lock-free by construction, not by absence of state: packing (timestamp, sequence) into one
    // long is what makes the pair atomic without a lock - two separate fields (a lastTimestamp
    // long plus a sequence long) could not be updated together without a lock, since a thread
    // could observe one field updated and the other stale. The single updateAndGet CAS loop below
    // is the only correct alternative to that: every contending thread retries against a fresher
    // read rather than blocking.
    //
    // `Math.max(candidate, previous + 1)` does three jobs, not one: (1) a fresh millisecond resets
    // the sequence to zero for free, since `candidate` (the new tick shifted into position) wins
    // over `previous + 1` once the tick advances; (2) same-millisecond calls increment `previous`
    // by 1 each time - this is the sequence counter; (3) sequence exhaustion (more than
    // 2^SEQUENCE_BITS = 4,194,304 ids in one millisecond) and a backward clock step (an NTP
    // correction) are both handled by the same `previous + 1` branch, which borrows into the next
    // millisecond's timestamp bits rather than spin-waiting (Sonyflake's approach, parks a thread)
    // or throwing (reference Snowflake's approach, fails an insert). The cost is bounded clock
    // drift under a sustained rate this single-instance app cannot produce (4,194,304 ids/ms).
    //
    // Uniqueness is per-JVM, and that is the whole guarantee: two JVMs sharing a database would
    // collide on this shared sequence space, since there is no machine-id field to separate them.
    // Accepted deliberately - this app is single-instance (docker-compose.prod.yml) - and stated
    // here rather than left implicit. The old random-low-bits design was equally unsafe across
    // instances, just probabilistically instead of deterministically.
    //
    // Layout: 1 sign bit (never written) + 41 timestamp bits + 22 sequence bits = 64,
    // exhausting 2087-09-07.
    //
    // Measured (quick task 260813-ncx, PROBE-FINDINGS.md): the prior 23-random-bit design
    // collided in 13 of 200 trials (6.5%) of 1000 rapid calls, matching the birthday prediction
    // computed from the observed per-trial millisecond clustering - this is the measurement that
    // motivated replacing the random low bits with the monotonic sequence above (quick task
    // 260813-os9). Same-millisecond collisions are now structurally impossible instead of a
    // measured probabilistic event.
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
