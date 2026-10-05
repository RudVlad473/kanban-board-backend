package com.vrudenko.kanban_board.activitylog;

import java.nio.ByteBuffer;
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
 * Re-verifies the dead-letter path's byte-fidelity and non-blocking guarantees under Avro; a
 * sibling of {@link ActivityLogDeadLetterE2ETest}, not a replacement.
 *
 * <p>Two poison shapes are asserted to reach {@code kanban.activity.dlt} byte-for-byte: a payload
 * with no valid Confluent magic byte (fails at framing, before the registry is consulted), and
 * valid Confluent framing carrying a schema id the registry never issued (a case JSON could not
 * produce).
 *
 * <p>Decisions: production {@code KafkaConsumerConfig} is deliberately left untouched. The
 * dead-letter path's {@code DelegatingByTypeSerializer} is generic over any deserialization-failure
 * payload shape, Avro included, because it dispatches on the record value's runtime class ({@code
 * byte[]}) and never inspects the bytes. An Avro-aware branch would be harmful: it would try to
 * re-encode a payload that just failed to decode, throwing inside the recovery path and destroying
 * the audit trail an operator needs most for exactly these messages.
 */
@SpringBootTest
@Tag("kafka")
class ActivityLogAvroDeadLetterE2ETest extends AbstractKafkaContainerTest {

    /** Confluent wire-format magic byte marking a Schema-Registry-framed payload. */
    private static final byte CONFLUENT_MAGIC_BYTE = 0x0;

    /**
     * A schema id far outside the few ids {@link
     * com.vrudenko.kanban_board.config.AvroSchemaRegistrar} registers (5 subjects, small sequential
     * ids), so the deserializer fails at schema resolution, not framing.
     */
    private static final int UNREGISTERED_SCHEMA_ID = 999_999_999;

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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "avro-dlt-probe-" + UUID.randomUUID());
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
     * Builds a valid Confluent wire-format payload: the magic byte, a 4-byte big-endian schema id
     * the registry never issued, then a random trailing discriminator.
     *
     * <p>The discriminator keeps every call's payload byte-unique: {@code kanban.activity.dlt} is
     * shared across the package's tests (the Spring context is cached), so byte-identical poison
     * payloads would both match {@link #awaitDeadLetterRecordMatching}'s exact-payload filter and
     * break its single-match assertion. The trailing bytes are never decoded: resolution fails on
     * the id lookup first.
     */
    private byte[] framedPayloadWithUnregisteredSchemaId() {
        var discriminator = randomId().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + 4 + discriminator.length)
                .put(CONFLUENT_MAGIC_BYTE)
                .putInt(UNREGISTERED_SCHEMA_ID)
                .put(discriminator)
                .array();
    }

    /**
     * Polls {@link KafkaTopics#ACTIVITY_DLT} until exactly one record whose value byte-equals
     * {@code expectedValue} has been seen, then returns it.
     *
     * <p>Raw arrays are compared, never a decoded string: decoding first would mask the re-encoding
     * bug this class exists to catch. Routing takes a few seconds (three attempts at a ~1s fixed
     * interval, see {@code KafkaConsumerConfig}); the 30s ceiling comfortably exceeds that.
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
    class UnframedPayloadTest {

        @Test
        void shouldDeadLetterWithByteFidelity_whenPayloadHasNoValidMagicByte() throws Exception {
            // arrange -- a deterministic framing failure: the first byte is `{` (0x7B), not the
            // Confluent magic byte 0x0, so KafkaAvroDeserializer rejects it before consulting the
            // registry. Distinct from every poison literal ActivityLogDeadLetterE2ETest uses.
            var poisonBytes =
                    "{\"type\":\"AvroSchemaRegistryPoison\"".getBytes(StandardCharsets.UTF_8);
            var key = randomId();

            // act
            publishRawBytes(poisonBytes, key);

            // assert -- compares the arrays directly; decoding to a String first would mask a
            // base64/re-encoding round trip, exactly the regression this assertion exists to catch.
            var deadLetteredValue = awaitDeadLetterRecordMatching(poisonBytes);
            Assertions.assertThat(deadLetteredValue).isEqualTo(poisonBytes);
        }
    }

    @Nested
    class UnregisteredSchemaIdTest {

        @Test
        void shouldDeadLetterWithByteFidelity_whenPayloadIsFramedButSchemaIdIsUnregistered()
                throws Exception {
            // arrange -- valid Confluent framing with an id the registry never issued: a failure
            // from the registry lookup itself, not the payload shape, must still dead-letter with
            // fidelity.
            var poisonBytes = framedPayloadWithUnregisteredSchemaId();
            var key = randomId();

            // act
            publishRawBytes(poisonBytes, key);

            // assert
            var deadLetteredValue = awaitDeadLetterRecordMatching(poisonBytes);
            Assertions.assertThat(deadLetteredValue).isEqualTo(poisonBytes);
        }
    }

    @Nested
    class NonBlockingTest {

        @Test
        void shouldStillPersistEvent_whenPublishedAfterRegistryAwarePoisonMessage()
                throws Exception {
            // arrange
            var boardId = randomId();
            var poisonBytes = framedPayloadWithUnregisteredSchemaId();
            var key = randomId();

            // act -- both records share the topic's single partition, so the well-formed event
            // behind the poison one is consumed only if the container advanced past the poisoned
            // offset instead of stalling.
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
}
