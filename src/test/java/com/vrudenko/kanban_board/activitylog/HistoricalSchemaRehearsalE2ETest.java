package com.vrudenko.kanban_board.activitylog;

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

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.event.ActivityEvent;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
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
 * Rehearses the historical corpus against the new Avro schemas before the registry is repointed at
 * a production target.
 *
 * <p>It reads every row of this environment's real Postgres {@code activity_log} table (the durable
 * record, not the disposable Kafka topic), reconstructs each into its domain event via {@link
 * HistoricalActivityEventReconstructor}, and pushes it through the schemas end to end.
 *
 * <p>Decisions:
 *
 * <ul>
 *   <li>Read-only against the historical database: this class opens no write transaction. {@link
 *       CorpusCheckAndRehearsalTest#shouldRehearseHistoricalCorpus_reportingSizeAndCoverage} reads
 *       rows through {@link ActivityLogRepository} or round-trips a reconstructed event in memory
 *       (direct {@code KafkaAvroSerializer}/{@code KafkaAvroDeserializer} calls against the
 *       registry). The one possible write is the final end-to-end sample, which republishes a
 *       handful of historical events for the real {@link ActivityLogConsumer}; it is structurally a
 *       no-op because {@link ActivityLogRecorder#record} is idempotent on {@code eventId} (its
 *       {@code existsByEventId} fast path) and every republished eventId already has a row in the
 *       database this class reads. Do not add an assertion that inserts a row through any other
 *       path: it would turn the rehearsal from a safe read into a mutation against the only
 *       surviving historical corpus.
 *   <li>Does not run under the {@code test} Spring profile: it carries no {@code
 *       spring.profiles.active} override, and the {@code rehearseHistoricalSchemas} Gradle task
 *       (unlike {@code test}/{@code fastTest}) never sets that property either, so Spring resolves
 *       the default profile's {@code application.properties}, whose datasource points at a real
 *       Postgres via the {@code DB_HOST}/{@code DB_NAME}/{@code DB_USER}/{@code DB_PASS}
 *       environment variables the running application uses. The Kafka broker and Schema Registry
 *       are still the Testcontainers-managed Redpanda from {@link AbstractKafkaContainerTest}; only
 *       the JPA datasource is real, which lets {@link ActivityLogRepository} read genuine
 *       historical rows instead of an empty database.
 * </ul>
 */
@SpringBootTest
@Tag("rehearsal")
@Tag("kafka")
class HistoricalSchemaRehearsalE2ETest extends AbstractKafkaContainerTest {

    private static final Logger log =
            LoggerFactory.getLogger(HistoricalSchemaRehearsalE2ETest.class);

    // A few hundred rows spanning every action present prove what an exhaustive pass would, at a
    // fraction of the runtime, and bound the corpus scan.
    private static final int MAX_SAMPLE_ROWS_PER_ACTION = 100;

    // The end-to-end sample republishes historical events and waits to see whether any reach the
    // dead-letter topic. Generous relative to DefaultErrorHandler's ~1s x 3 retry policy
    // (KafkaConsumerConfig), so a genuine dead-lettering completes before the window closes.
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
     * timestamp-millis} truncates to milliseconds by design; every other field is compared exactly.
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
            // --- Step 1: corpus check, first so a rehearsal that examines nothing cannot pass
            // silently: report the corpus size and action coverage unconditionally, then fail
            // loudly on zero rows.
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

            // Cap each action's group at MAX_SAMPLE_ROWS_PER_ACTION, not an unbounded scan.
            rowsByAction.replaceAll(
                    (action, rows) ->
                            rows.size() > MAX_SAMPLE_ROWS_PER_ACTION
                                    ? rows.subList(0, MAX_SAMPLE_ROWS_PER_ACTION)
                                    : rows);

            // --- Step 2: per-row reconstruct, then encode/decode through the real registry. Avro's
            // build() is the strictness gate: a historical row that cannot fill every required
            // field fails here, and this rehearsal lets it fail rather than working around it.
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
            // topic. Safe against the real database: ActivityLogRecorder is idempotent on eventId,
            // so republishing a recorded event writes nothing (see this class's Javadoc).
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
