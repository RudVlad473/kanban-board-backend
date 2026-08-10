package com.vrudenko.kanban_board.activitylog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.event.ActivityEvent;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * SCHEMA-06's rehearsal: the last check before Phase 5 repoints the registry at a production
 * target. Reads every row this environment's real Postgres {@code activity_log} table actually
 * holds -- the durable historical record, not the disposable Kafka topic (04-RESEARCH.md Pitfall 2)
 * -- reconstructs each one back into the domain event that produced it via {@link
 * HistoricalActivityEventReconstructor}, and pushes it through the new Avro schemas end to end.
 *
 * <p><b>Read-only against the historical database.</b> This class opens no write transaction of its
 * own: {@link CorpusCheckAndRehearsalTest#shouldRehearseHistoricalCorpus_reportingSizeAndCoverage}
 * either reads existing rows through {@link ActivityLogRepository} or round-trips an
 * already-reconstructed event purely in memory (direct {@code KafkaAvroSerializer}/{@code
 * KafkaAvroDeserializer} calls against the registry, never touching a database). The one place a
 * write could sneak in is the final end-to-end sample, which republishes a handful of historical
 * events through the real topic and lets the real {@link ActivityLogConsumer} consume them -- that
 * IS a write, but a safe one: {@link ActivityLogRecorder#record} is idempotent on {@code eventId}
 * (its {@code existsByEventId} fast path), and every eventId republished here already has a row
 * under that exact id in the very database this class reads from, so the write is structurally a
 * no-op. Do not add an assertion here that inserts a row through any other path -- doing so would
 * silently turn this rehearsal from a safe read into a mutation against the only surviving
 * historical corpus (T-04-13).
 *
 * <p>Deliberately does NOT run under the {@code test} Spring profile: this class carries no {@code
 * spring.profiles.active} override, and the {@code rehearseHistoricalSchemas} Gradle task (unlike
 * {@code test}/{@code fastTest}) never sets that system property either -- so Spring resolves the
 * default profile's {@code application.properties}, whose datasource already points at a real
 * Postgres instance via the {@code DB_HOST}/{@code DB_NAME}/{@code DB_USER}/{@code DB_PASS}
 * environment variables the running application already uses. The Kafka broker and Schema Registry
 * are still the Testcontainers-managed Redpanda instance from {@link AbstractKafkaContainerTest} --
 * only the JPA datasource is the real one, which is exactly what lets {@link ActivityLogRepository}
 * read genuine historical rows instead of an empty, freshly-created H2 database.
 */
@SpringBootTest
@Tag("rehearsal")
@Tag("kafka")
class HistoricalSchemaRehearsalE2ETest extends AbstractKafkaContainerTest {

    private static final Logger log =
            LoggerFactory.getLogger(HistoricalSchemaRehearsalE2ETest.class);

    // A few hundred rows spanning every action present proves what an exhaustive pass would, at a
    // fraction of the runtime (T-04-16, Denial of Service via unbounded corpus scan).
    private static final int MAX_SAMPLE_ROWS_PER_ACTION = 100;

    // The end-to-end sample republishes real historical events and waits to see whether any of
    // them show up on the dead-letter topic. Generous relative to DefaultErrorHandler's ~1s x 3
    // retry policy (KafkaConsumerConfig) -- long enough that a genuine dead-lettering would
    // certainly have completed by the time this window closes.
    private static final Duration DEAD_LETTER_SETTLE_WINDOW = Duration.ofSeconds(15);

    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private ObjectMapper objectMapper;

    private HistoricalActivityEventReconstructor reconstructor;

    @BeforeEach
    void createReconstructor() {
        reconstructor = new HistoricalActivityEventReconstructor(objectMapper);
    }

    private KafkaAvroSerializer buildAvroSerializer() {
        var serializer = new KafkaAvroSerializer();
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url", getSchemaRegistryAddress());
        config.put("auto.register.schemas", false);
        config.put(
                "value.subject.name.strategy",
                "io.confluent.kafka.serializers.subject.RecordNameStrategy");
        serializer.configure(config, false);
        return serializer;
    }

    private KafkaAvroDeserializer buildAvroDeserializer() {
        var deserializer = new KafkaAvroDeserializer();
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url", getSchemaRegistryAddress());
        config.put("specific.avro.reader", true);
        deserializer.configure(config, false);
        return deserializer;
    }

    private KafkaConsumer<String, byte[]> buildRawDeadLetterConsumer() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "rehearsal-dlt-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        var consumer = new KafkaConsumer<String, byte[]>(props);
        consumer.subscribe(List.of(KafkaTopics.ACTIVITY_DLT));
        return consumer;
    }

    /**
     * Same tolerance rationale as {@link HistoricalActivityEventReconstructorTest}: Avro's {@code
     * timestamp-millis} logical type truncates to millisecond precision by design, so an exact
     * round-trip comparison on {@code timestamp} would be too strict. Every other field is compared
     * for exact equality.
     */
    private void assertFieldEqual(ActivityEvent expected, ActivityEvent actual) {
        Assertions.assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields("timestamp")
                .isEqualTo(expected);
        Assertions.assertThat(actual.timestamp())
                .isCloseTo(expected.timestamp(), Assertions.within(1, ChronoUnit.MILLIS));
    }

    @Nested
    class CorpusCheckAndRehearsalTest {

        @Test
        void shouldRehearseHistoricalCorpus_reportingSizeAndCoverage() throws Exception {
            // --- Step 1: corpus check -- must run first. A rehearsal that examines nothing must
            // not silently pass (T-04-14): report the actual corpus size and action coverage
            // unconditionally, then fail loudly rather than pass vacuously on zero rows.
            List<ActivityLogEntity> allRows = activityLogRepository.findAll();
            int rowCount = allRows.size();

            Set<ActivityAction> actionsPresent = EnumSet.noneOf(ActivityAction.class);
            Map<ActivityAction, List<ActivityLogEntity>> rowsByAction =
                    new EnumMap<>(ActivityAction.class);
            for (ActivityLogEntity row : allRows) {
                actionsPresent.add(row.getAction());
                rowsByAction.computeIfAbsent(row.getAction(), a -> new ArrayList<>()).add(row);
            }

            log.info(
                    "SCHEMA-06 rehearsal corpus: {} historical row(s) across {} of {} "
                            + "ActivityAction value(s): {}",
                    rowCount,
                    actionsPresent.size(),
                    ActivityAction.values().length,
                    actionsPresent);

            if (rowCount == 0) {
                Assertions.fail(
                        "SCHEMA-06 UNVERIFIED: no historical rows found in activity_log in this "
                                + "environment. The rehearsal examined nothing, so it must not "
                                + "report a pass -- rerun against an environment (e.g. the local "
                                + "docker-compose stack) that holds real historical activity_log "
                                + "data.");
            }

            Set<ActivityAction> missingActions = EnumSet.allOf(ActivityAction.class);
            missingActions.removeAll(actionsPresent);
            if (!missingActions.isEmpty()) {
                log.warn(
                        "SCHEMA-06 PARTIAL: this environment's corpus does not cover every "
                                + "ActivityAction. Missing: {}. Verified only for the actions "
                                + "actually present: {}.",
                        missingActions,
                        actionsPresent);
            }

            // Cap each action's group at MAX_SAMPLE_ROWS_PER_ACTION -- "a few hundred rows
            // spanning every action present" (T-04-16), not an unbounded scan.
            rowsByAction.replaceAll(
                    (action, rows) ->
                            rows.size() > MAX_SAMPLE_ROWS_PER_ACTION
                                    ? rows.subList(0, MAX_SAMPLE_ROWS_PER_ACTION)
                                    : rows);

            // --- Step 2: per-row reconstruct, then encode/decode through the real registry.
            // Avro's build() is the strictness gate SCHEMA-06 exists to exercise -- a historical
            // row that cannot fill every required field fails here, and this rehearsal lets it
            // fail rather than defensively working around it.
            int roundTripped = 0;
            try (KafkaAvroSerializer serializer = buildAvroSerializer();
                    KafkaAvroDeserializer deserializer = buildAvroDeserializer()) {
                for (List<ActivityLogEntity> rows : rowsByAction.values()) {
                    for (ActivityLogEntity row : rows) {
                        ActivityEvent reconstructed = reconstructor.reconstruct(row);
                        SpecificRecord avroRecord = activityEventAvroMapper.toAvro(reconstructed);

                        byte[] encoded = serializer.serialize(KafkaTopics.ACTIVITY, avroRecord);
                        Object decoded = deserializer.deserialize(KafkaTopics.ACTIVITY, encoded);
                        ActivityEvent roundTripped2 =
                                activityEventAvroMapper.toDomain((SpecificRecord) decoded);

                        assertFieldEqual(reconstructed, roundTripped2);
                        roundTripped++;
                    }
                }
            }
            log.info(
                    "SCHEMA-06 rehearsal: {} historical row(s) sampled and round-tripped through "
                            + "the new Avro schemas with zero required-field or strictness errors.",
                    roundTripped);

            // --- Step 3: a small end-to-end sample, one per action present, through the real
            // topic. Safe against the real database: ActivityLogRecorder is idempotent on
            // eventId, so republishing an event this environment already recorded writes nothing
            // new -- see this class's Javadoc.
            List<ActivityEvent> endToEndSample = new ArrayList<>();
            for (ActivityAction action : actionsPresent) {
                ActivityLogEntity firstRow = rowsByAction.get(action).getFirst();
                endToEndSample.add(reconstructor.reconstruct(firstRow));
            }
            Set<String> sampledEventIds = new HashSet<>();
            for (ActivityEvent event : endToEndSample) {
                sampledEventIds.add(event.eventId().toString());
            }

            for (ActivityEvent event : endToEndSample) {
                sendAndAwaitAck(event);
            }

            try (KafkaConsumer<String, byte[]> dltConsumer = buildRawDeadLetterConsumer()) {
                Awaitility.await()
                        .pollDelay(DEAD_LETTER_SETTLE_WINDOW)
                        .atMost(DEAD_LETTER_SETTLE_WINDOW.plusSeconds(15))
                        .untilAsserted(
                                () -> {
                                    var records = dltConsumer.poll(Duration.ofMillis(500));
                                    for (var record : records) {
                                        Assertions.assertThat(sampledEventIds)
                                                .doesNotContain(record.key());
                                    }
                                });
            }
            log.info(
                    "SCHEMA-06 rehearsal: {} historical event(s) republished end-to-end through "
                            + "the real topic, none dead-lettered.",
                    endToEndSample.size());
        }
    }
}
