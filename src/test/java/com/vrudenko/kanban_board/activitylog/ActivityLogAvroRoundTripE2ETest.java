package com.vrudenko.kanban_board.activitylog;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The Phase 4 tracer's end-to-end proof: a real {@link TaskMovedEvent} published through the real
 * producer travels as Avro binary with a registry-resolved schema id, is deserialized back into a
 * domain event by {@code ActivityEventAvroMapper}, and lands as an {@code activity_log} row -- with
 * the existing exhaustive switch downstream of it completely unaware anything changed (SCHEMA-02).
 */
@SpringBootTest
class ActivityLogAvroRoundTripE2ETest extends AbstractKafkaContainerTest {

    // Confluent's wire format: 1 magic byte + 4-byte schema id, ahead of the Avro binary payload.
    private static final int CONFLUENT_WIRE_PREFIX_LENGTH = 5;
    private static final byte CONFLUENT_MAGIC_BYTE = 0;

    @Autowired private ActivityLogRepository activityLogRepository;

    private String randomId() {
        return UUID.randomUUID().toString();
    }

    private KafkaConsumer<String, byte[]> buildRawActivityConsumer() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "avro-wire-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // The key deserializer must match what the producer actually writes (a String, per
        // spring.kafka.producer.key-serializer) -- deserializing it as byte[] instead would make
        // every key comparison below silently and permanently false, never a cast exception,
        // since Object.equals(Object) accepts any type without complaint.
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        var consumer = new KafkaConsumer<String, byte[]>(props);
        consumer.subscribe(List.of(KafkaTopics.ACTIVITY));
        return consumer;
    }

    /**
     * Polls {@link KafkaTopics#ACTIVITY} with a plain byte-array {@code kafka-clients} consumer --
     * bypassing the Avro deserializer entirely -- until a record keyed by {@code key} is seen, then
     * returns its raw value bytes. The topic is shared across every test class in this package (the
     * Spring/Testcontainers context is cached across the whole {@code activitylog} package), so
     * matching is scoped by key rather than assuming the topic starts empty.
     */
    private byte[] awaitRawValueForKey(String key) {
        var matches = new ArrayList<byte[]>();
        try (var consumer = buildRawActivityConsumer()) {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var records = consumer.poll(Duration.ofMillis(500));
                                records.forEach(
                                        record -> {
                                            if (key.equals(record.key())) {
                                                matches.add(record.value());
                                            }
                                        });
                                Assertions.assertThat(matches).isNotEmpty();
                            });
        }
        return matches.getFirst();
    }

    @Nested
    class FullRoundTripTest {

        @Test
        void
                shouldPersistMatchingActivityLogRow_whenTaskMovedEventPublishedThroughRealAvroPipeline()
                        throws Exception {
            // arrange
            var eventId = UUID.randomUUID();
            var taskId = randomId();
            var sourceColumnId = randomId();
            var targetColumnId = randomId();
            var event =
                    new TaskMovedEvent(
                            eventId,
                            randomId(),
                            randomId(),
                            taskId,
                            sourceColumnId,
                            targetColumnId,
                            Instant.now());

            // act
            sendAndAwaitAck(event);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var rows =
                                        activityLogRepository.findAll().stream()
                                                .filter(row -> row.getEventId().equals(eventId))
                                                .toList();
                                Assertions.assertThat(rows).hasSize(1);
                                var row = rows.getFirst();
                                Assertions.assertThat(row.getAction())
                                        .isEqualTo(ActivityAction.TASK_MOVED);
                                Assertions.assertThat(row.getDetail())
                                        .isEqualTo(
                                                "{\"taskId\":\""
                                                        + taskId
                                                        + "\",\"sourceColumnId\":\""
                                                        + sourceColumnId
                                                        + "\",\"targetColumnId\":\""
                                                        + targetColumnId
                                                        + "\"}");
                            });
        }
    }

    @Nested
    class WireFormatTest {

        @Test
        void shouldEncodeAsGenuineAvro_whenTaskMovedEventPublishedThroughRealPipeline()
                throws Exception {
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
            sendAndAwaitAck(event);
            var rawValue = awaitRawValueForKey(eventId.toString());

            // assert -- this is what makes the class a genuine cutover proof rather than a "the
            // pipeline still works" test: a silent fallback to JSON would still pass every other
            // assertion in this class, but a JSON payload always starts with '{' (0x7B), never the
            // Confluent magic byte 0.
            Assertions.assertThat(rawValue.length).isGreaterThan(CONFLUENT_WIRE_PREFIX_LENGTH);
            Assertions.assertThat(rawValue[0]).isEqualTo(CONFLUENT_MAGIC_BYTE);

            var schemaId = ByteBuffer.wrap(rawValue, 1, 4).getInt();
            Assertions.assertThat(schemaId).isPositive();
        }
    }
}
