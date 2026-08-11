package com.vrudenko.kanban_board.activitylog;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real-broker proof of the dead-letter path's routing, payload fidelity and non-blocking behaviour
 * (RELY-01, RELY-02, D-06). The poison is genuinely unparseable JSON published as raw bytes through
 * a standalone {@code kafka-clients} producer -- never a rigged, test-only failure hook -- so a
 * dead-lettered record here proves the pipeline isolates real bad data, not that a hook works.
 */
@SpringBootTest
@Tag("kafka")
class ActivityLogDeadLetterE2ETest extends AbstractKafkaContainerTest {

    @Autowired private ActivityLogRepository activityLogRepository;

    private String randomId() {
        return UUID.randomUUID().toString();
    }

    private KafkaProducer<String, byte[]> buildRawByteProducer() {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaProducer<>(props);
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

    private void publishRawBytes(byte[] payload, String key) throws Exception {
        try (var producer = buildRawByteProducer()) {
            producer.send(new ProducerRecord<>(KafkaTopics.ACTIVITY, key, payload)).get();
        }
    }

    /**
     * Polls {@link KafkaTopics#ACTIVITY_DLT} until exactly one record whose value byte-equals
     * {@code expectedValue} has been seen, then returns that value. Comparing the raw arrays (never
     * a decoded string) is what {@link DeadLetterFidelityTest} depends on: a decode step before
     * comparing would mask exactly the re-encoding bug this class exists to catch. The topic is
     * shared across every test class in this package (the Spring/Testcontainers context is cached
     * across the whole {@code activitylog} package), so matching must be scoped to this exact
     * payload rather than assuming the topic starts empty. The retry policy is three attempts at a
     * ~1s fixed interval (see {@code KafkaConsumerConfig}), so routing is expected within a few
     * seconds; the 30s ceiling comfortably exceeds that.
     */
    private byte[] awaitDeadLetterRecordMatching(byte[] expectedValue) {
        var matches = new ArrayList<byte[]>();
        try (var consumer = buildRawDeadLetterConsumer()) {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var records = consumer.poll(Duration.ofMillis(500));
                                records.forEach(
                                        record -> {
                                            if (Arrays.equals(record.value(), expectedValue)) {
                                                matches.add(record.value());
                                            }
                                        });
                                Assertions.assertThat(matches).hasSize(1);
                            });
        }
        return matches.getFirst();
    }

    @Nested
    class DeadLetterRoutingTest {

        @Test
        void shouldRouteMalformedPayloadToDeadLetterTopic_whenPayloadIsUnparseableJson()
                throws Exception {
            // arrange -- an unterminated JSON object: a genuine, deterministic parse failure in
            // the delegate JSON deserializer, never a trusted-packages rejection (which would
            // also fire for a perfectly valid event and would mean the whole pipeline is broken,
            // not correctly isolating one bad record).
            var poisonBytes = "{\"type\":\"TaskMovedEvent\"".getBytes(StandardCharsets.UTF_8);
            var key = randomId();

            // act
            publishRawBytes(poisonBytes, key);

            // assert
            var deadLetteredValue = awaitDeadLetterRecordMatching(poisonBytes);
            Assertions.assertThat(deadLetteredValue).isNotNull();
        }
    }

    @Nested
    class DeadLetterFidelityTest {

        @Test
        void shouldPreserveOriginalBytes_whenMalformedPayloadIsDeadLettered() throws Exception {
            // arrange
            var poisonBytes =
                    "{\"type\":\"TaskMovedEvent\",\"boardId\":".getBytes(StandardCharsets.UTF_8);
            var key = randomId();

            // act
            publishRawBytes(poisonBytes, key);

            // assert -- compares the arrays directly; decoding to a String first would mask a
            // base64/re-encoding round trip, which is exactly the failure mode this test exists
            // to catch (a byte-preserving dead-letter template turning back into a JSON-wrapping
            // one).
            var deadLetteredValue = awaitDeadLetterRecordMatching(poisonBytes);
            Assertions.assertThat(deadLetteredValue).isEqualTo(poisonBytes);
        }
    }

    @Nested
    class NonBlockingTest {

        @Test
        void shouldStillPersistEvent_whenPublishedAfterMalformedPayload() throws Exception {
            // arrange
            var boardId = randomId();
            var poisonBytes =
                    "{\"type\":\"TaskMovedEvent\",\"nonBlocking\":true"
                            .getBytes(StandardCharsets.UTF_8);
            var key = randomId();

            // act -- since both records share the topic's single partition, the well-formed
            // event published behind the poison one can only be consumed if the container
            // advanced past the poisoned offset instead of stalling on it (RELY-01).
            publishRawBytes(poisonBytes, key);
            var wellFormedEventId = UUID.randomUUID().toString();
            var wellFormedEvent =
                    new TaskMovedEvent(
                            wellFormedEventId,
                            randomId(),
                            boardId,
                            randomId(),
                            randomId(),
                            randomId(),
                            Instant.now());
            sendAndAwaitAck(wellFormedEvent);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .until(() -> activityLogRepository.existsByEventId(wellFormedEventId));

            var rowsForBoard =
                    activityLogRepository.findAll().stream()
                            .filter(row -> row.getBoardId().equals(boardId))
                            .toList();
            Assertions.assertThat(rowsForBoard).hasSize(1);
        }
    }

    @Nested
    class TombstoneTest {

        @Test
        void shouldNotPersistOrStall_whenTombstoneRecordIsPublished() throws Exception {
            // arrange
            var boardId = randomId();
            var key = randomId();

            // act -- a tombstone: non-null key, null value. Whether it is silently ignored or
            // itself routed to the dead-letter topic is an implementation consequence, not
            // something RELY-01/RELY-02 specify; only non-blocking is asserted here.
            publishRawBytes(null, key);
            var sentinelEventId = UUID.randomUUID().toString();
            var sentinelEvent =
                    new TaskMovedEvent(
                            sentinelEventId,
                            randomId(),
                            boardId,
                            randomId(),
                            randomId(),
                            randomId(),
                            Instant.now());
            sendAndAwaitAck(sentinelEvent);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .until(() -> activityLogRepository.existsByEventId(sentinelEventId));

            var rowsForBoard =
                    activityLogRepository.findAll().stream()
                            .filter(row -> row.getBoardId().equals(boardId))
                            .toList();
            Assertions.assertThat(rowsForBoard).hasSize(1);
        }
    }
}
