package com.vrudenko.kanban_board.config;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves {@link EventIdGenerator} (GAP-07) delegates to a real, exactly-distinct, time-ordered id
 * source rather than merely compiling against {@code RandFlakeGenerator}. Distinctness is now
 * guaranteed by construction: {@code RandFlakeGenerator}'s low bits are a monotonic shared
 * sequence, not a random draw, so a same-millisecond collision is structurally impossible rather
 * than an ordinary, measured event (quick task 260813-os9 replaced the prior random-low-bits design
 * after quick task 260813-ncx measured its ~6.5%-per-1000-calls collision rate) -- see {@link
 * GenerateTest#shouldReturnDistinctValues_whenCalledManyTimesRapidly()}'s Javadoc. No mocks
 * (CODE_STYLE rule 4): {@link EventIdGenerator} touches neither Kafka nor a database, so a plain
 * Spring context is sufficient to autowire it -- this class still extends {@link
 * AbstractPostgresContainerTest} because the test profile carries no datasource without a container
 * (04.2, D-01), so booting the full context requires one even though this class's own assertions
 * never touch it.
 */
@SpringBootTest
class EventIdGeneratorTest extends AbstractPostgresContainerTest {

    @Autowired private EventIdGenerator eventIdGenerator;

    @Nested
    class GenerateTest {

        @Test
        void shouldReturnNonBlankString_whenCalled() {
            // act
            var id = eventIdGenerator.generate();

            // assert
            Assertions.assertThat(id).isNotBlank();
        }

        /**
         * Structural guarantee, not a probabilistic tolerance (quick task 260813-os9). {@code
         * RandFlakeGenerator} now composes an id from a single shared {@code AtomicLong} holding
         * {@code (timestampMillis << 22) | sequence}, updated via {@code updateAndGet(previous ->
         * max(candidate, previous + 1))} -- every call observes a strictly greater payload than
         * every prior call in the JVM, so 1000 rapid sequential calls are exactly 1000 distinct
         * values, not merely "overwhelmingly" distinct. Provenance: quick task 260813-ncx measured
         * the prior 23-random-bit design's same-millisecond collision rate (13/200 trials of 1000
         * calls, ~6.5%, matching the birthday-paradox prediction) and relaxed this assertion to a
         * measurement-derived {@code MIN_DISTINCT_IDS=993} threshold rather than fix the generator
         * that measurement motivated; 260813-os9 fixed the generator and this assertion is
         * tightened back to the original exact guarantee it now actually holds.
         */
        @Test
        void shouldReturnDistinctValues_whenCalledManyTimesRapidly() {
            // arrange
            var callCount = 1000;

            // act
            Set<String> ids =
                    IntStream.range(0, callCount)
                            .mapToObj(i -> eventIdGenerator.generate())
                            .collect(Collectors.toCollection(HashSet::new));

            // assert
            Assertions.assertThat(ids).hasSize(callCount);
        }

        /**
         * Base36 fixed-width caveat (this plan's design_rationale): {@code RandFlakeGenerator}
         * renders its id with {@code Long.toString(id, 36)}, which is NOT fixed-width -- as the
         * underlying timestamp advances the string eventually gains a character, and Base36 strings
         * of different lengths do not compare correctly under plain lexicographic comparison. At
         * the current epoch (2018-01-01, since quick task 260813-os9) the value is a stable width
         * and will remain so until 2053-10-19 (recomputed during 260813-os9; see its SUMMARY),
         * confirmed as this test's third case, so this assertion holds in practice, not by a
         * guarantee this test enforces -- nothing should be built that depends on lexicographic
         * ordering of {@code event_id} surviving a width change. Two back-to-back calls with no
         * clock wait are now asserted strictly increasing: the monotonic shared sequence
         * (260813-os9) guarantees this even within the same millisecond, unlike the prior random
         * low-bit design, and both ids share the same 12-char width so string comparison and
         * numeric comparison agree.
         */
        @Test
        void shouldSortBeforeSecondId_whenGeneratedAMeasurableIntervalApart()
                throws InterruptedException {
            // arrange
            var firstId = eventIdGenerator.generate();
            var startMillis = System.currentTimeMillis();
            while (System.currentTimeMillis() == startMillis) {
                Thread.sleep(1);
            }

            // act
            var secondId = eventIdGenerator.generate();

            // assert
            Assertions.assertThat(firstId.compareTo(secondId)).isNegative();
        }

        @Test
        void shouldSortBeforeSecondId_whenGeneratedBackToBackWithNoClockWait() {
            // arrange & act
            var firstId = eventIdGenerator.generate();
            var secondId = eventIdGenerator.generate();

            // assert
            Assertions.assertThat(firstId.compareTo(secondId)).isNegative();
        }
    }
}
