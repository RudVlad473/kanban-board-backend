package com.vrudenko.kanban_board.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
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
 * Orchestrate the two-store nonprod reset: Postgres (delegated to {@link ResetTruncateService}) and
 * the {@code kanban.activity}/{@code kanban.activity.dlt} Kafka topics (this class).
 *
 * <p>Decisions:
 *
 * <p><b>The transactional truncate lives on a separate bean:</b> Spring's {@code @Transactional} is
 * proxy-based and only intercepts calls arriving from outside the bean. A self-invoked {@code
 * this.someTransactionalMethod()} never passes through the proxy, so the annotation would be
 * silently ignored. {@link ResetTruncateService} is therefore its own {@code @Service}.
 *
 * <p><b>The {@code @KafkaListener} containers are paused for the duration of the reset:</b> a
 * record the consumer had already polled but not persisted when the Postgres truncate ran could
 * land in {@code activity_log} moments later and repopulate the table just emptied. Stopping every
 * container before either store is touched, and restarting them once both truncates have completed
 * (or failed), removes that race.
 *
 * <p><b>{@code AdminClient.deleteRecords()} rather than deleting and recreating the topic:</b>
 * {@code KafkaAdmin.deleteTopics()} as a runtime method was only added in spring-kafka 4.0, and
 * this project's Spring Boot 3.5.16 BOM manages spring-kafka in the 3.3.x line, so it does not
 * exist here. Deleting and recreating a topic under a live listener is also its own unbounded
 * failure mode. {@code deleteRecords()} moves a partition's log-start offset forward without
 * touching topic existence, and composes with the listener pause.
 */
@Profile("nonprod")
@Service
public class ResetService {
    private static final Logger log = LoggerFactory.getLogger(ResetService.class);

    @Autowired private KafkaAdmin kafkaAdmin;

    @Autowired private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired private ResetTruncateService resetTruncateService;

    @Autowired private UserRepository userRepository;

    @Autowired private UserService userService;

    @Autowired private ActivityLogRepository activityLogRepository;

    @Autowired private EntityManager entityManager;

    /**
     * Reset both stores. The listener containers restart in a {@code finally}, so a failure in the
     * topic trim or the truncate never leaves the consumer permanently stalled.
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
     * Cascade-delete each listed user's data and {@code activity_log} rows, leaving every other
     * user and both Kafka topics untouched.
     *
     * <p>The cascade is {@link UserService#deleteById}: boards, columns, tasks and subtasks.
     *
     * <p>Decisions:
     *
     * <p><b>Existence check before any delete.</b> Every id is verified to exist, in one batched
     * {@code IN (...)} query, before a single delete runs, all inside this one
     * {@code @Transactional} method. Otherwise a caller past the shared-secret gate could submit
     * one real id plus one guessed id and use the partial-success/404 split as an oracle for "does
     * this user id exist". Checking first makes the outcome binary: all deleted, or none deleted
     * plus a 404.
     *
     * <p><b>Accepted, bounded race with {@code ActivityLogConsumer} (not engineered away).</b>
     * {@link UserService#deleteById}'s cascade publishes domain events (e.g. {@code
     * BoardDeletedEvent}) that {@code KafkaEventPublisher} sends {@code @Async} on {@code
     * AFTER_COMMIT}. If {@code ActivityLogConsumer} processes one for a just-deleted user after
     * this method's {@code activity_log} cleanup ran and this transaction committed, a single stray
     * {@code activity_log} row referencing that now-nonexistent user can reappear. Not solved by
     * pausing the Kafka listeners as {@link #resetAll()} does: that would stall the activity feed
     * for every unrelated live user during a call meant to be scoped to a few target users. The
     * affected id can never be a valid delete target again once its user row is gone, so the risk
     * is self-limited; it is the same accepted-race pattern as {@code
     * SecurityConfiguration#sessionAuthenticationStrategy}'s concurrent-session-ceiling overshoot.
     */
    @Transactional
    public void deleteUsers(List<String> userIds) {
        var distinctIds = userIds.stream().distinct().toList();

        if (userRepository.findAllById(distinctIds).size() != distinctIds.size()) {
            throw new AppEntityNotFoundException("User");
        }

        for (String id : distinctIds) {
            userService.deleteById(id);
        }

        entityManager.flush();
        activityLogRepository.deleteAllByUserIdIn(distinctIds);
        entityManager.clear();
    }

    /**
     * Trim {@link KafkaTopics#ACTIVITY} and {@link KafkaTopics#ACTIVITY_DLT} (both
     * single-partition, see {@code KafkaConsumerConfig}) to their current end offset.
     *
     * <p>A topic that does not exist yet ({@link UnknownTopicOrPartitionException}) counts as
     * already empty, so a reset before any traffic still succeeds. Any other failure propagates, so
     * a Kafka-side problem fails {@code resetAll()} instead of leaving a silently partial reset.
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
