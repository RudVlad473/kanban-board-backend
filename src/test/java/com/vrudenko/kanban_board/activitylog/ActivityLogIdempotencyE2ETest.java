package com.vrudenko.kanban_board.activitylog;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real-broker proof that a redelivered {@code eventId} yields exactly one {@code activity_log} row
 * and never reaches {@link KafkaTopics#ACTIVITY_DLT}.
 *
 * <p>The unique {@code event_id} constraint, not the {@code existsByEventId} fast path, arbitrates
 * a genuine concurrent race.
 *
 * <p>One partition and one consumer thread make broker delivery strictly sequential, so the
 * concurrency case bypasses the transport and drives {@link ActivityLogRecorder#record} from two
 * threads; every other case goes through the real broker.
 */
@SpringBootTest
@Tag("kafka")
class ActivityLogIdempotencyE2ETest extends AbstractKafkaContainerTest {

    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private ActivityLogRecorder activityLogRecorder;

    private String randomId() {
        return UUID.randomUUID().toString();
    }

    private ActivityLogEntity buildActivityLogEntity(
            String eventId, String boardId, String userId, Instant timestamp) {
        var entity = new ActivityLogEntity();
        entity.setBoardId(boardId);
        entity.setUserId(userId);
        entity.setAction(ActivityAction.TASK_CREATED);
        entity.setDetail("{}");
        entity.setEventId(eventId);
        entity.setCreatedAt(timestamp);
        return entity;
    }

    /**
     * Publishes {@code event} twice, waits for its row, then publishes a sentinel event and waits
     * for the sentinel's row.
     *
     * <p>The topic has one partition and one consumer, so the sentinel's arrival proves the
     * consumer drained past both copies: the settle signal that makes a negative assertion
     * ("exactly one row", "no dead-letter record") safe instead of a race against an unprocessed
     * duplicate.
     */
    private void publishTwiceThenAwaitSettle(TaskMovedEvent event) throws Exception {
        sendAndAwaitAck(event);
        sendAndAwaitAck(event);

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> activityLogRepository.existsByEventId(event.eventId()));

        var sentinel =
                new TaskMovedEvent(
                        UUID.randomUUID().toString(),
                        randomId(),
                        randomId(),
                        randomId(),
                        randomId(),
                        randomId(),
                        Instant.now());
        sendAndAwaitAck(sentinel);
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> activityLogRepository.existsByEventId(sentinel.eventId()));
    }

    private KafkaConsumer<String, byte[]> buildRawDeadLetterConsumer() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        var consumer = new KafkaConsumer<String, byte[]>(props);
        consumer.subscribe(List.of(KafkaTopics.ACTIVITY_DLT));
        return consumer;
    }

    /**
     * Polls {@link KafkaTopics#ACTIVITY_DLT} for {@code window} and returns every record value
     * seen.
     *
     * <p>The topic is shared across the package's test classes (the context is cached), so callers
     * must filter for their own {@code eventId}.
     */
    private List<byte[]> pollDeadLetterValues(Duration window) {
        var values = new ArrayList<byte[]>();
        try (var consumer = buildRawDeadLetterConsumer()) {
            var deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                var records = consumer.poll(Duration.ofMillis(500));
                records.forEach(record -> values.add(record.value()));
            }
        }
        return values;
    }

    @Nested
    class RedeliveryTest {

        @Test
        void shouldPersistExactlyOneRow_whenEventRedeliveredThroughRealBroker() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var event =
                    new TaskMovedEvent(
                            eventId,
                            randomId(),
                            randomId(),
                            randomId(),
                            randomId(),
                            randomId(),
                            Instant.now());

            // act
            publishTwiceThenAwaitSettle(event);

            // assert -- the sentinel settle signal above already proves the consumer drained
            // past both copies, so this negative ("exactly one", not "at least one") is safe.
            var rows =
                    activityLogRepository.findAll().stream()
                            .filter(row -> row.getEventId().equals(eventId))
                            .toList();
            Assertions.assertThat(rows).hasSize(1);
        }

        @Test
        void shouldLeaveDeadLetterTopicEmpty_whenEventIsRedeliveredNotPoison() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var event =
                    new TaskMovedEvent(
                            eventId,
                            randomId(),
                            randomId(),
                            randomId(),
                            randomId(),
                            randomId(),
                            Instant.now());

            // act
            publishTwiceThenAwaitSettle(event);

            // assert -- distinguishes real idempotency from a duplicate that merely exhausted its
            // three retries into the dead-letter topic, which the row count alone cannot tell
            // apart.
            var deadLetterValues = pollDeadLetterValues(Duration.ofSeconds(5));
            var matchingEventId =
                    deadLetterValues.stream()
                            // A tombstone (null value) from an unrelated test can sit on this
                            // shared topic and never carries this eventId: filter it, don't decode.
                            .filter(Objects::nonNull)
                            // Decode as UTF-8: the producer writes UTF-8 JSON, and a
                            // platform-default charset (windows-1252 locally, UTF-8 on CI) would
                            // let this negative assertion pass for the wrong reason.
                            .filter(
                                    value ->
                                            new String(value, StandardCharsets.UTF_8)
                                                    .contains(eventId.toString()))
                            .toList();
            Assertions.assertThat(matchingEventId).isEmpty();
        }
    }

    @Nested
    class ConcurrentRecordTest {

        @Test
        void shouldPersistExactlyOneRow_whenTwoThreadsRecordSameEventIdConcurrently()
                throws InterruptedException {
            // arrange -- bypasses the Kafka transport: broker delivery is strictly sequential, so
            // only concurrent calls to the recorder reach the exists-check/insert race window and
            // prove the unique constraint, not the exists-check fast path, arbitrates it.
            var eventId = UUID.randomUUID().toString();
            var timestamp = Instant.now();
            var firstEntity = buildActivityLogEntity(eventId, randomId(), randomId(), timestamp);
            var secondEntity = buildActivityLogEntity(eventId, randomId(), randomId(), timestamp);

            var startGate = new CountDownLatch(1);
            var firstFailure = new AtomicReference<Throwable>();
            var secondFailure = new AtomicReference<Throwable>();
            ExecutorService executor = Executors.newFixedThreadPool(2);

            // act
            try {
                // The returned Future is dropped, not awaited: awaiting would serialize the two
                // submissions and destroy the race window.
                //
                // FutureReturnValueIgnored's failure channel is covered below: each lambda catches
                // Throwable into firstFailure/secondFailure, asserted after awaitTermination. Each
                // submit has its own block so both locals can share the name `unused` that
                // ErrorProne's suggested fix expects.
                {
                    Future<?> unused =
                            executor.submit(
                                    () -> {
                                        try {
                                            startGate.await();
                                            activityLogRecorder.record(firstEntity);
                                        } catch (Throwable t) {
                                            firstFailure.set(t);
                                        }
                                    });
                }
                {
                    Future<?> unused =
                            executor.submit(
                                    () -> {
                                        try {
                                            startGate.await();
                                            activityLogRecorder.record(secondEntity);
                                        } catch (Throwable t) {
                                            secondFailure.set(t);
                                        }
                                    });
                }

                startGate.countDown();
                executor.shutdown();
                Assertions.assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            // assert
            Assertions.assertThat(firstFailure.get()).isNull();
            Assertions.assertThat(secondFailure.get()).isNull();
            var rows =
                    activityLogRepository.findAll().stream()
                            .filter(row -> row.getEventId().equals(eventId))
                            .toList();
            Assertions.assertThat(rows).hasSize(1);
        }
    }

    @Nested
    class FreshEventTest {

        @Test
        void shouldInsertRow_whenEventIdIsNeverSeenBefore() {
            // arrange -- control case: without it, a recorder that silently dropped everything
            // would still pass the redelivery and concurrency cases above.
            var eventId = UUID.randomUUID().toString();
            var entity = buildActivityLogEntity(eventId, randomId(), randomId(), Instant.now());

            // act
            activityLogRecorder.record(entity);

            // assert
            var rows =
                    activityLogRepository.findAll().stream()
                            .filter(row -> row.getEventId().equals(eventId))
                            .toList();
            Assertions.assertThat(rows).hasSize(1);
        }
    }
}
