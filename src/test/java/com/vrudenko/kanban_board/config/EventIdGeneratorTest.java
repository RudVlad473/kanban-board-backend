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
 * Proves EventIdGenerator delegates to a distinct, time-ordered id source.
 *
 * No mocks (CODE_STYLE rule 4): the generator touches neither Kafka nor a database. Extends
 * AbstractPostgresContainerTest because the test profile names no datasource, so booting
 * the context needs a container even though no assertion here touches it.
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
         * 1000 rapid sequential calls yield exactly 1000 distinct values, by construction rather
         * than by probability.
         *
         * Why this is the way it is: RandFlakeGenerator composes each id from one shared
         * AtomicLong holding (timestampMillis << 22) | sequence, updated via
         * updateAndGet(previous -> max(candidate, previous + 1)), so every call sees a strictly
         * greater payload than any prior call in the JVM. The earlier 23-random-bit design collided
         * in 13/200 trials of 1000 calls (~6.5%, matching the birthday-paradox prediction), and
         * this assertion was relaxed to MIN_DISTINCT_IDS=993 while that design lived.
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
         * Two back-to-back ids sort in generation order, which holds in practice, not by a
         * guarantee this test enforces.
         *
         * Known holes: Long.toString(id, 36) is not fixed-width, so Base36 ids of
         * different lengths do not compare correctly lexicographically. At the current epoch
         * (2018-01-01) the width is stable until 2053-10-19, so nothing should depend on
         * lexicographic ordering of event_id surviving a width change. The monotonic shared
         * sequence makes two back-to-back calls strictly increasing even within one millisecond,
         * and both ids share the 12-char width, so string and numeric comparison agree.
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
