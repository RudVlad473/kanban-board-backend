package com.vrudenko.kanban_board.activitylog;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.event.BoardCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnCreatedEvent;
import com.vrudenko.kanban_board.event.TaskCreatedEvent;
import com.vrudenko.kanban_board.event.TaskDeletedEvent;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Real-broker proof that {@link ActivityLogConsumer#onActivityEvent} turns a published event into a
 * persisted, deduplicated {@link ActivityLogEntity} row (TEST-01, ACTLOG-02). Every assertion here
 * waits on a real {@code apache/kafka-native} container started by {@link
 * AbstractKafkaContainerTest} -- consumer-group formation and first delivery are asynchronous, so
 * every assertion below polls with {@link Awaitility} rather than sleeping a fixed duration.
 */
@SpringBootTest
class ActivityLogConsumerE2ETest extends AbstractKafkaContainerTest {

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private ActivityLogRepository activityLogRepository;

    private String randomId() {
        return UUID.randomUUID().toString();
    }

    @Nested
    class OnActivityEventTest {

        @Test
        void shouldPersistExactlyOneRow_whenTaskMovedEventPublishedThroughRealBroker() {
            // arrange
            var eventId = UUID.randomUUID();
            var userId = randomId();
            var boardId = randomId();
            var event =
                    new TaskMovedEvent(
                            eventId,
                            userId,
                            boardId,
                            randomId(),
                            randomId(),
                            randomId(),
                            Instant.now());

            // act
            kafkaTemplate.send(KafkaTopics.ACTIVITY, event.eventId().toString(), event);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var row = findByEventId(eventId);
                                Assertions.assertThat(row.getBoardId()).isEqualTo(boardId);
                                Assertions.assertThat(row.getUserId()).isEqualTo(userId);
                                Assertions.assertThat(row.getAction())
                                        .isEqualTo(ActivityAction.TASK_MOVED);
                            });
        }

        @Test
        void shouldPersistTaskCreated_withColumnIdThenTaskIdDetail() {
            // arrange
            var eventId = UUID.randomUUID();
            var columnId = randomId();
            var taskId = randomId();
            var event =
                    new TaskCreatedEvent(
                            eventId, randomId(), randomId(), columnId, taskId, Instant.now());

            // act
            kafkaTemplate.send(KafkaTopics.ACTIVITY, event.eventId().toString(), event);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var row = findByEventId(eventId);
                                Assertions.assertThat(row.getAction())
                                        .isEqualTo(ActivityAction.TASK_CREATED);
                                Assertions.assertThat(row.getDetail())
                                        .isEqualTo(
                                                "{\"columnId\":\""
                                                        + columnId
                                                        + "\",\"taskId\":\""
                                                        + taskId
                                                        + "\"}");
                            });
        }

        @Test
        void shouldPersistTaskMoved_withTaskIdSourceColumnIdTargetColumnIdDetail() {
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
            kafkaTemplate.send(KafkaTopics.ACTIVITY, event.eventId().toString(), event);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var row = findByEventId(eventId);
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

        @Test
        void shouldPersistTaskDeleted_withColumnIdThenTaskIdDetail() {
            // arrange
            var eventId = UUID.randomUUID();
            var columnId = randomId();
            var taskId = randomId();
            var event =
                    new TaskDeletedEvent(
                            eventId, randomId(), randomId(), columnId, taskId, Instant.now());

            // act
            kafkaTemplate.send(KafkaTopics.ACTIVITY, event.eventId().toString(), event);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var row = findByEventId(eventId);
                                Assertions.assertThat(row.getAction())
                                        .isEqualTo(ActivityAction.TASK_DELETED);
                                Assertions.assertThat(row.getDetail())
                                        .isEqualTo(
                                                "{\"columnId\":\""
                                                        + columnId
                                                        + "\",\"taskId\":\""
                                                        + taskId
                                                        + "\"}");
                            });
        }

        @Test
        void shouldPersistBoardCreated_withEmptyDetail() {
            // arrange
            var eventId = UUID.randomUUID();
            var event = new BoardCreatedEvent(eventId, randomId(), randomId(), Instant.now());

            // act
            kafkaTemplate.send(KafkaTopics.ACTIVITY, event.eventId().toString(), event);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var row = findByEventId(eventId);
                                Assertions.assertThat(row.getAction())
                                        .isEqualTo(ActivityAction.BOARD_CREATED);
                                Assertions.assertThat(row.getDetail()).isEqualTo("{}");
                            });
        }

        @Test
        void shouldPersistColumnCreated_withColumnIdDetail() {
            // arrange
            var eventId = UUID.randomUUID();
            var columnId = randomId();
            var event =
                    new ColumnCreatedEvent(
                            eventId, randomId(), randomId(), columnId, Instant.now());

            // act
            kafkaTemplate.send(KafkaTopics.ACTIVITY, event.eventId().toString(), event);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var row = findByEventId(eventId);
                                Assertions.assertThat(row.getAction())
                                        .isEqualTo(ActivityAction.COLUMN_CREATED);
                                Assertions.assertThat(row.getDetail())
                                        .isEqualTo("{\"columnId\":\"" + columnId + "\"}");
                            });
        }

        @Test
        void shouldProduceIdenticalDetail_whenTwoTaskMovedEventsShareSameIds() {
            // arrange
            var taskId = randomId();
            var sourceColumnId = randomId();
            var targetColumnId = randomId();
            var firstEventId = UUID.randomUUID();
            var secondEventId = UUID.randomUUID();
            var firstEvent =
                    new TaskMovedEvent(
                            firstEventId,
                            randomId(),
                            randomId(),
                            taskId,
                            sourceColumnId,
                            targetColumnId,
                            Instant.now());
            var secondEvent =
                    new TaskMovedEvent(
                            secondEventId,
                            randomId(),
                            randomId(),
                            taskId,
                            sourceColumnId,
                            targetColumnId,
                            Instant.now());

            // act
            kafkaTemplate.send(KafkaTopics.ACTIVITY, firstEvent.eventId().toString(), firstEvent);
            kafkaTemplate.send(KafkaTopics.ACTIVITY, secondEvent.eventId().toString(), secondEvent);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var firstRow = findByEventId(firstEventId);
                                var secondRow = findByEventId(secondEventId);
                                Assertions.assertThat(firstRow.getDetail())
                                        .isEqualTo(secondRow.getDetail());
                            });
        }

        @Test
        void shouldProduceTwoRows_whenTwoEventsShareSameBoardUserAndInstantButDifferentEventIds() {
            // arrange
            var boardId = randomId();
            var userId = randomId();
            var sharedInstant = Instant.now();
            var firstEventId = UUID.randomUUID();
            var secondEventId = UUID.randomUUID();
            var firstEvent =
                    new ColumnCreatedEvent(
                            firstEventId, userId, boardId, randomId(), sharedInstant);
            var secondEvent =
                    new ColumnCreatedEvent(
                            secondEventId, userId, boardId, randomId(), sharedInstant);

            // act
            kafkaTemplate.send(KafkaTopics.ACTIVITY, firstEvent.eventId().toString(), firstEvent);
            kafkaTemplate.send(KafkaTopics.ACTIVITY, secondEvent.eventId().toString(), secondEvent);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var rowsForBoard =
                                        activityLogRepository.findAll().stream()
                                                .filter(row -> row.getBoardId().equals(boardId))
                                                .toList();
                                Assertions.assertThat(rowsForBoard).hasSize(2);
                            });
        }

        @Test
        void shouldPopulateAllColumns_whenBoardCreatedEventIsSparsest() {
            // arrange
            var eventId = UUID.randomUUID();
            var userId = randomId();
            var boardId = randomId();
            var timestamp = Instant.now();
            var event = new BoardCreatedEvent(eventId, userId, boardId, timestamp);

            // act
            kafkaTemplate.send(KafkaTopics.ACTIVITY, event.eventId().toString(), event);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var row = findByEventId(eventId);
                                Assertions.assertThat(row.getBoardId()).isNotNull();
                                Assertions.assertThat(row.getUserId()).isNotNull();
                                Assertions.assertThat(row.getAction()).isNotNull();
                                Assertions.assertThat(row.getDetail()).isNotNull();
                                Assertions.assertThat(row.getEventId()).isNotNull();
                                Assertions.assertThat(row.getCreatedAt()).isNotNull();
                                Assertions.assertThat(row.getDetail()).isEqualTo("{}");
                                Assertions.assertThat(row.getCreatedAt()).isEqualTo(timestamp);
                            });
        }

        private ActivityLogEntity findByEventId(UUID eventId) {
            var rows =
                    activityLogRepository.findAll().stream()
                            .filter(row -> row.getEventId().equals(eventId))
                            .toList();
            Assertions.assertThat(rows).hasSize(1);
            return rows.getFirst();
        }
    }
}
