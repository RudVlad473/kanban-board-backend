package com.vrudenko.kanban_board.activitylog;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The tracer's end-to-end proof: a real TaskMovedEvent travels as Avro binary with a
 * registry-resolved schema id, is deserialized by ActivityEventAvroMapper, and lands as an
 * activity_log row, with the downstream exhaustive switch unaware anything changed.
 */
@SpringBootTest
@Tag("kafka")
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
        // The key deserializer must match what the producer writes (a String, per
        // spring.kafka.producer.key-serializer): byte[] would make every key comparison silently
        // false, never a cast exception, since Object.equals accepts any type.
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        var consumer = new KafkaConsumer<String, byte[]>(props);
        consumer.subscribe(List.of(KafkaTopics.ACTIVITY));
        return consumer;
    }

    /**
     * Polls KafkaTopics.ACTIVITY with a plain byte-array consumer, bypassing the Avro
     * deserializer, until a record keyed by key is seen, then returns its raw value bytes.
     *
     * The topic is shared across the package's test classes (the context is cached), so matching
     * is scoped by key, not by assuming an empty topic.
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
            var eventId = UUID.randomUUID().toString();
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
            sendAndAwaitAck(event);
            var rawValue = awaitRawValueForKey(eventId.toString());

            // assert -- what makes this a cutover proof: a silent fallback to JSON would pass every
            // other assertion here, but a JSON payload starts with '{' (0x7B), never magic byte 0.
            Assertions.assertThat(rawValue.length).isGreaterThan(CONFLUENT_WIRE_PREFIX_LENGTH);
            Assertions.assertThat(rawValue[0]).isEqualTo(CONFLUENT_MAGIC_BYTE);

            var schemaId = ByteBuffer.wrap(rawValue, 1, 4).getInt();
            Assertions.assertThat(schemaId).isPositive();
        }
    }
}
