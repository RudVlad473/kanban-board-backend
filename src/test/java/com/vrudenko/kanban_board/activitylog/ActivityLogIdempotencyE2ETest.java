package com.vrudenko.kanban_board.activitylog;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Real-broker proof that a redelivered {@code eventId} produces exactly one {@code activity_log}
 * row (TEST-02, ACTLOG-03) and never reaches {@link KafkaTopics#ACTIVITY_DLT} (D-05), and that the
 * database's unique {@code event_id} constraint -- not the {@code existsByEventId} fast path -- is
 * what arbitrates a genuine concurrent race. One partition and one consumer thread make broker
 * delivery strictly sequential (D-08), so the concurrency case bypasses the transport and drives
 * {@link ActivityLogRecorder#record} directly from two threads; every other case here goes through
 * the real broker.
 */
@SpringBootTest
class ActivityLogIdempotencyE2ETest extends AbstractKafkaContainerTest {

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private ActivityLogRecorder activityLogRecorder;

    private String randomId() {
        return UUID.randomUUID().toString();
    }

    private ActivityLogEntity buildActivityLogEntity(
            UUID eventId, String boardId, String userId, Instant timestamp) {
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
     * Publishes {@code event} twice, waits for its row to appear, then publishes a distinct
     * sentinel event and waits for the sentinel's row to appear. The topic has one partition and
     * one consumer, so the sentinel's arrival proves the consumer has drained past both copies of
     * {@code event} -- the settle signal that makes a subsequent negative assertion ("exactly one
     * row", "no dead-letter record") safe instead of a race against an unprocessed duplicate.
     */
    private void publishTwiceThenAwaitSettle(TaskMovedEvent event) {
        kafkaTemplate.send(KafkaTopics.ACTIVITY, event.eventId().toString(), event);
        kafkaTemplate.send(KafkaTopics.ACTIVITY, event.eventId().toString(), event);

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> activityLogRepository.existsByEventId(event.eventId()));

        var sentinel =
                new TaskMovedEvent(
                        UUID.randomUUID(),
                        randomId(),
                        randomId(),
                        randomId(),
                        randomId(),
                        randomId(),
                        Instant.now());
        kafkaTemplate.send(KafkaTopics.ACTIVITY, sentinel.eventId().toString(), sentinel);
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
     * seen. The topic is shared across every test class in this package (the Spring/Testcontainers
     * context is cached across the whole {@code activitylog} package), so a caller must filter the
     * returned values for its own {@code eventId} rather than assume the topic starts empty.
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
        void shouldPersistExactlyOneRow_whenEventRedeliveredThroughRealBroker() {
            // arrange
            var eventId = UUID.randomUUID();
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
        void shouldLeaveDeadLetterTopicEmpty_whenEventIsRedeliveredNotPoison() {
            // arrange
            var eventId = UUID.randomUUID();
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

            // assert -- this is what distinguishes real idempotency from a duplicate that merely
            // exhausted its three retries into the dead-letter topic (D-05); the row count alone
            // cannot tell the two apart.
            var deadLetterValues = pollDeadLetterValues(Duration.ofSeconds(5));
            var matchingEventId =
                    deadLetterValues.stream()
                            // A tombstone (null value) can legitimately sit on this shared topic
                            // from an unrelated test (see ActivityLogDeadLetterE2ETest's
                            // TombstoneTest) -- it can never carry this eventId, so it is
                            // filtered out rather than decoded.
                            .filter(Objects::nonNull)
                            // The producer writes activity events as UTF-8 JSON, so the decode
                            // charset here is a known property of the data, not a guess -- a
                            // platform-default decode would silently mis-match on a non-UTF-8
                            // default locale/charset (windows-1252 locally, UTF-8 on CI) and let
                            // this negative assertion pass for the wrong reason.
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
            // arrange -- bypasses the Kafka transport deliberately: with one partition and one
            // consumer thread, broker delivery is strictly sequential, so only a direct,
            // concurrent call to the recorder can reach the exists-check/insert race window and
            // prove the database's unique constraint -- not just the exists-check fast path -- is
            // what arbitrates it (ACTLOG-03 concurrency probe).
            var eventId = UUID.randomUUID();
            var timestamp = Instant.now();
            var firstEntity = buildActivityLogEntity(eventId, randomId(), randomId(), timestamp);
            var secondEntity = buildActivityLogEntity(eventId, randomId(), randomId(), timestamp);

            var startGate = new CountDownLatch(1);
            var firstFailure = new AtomicReference<Throwable>();
            var secondFailure = new AtomicReference<Throwable>();
            ExecutorService executor = Executors.newFixedThreadPool(2);

            // act
            try {
                executor.submit(
                        () -> {
                            try {
                                startGate.await();
                                activityLogRecorder.record(firstEntity);
                            } catch (Throwable t) {
                                firstFailure.set(t);
                            }
                        });
                executor.submit(
                        () -> {
                            try {
                                startGate.await();
                                activityLogRecorder.record(secondEntity);
                            } catch (Throwable t) {
                                secondFailure.set(t);
                            }
                        });

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
            var eventId = UUID.randomUUID();
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
