package com.vrudenko.kanban_board;

import com.vrudenko.kanban_board.config.EventIdGenerator;
import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves {@link EventIdGenerator} (GAP-07) delegates to a real, distinct, time-ordered id source
 * rather than merely compiling against {@code RandFlakeGenerator}. No mocks (CODE_STYLE rule 4):
 * {@link EventIdGenerator} touches neither Kafka nor a database, so a plain Spring context is
 * sufficient to autowire it -- this class still extends {@link AbstractPostgresContainerTest}
 * because the test profile carries no datasource without a container (04.2, D-01), so booting the
 * full context requires one even though this class's own assertions never touch it.
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
