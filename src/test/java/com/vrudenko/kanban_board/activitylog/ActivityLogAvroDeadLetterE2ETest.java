package com.vrudenko.kanban_board.activitylog;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Re-verifies the dead-letter path's byte-fidelity and non-blocking guarantees (SCHEMA-05, T-04-09,
 * T-04-10) now that the main pipeline serializes as Avro instead of JSON. This class is a sibling
 * of {@link ActivityLogDeadLetterE2ETest}, not a replacement -- that class keeps proving the
 * framing-level poison shapes it always has, unchanged, against the same Redpanda broker.
 *
 * <p>Two genuinely distinct poison shapes are exercised. The first -- a payload with no valid
 * Confluent magic byte -- fails at <em>framing</em>, before the deserializer ever consults the
 * registry; the JSON-era test already covered the analogous case, and this class carries the
 * equivalent forward so the Avro path is proven, not assumed, to behave the same way. The second --
 * a payload in genuinely valid Confluent wire format (correct magic byte, correct 4-byte schema-id
 * framing) carrying a schema id the registry has never issued -- is the case the JSON-era test
 * could not produce at all, since JSON has no registry-mediated resolution step to fail. Both are
 * asserted to reach {@code kanban.activity.dlt} with their bytes byte-for-byte intact.
 *
 * <p>Per this plan's design_alternatives, the production {@code KafkaConsumerConfig} is
 * deliberately left untouched: the dead-letter path's {@code DelegatingByTypeSerializer} is already
 * generic over <em>any</em> deserialization-failure payload shape, Avro included, since it
 * dispatches purely on the record value's runtime class ({@code byte[]}) and never inspects the
 * bytes themselves. Giving the recoverer an Avro-aware branch would be actively harmful: it would
 * attempt to re-encode a payload that just failed to decode, throwing inside the recovery path and
 * destroying the one audit trail an operator needs most for exactly these messages.
 */
@SpringBootTest
class ActivityLogAvroDeadLetterE2ETest extends AbstractKafkaContainerTest {

    /** Confluent wire-format magic byte marking a Schema-Registry-framed payload. */
    private static final byte CONFLUENT_MAGIC_BYTE = 0x0;

    /**
     * Deliberately far outside the handful of ids {@link
     * com.vrudenko.kanban_board.config.AvroSchemaRegistrar} actually registers (5 subjects, issued
     * small sequential ids by a freshly-started registry) -- guaranteed to be an id the registry
     * has never handed out, so the deserializer fails at schema <em>resolution</em>, not at
     * framing.
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
     * Builds a payload in genuinely valid Confluent wire format -- the magic byte, then a 4-byte
     * big-endian schema id the registry has never issued, then a fresh-random-per-call trailing
     * discriminator. The discriminator exists solely to keep every call's return value byte-unique:
     * {@code kanban.activity.dlt} is shared across every class and every test method in this
     * package (the Spring context is cached), so two byte-identical poison payloads published by
     * two different test methods would otherwise both match {@link
     * #awaitDeadLetterRecordMatching}'s exact-payload filter and break its single-match assertion.
     * The trailing bytes are never decoded as Avro: resolution fails on the id lookup before the
     * deserializer ever attempts to read them.
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
     * {@code expectedValue} has been seen, then returns that value. Comparing the raw arrays (never
     * a decoded string) is load-bearing: a decode step before comparing would mask exactly the
     * re-encoding bug this class exists to catch. The retry policy is three attempts at a ~1s fixed
     * interval (see {@code KafkaConsumerConfig}), so routing is expected within a few seconds; the
     * 30s ceiling comfortably exceeds that.
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
            // arrange -- a genuine, deterministic framing failure: the first byte is `{` (0x7B),
            // never the Confluent magic byte 0x0, so KafkaAvroDeserializer rejects it before ever
            // consulting the registry. Distinct from every poison literal
            // ActivityLogDeadLetterE2ETest
            // already uses on this same shared topic.
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
            // arrange -- the poison shape the JSON-era test could not produce: genuinely valid
            // Confluent framing (correct magic byte, correct schema-id width) but an id the
            // registry has never issued. This proves a failure originating from the *registry
            // lookup* itself, not from the payload's shape, still lands in the dead-letter topic
            // with fidelity.
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
            // published behind the registry-aware poison one can only be consumed if the
            // container advanced past the poisoned offset instead of stalling on it (T-04-10).
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
