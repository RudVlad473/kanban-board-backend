package com.vrudenko.kanban_board.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit coverage for RandFlakeGenerator.generateRandflake(), with no Spring context
 * or container because the class under test touches neither.
 *
 * Proves the monotonic shared sequence closes the same-millisecond collision (13/200 trials of
 * 1000 rapid calls, ~6.5%, under the old random-low-bits design) across both threads and generator
 * instances, which a per-instance or per-thread counter would each fail separately.
 */
public class RandFlakeGeneratorTest {

    @Nested
    class GenerateRandflakeTest {

        // Largest id the legacy layout (23 random low bits, CUSTOM_EPOCH = 2023-01-01) could have
        // produced running until 2027-01-01T00:00:00Z.
        //
        // ((2027-01-01 - 2023-01-01) << 23) | (2^23 - 1) = 1058897343291588607. Frozen, not
        // computed live: a live ceiling stops being a real assertion after 2027-01-01, as ids only
        // grow.
        private static final long LEGACY_LAYOUT_CEILING = 1058897343291588607L;

        @Test
        void shouldProduceStrictlyIncreasingIds_whenCalledRapidlyInSequence() {
            // arrange
            var generator = new RandFlakeGenerator();
            var callCount = 1000;

            // act
            List<Long> decoded =
                    IntStream.range(0, callCount)
                            .mapToObj(i -> Long.parseLong(generator.generateRandflake(), 36))
                            .collect(Collectors.toList());

            // assert
            Assertions.assertThat(decoded).isSorted();
            Assertions.assertThat(decoded).doesNotHaveDuplicates();
        }

        @Test
        void shouldDecodeAboveLegacyLayoutCeiling_whenGeneratedFreshly() {
            // arrange
            var generator = new RandFlakeGenerator();

            // act
            var decoded = Long.parseLong(generator.generateRandflake(), 36);

            // assert
            Assertions.assertThat(decoded).isGreaterThan(LEGACY_LAYOUT_CEILING);
        }

        @Test
        void shouldRenderAsTwelveCharPositiveBase36String_whenGeneratedFreshly() {
            // arrange
            var generator = new RandFlakeGenerator();

            // act
            var id = generator.generateRandflake();
            var decoded = Long.parseLong(id, 36);

            // assert
            Assertions.assertThat(id).hasSize(12);
            Assertions.assertThat(decoded).isPositive();
        }

        @Test
        void shouldProduceAllDistinctIds_whenCalledConcurrentlyFromMultipleThreads()
                throws InterruptedException, ExecutionException {
            // arrange
            var threadCount = 8;
            var callsPerThread = 250;
            var totalCalls = threadCount * callsPerThread;
            var generator = new RandFlakeGenerator();
            var startGate = new CountDownLatch(1);
            Set<String> ids = ConcurrentHashMap.newKeySet();
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<?>> futures = new ArrayList<>();

            // act
            for (int t = 0; t < threadCount; t++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        startGate.await();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        return;
                                    }
                                    for (int i = 0; i < callsPerThread; i++) {
                                        ids.add(generator.generateRandflake());
                                    }
                                }));
            }
            startGate.countDown();
            executor.shutdown();
            var terminated = executor.awaitTermination(30, TimeUnit.SECONDS);
            for (Future<?> future : futures) {
                future.get();
            }

            // assert
            Assertions.assertThat(terminated).isTrue();
            Assertions.assertThat(ids).hasSize(totalCalls);
        }

        @Test
        void shouldProduceAllDistinctIds_whenInterleavedAcrossTwoSeparateInstances() {
            // arrange
            var first = new RandFlakeGenerator();
            var second = new RandFlakeGenerator();
            var callsPerInstance = 500;
            Set<String> ids = new HashSet<>();

            // act
            for (int i = 0; i < callsPerInstance; i++) {
                ids.add(first.generateRandflake());
                ids.add(second.generateRandflake());
            }

            // assert
            Assertions.assertThat(ids).hasSize(callsPerInstance * 2);
        }

        @Test
        void shouldDecodeToPositiveLong_whenGenerated() {
            // arrange
            var generator = new RandFlakeGenerator();
            var callCount = 100;

            // act
            List<Long> decoded =
                    IntStream.range(0, callCount)
                            .mapToObj(i -> Long.parseLong(generator.generateRandflake(), 36))
                            .collect(Collectors.toList());

            // assert
            Assertions.assertThat(decoded).allMatch(id -> id > 0);
        }
    }
}
