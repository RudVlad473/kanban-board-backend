package com.vrudenko.kanban_board.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.vrudenko.kanban_board.constant.KafkaTopics;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

/**
 * Plan 08-02 (RESET-01): orchestrates the two-store nonprod reset -- Postgres (delegated to {@link
 * ResetTruncateService}) and the {@code kanban.activity}/{@code kanban.activity.dlt} Kafka topics
 * (this class).
 *
 * <p><b>Why the transactional truncate lives on a separate bean, not a private method here:</b>
 * Spring's {@code @Transactional} is proxy-based -- it only intercepts calls arriving from outside
 * the bean. A self-invoked {@code this.someTransactionalMethod()} call never passes through the
 * proxy, so the annotation would be silently ignored. {@link ResetTruncateService} is therefore its
 * own {@code @Service}, called here through the normal Spring-managed reference.
 *
 * <p><b>Why the {@code @KafkaListener} containers are paused for the duration of the reset:</b>
 * without pausing, a record the consumer already polled (but had not yet persisted) at the instant
 * the Postgres truncate ran could still land in {@code activity_log} moments later, silently
 * repopulating the very table this method just emptied. Stopping every listener container before
 * either store is touched, and restarting them only once both truncates have completed (or failed),
 * removes that race entirely.
 *
 * <p><b>Why {@code AdminClient.deleteRecords()} rather than deleting and recreating the topic:</b>
 * {@code KafkaAdmin.deleteTopics()} as a runtime method was only added in spring-kafka 4.0; this
 * project's Spring Boot 3.5.16 BOM manages spring-kafka in the 3.3.x line, so that method does not
 * exist here. Deleting and recreating a topic out from under a live listener is also its own
 * unbounded failure mode independent of that version gap. {@code deleteRecords()} is the narrower,
 * safer primitive: it moves a partition's log-start offset forward to a chosen point, satisfying
 * D-03's literal "zero rows" without ever touching topic existence, and composes cleanly with the
 * listener-pause above.
 */
@Profile("nonprod")
@Service
public class ResetService {
    private static final Logger log = LoggerFactory.getLogger(ResetService.class);

    @Autowired private KafkaAdmin kafkaAdmin;

    @Autowired private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired private ResetTruncateService resetTruncateService;

    /**
     * Runs, in order: (1) stop every listener container, (2) trim both activity topics to their
     * current high watermark, (3) truncate every Postgres table, (4) restart every listener
     * container -- step 4 in a {@code finally} so a failure in step 2 or 3 still leaves the
     * consumer running afterward rather than permanently stalled.
     */
    public void resetAll() {
        kafkaListenerEndpointRegistry
                .getListenerContainers()
                .forEach(container -> container.stop());

        try {
            truncateActivityTopics();
            resetTruncateService.truncateAll();
        } finally {
            kafkaListenerEndpointRegistry
                    .getListenerContainers()
                    .forEach(container -> container.start());
        }
    }

    /**
     * Trims {@link KafkaTopics#ACTIVITY} and {@link KafkaTopics#ACTIVITY_DLT} (both declared
     * single-partition -- see {@code KafkaConsumerConfig}) to their current end offset. A topic
     * that does not exist yet ({@link UnknownTopicOrPartitionException}) is treated as already
     * empty and skipped -- a reset issued before any traffic has ever touched the broker must still
     * succeed. Any other failure propagates, so a genuine Kafka-side problem surfaces as a failed
     * {@code resetAll()} call rather than a silently-partial reset.
     */
    void truncateActivityTopics() {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            for (String topic : List.of(KafkaTopics.ACTIVITY, KafkaTopics.ACTIVITY_DLT)) {
                var partition = new TopicPartition(topic, 0);

                try {
                    var endOffset =
                            admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
                                    .partitionResult(partition)
                                    .get()
                                    .offset();

                    admin.deleteRecords(Map.of(partition, RecordsToDelete.beforeOffset(endOffset)))
                            .all()
                            .get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                        log.info(
                                "Topic {} does not exist yet; treating it as already empty for"
                                        + " reset purposes.",
                                topic);
                        continue;
                    }
                    throw new IllegalStateException(
                            "Failed to truncate Kafka topic " + topic + " during nonprod reset", e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while truncating Kafka topic records during nonprod reset", e);
        }
    }
}
