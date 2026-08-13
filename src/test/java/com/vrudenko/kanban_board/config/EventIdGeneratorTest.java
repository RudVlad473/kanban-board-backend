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
 * Proves {@link EventIdGenerator} (GAP-07) delegates to a real, overwhelmingly-distinct,
 * time-ordered id source rather than merely compiling against {@code RandFlakeGenerator}. Not
 * exhaustively distinct: the generator's low 23 bits are random, not a sequence counter, so a
 * same-millisecond collision is an ordinary, measured event rather than a defect -- see {@link
 * GenerateTest#shouldReturnOverwhelminglyDistinctValues_whenCalledManyTimesRapidly()}'s Javadoc for
 * the measurement. No mocks (CODE_STYLE rule 4): {@link EventIdGenerator} touches neither Kafka nor
 * a database, so a plain Spring context is sufficient to autowire it -- this class still extends
 * {@link AbstractPostgresContainerTest} because the test profile carries no datasource without a
 * container (04.2, D-01), so booting the full context requires one even though this class's own
 * assertions never touch it.
 */
@SpringBootTest
class EventIdGeneratorTest extends AbstractPostgresContainerTest {

    @Autowired private EventIdGenerator eventIdGenerator;

    @Nested
    class GenerateTest {

        // See shouldReturnOverwhelminglyDistinctValues_whenCalledManyTimesRapidly's Javadoc for the
        // derivation (quick task 260813-ncx, PROBE-FINDINGS.md).
        private static final int MIN_DISTINCT_IDS = 993;

        @Test
        void shouldReturnNonBlankString_whenCalled() {
            // act
            var id = eventIdGenerator.generate();

            // assert
            Assertions.assertThat(id).isNotBlank();
        }

        /**
         * Measurement-derived threshold, not an exact-distinctness guarantee (quick task
         * 260813-ncx, PROBE-FINDINGS.md). {@code RandFlakeGenerator} composes an id as {@code
         * (timestampMillis << 23) | random23Bits} -- 8,388,608 distinct values per millisecond,
         * drawn from {@code ThreadLocalRandom} with no sequence counter, no dedupe and no retry.
         * 1000 rapid sequential calls mostly land inside one or two decoded milliseconds, so a
         * same-millisecond birthday collision is an ordinary outcome, not a defect: on the machine
         * that produced PROBE-FINDINGS.md, T=200 trials of 1000 calls measured C=13 colliding
         * trials against a birthday prediction of E=10.63 computed from the observed per-trial
         * bucket sizes (C <= E+3*sqrt(E) = 20.41, the {@code INHERENT_BIRTHDAY} criterion).
         *
         * <p>{@link #MIN_DISTINCT_IDS} = 993 was chosen from PROBE-FINDINGS.md's
         * candidate-threshold table: modeling per-trial shortfall as Poisson with the worst
         * observed single-bucket rate (mu = 1000*999/(2*8388608) ~= 0.059564), {@code P(distinct <
         * 993)} ~= 3.7e-15, three orders of magnitude below the 1e-9 false-failure floor -- not
         * sitting on that floor's boundary the way a threshold of 995 or 996 would. It also clears
         * the second floor: an entropy-free (constant-random) delegate would score at most the
         * number of distinct milliseconds a 1000-call loop spans -- observed as high as 4 on this
         * machine -- and 993 is more than 50x that. D-07's falsification (quick task 260813-ncx's
         * SUMMARY) proved this threshold still has teeth: replacing the random draw with a constant
         * reds this assertion, and restoring it greens it with zero net {@code src/main} diff.
         */
        @Test
        void shouldReturnOverwhelminglyDistinctValues_whenCalledManyTimesRapidly() {
            // arrange
            var callCount = 1000;

            // act
            Set<String> ids =
                    IntStream.range(0, callCount)
                            .mapToObj(i -> eventIdGenerator.generate())
                            .collect(Collectors.toCollection(HashSet::new));

            // assert
            Assertions.assertThat(ids).hasSizeGreaterThanOrEqualTo(MIN_DISTINCT_IDS);
        }

        /**
         * Base36 fixed-width caveat (this plan's design_rationale): {@code RandFlakeGenerator}
         * renders its id with {@code Long.toString(id, 36)}, which is NOT fixed-width -- as the
         * underlying timestamp advances the string eventually gains a character, and Base36 strings
         * of different lengths do not compare correctly under plain lexicographic comparison. At
         * the current epoch the value is a stable width and will remain so for years, so this
         * assertion holds in practice, not by a guarantee this test enforces -- nothing should be
         * built that depends on lexicographic ordering of {@code event_id} surviving a width
         * change. Deliberately does not assert ordering on two ids generated in the same
         * millisecond: the low 23 bits are random, not a counter, so such an assertion would be
         * flaky by construction -- the loop below waits for a real millisecond boundary to cross
         * before generating the second id.
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
    }
}
