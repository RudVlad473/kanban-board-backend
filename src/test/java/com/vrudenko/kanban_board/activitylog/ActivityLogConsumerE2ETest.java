package com.vrudenko.kanban_board.activitylog;

import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.event.BoardCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnDeletedEvent;
import com.vrudenko.kanban_board.event.TaskCreatedEvent;
import com.vrudenko.kanban_board.event.TaskDeletedEvent;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real-broker proof that {@link ActivityLogConsumer#onActivityEvent} turns a published event into a
 * persisted, deduplicated {@link ActivityLogEntity} row (TEST-01, ACTLOG-02). Every assertion here
 * waits on a real {@code apache/kafka-native} container started by {@link
 * AbstractKafkaContainerTest} -- consumer-group formation and first delivery are asynchronous, so
 * every assertion below polls with {@link Awaitility} rather than sleeping a fixed duration.
 */
@SpringBootTest
@Tag("kafka")
class ActivityLogConsumerE2ETest extends AbstractKafkaContainerTest {

    @Autowired private ActivityLogRepository activityLogRepository;

    private String randomId() {
        return UUID.randomUUID().toString();
    }

    @Nested
    class OnActivityEventTest {

        @Test
        void shouldPersistExactlyOneRow_whenTaskMovedEventPublishedThroughRealBroker()
                throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
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
            sendAndAwaitAck(event);

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
        void shouldPersistTaskCreated_withColumnIdThenTaskIdDetail() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var columnId = randomId();
            var taskId = randomId();
            var event =
                    new TaskCreatedEvent(
                            eventId, randomId(), randomId(), columnId, taskId, Instant.now());

            // act
            sendAndAwaitAck(event);

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
        void shouldPersistTaskMoved_withTaskIdSourceColumnIdTargetColumnIdDetail()
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
        void shouldPersistTaskDeleted_withColumnIdThenTaskIdDetail() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var columnId = randomId();
            var taskId = randomId();
            var event =
                    new TaskDeletedEvent(
                            eventId, randomId(), randomId(), columnId, taskId, Instant.now());

            // act
            sendAndAwaitAck(event);

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
        void shouldPersistBoardCreated_withEmptyDetail() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var event = new BoardCreatedEvent(eventId, randomId(), randomId(), Instant.now());

            // act
            sendAndAwaitAck(event);

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
        void shouldPersistColumnCreated_withColumnIdDetail() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var columnId = randomId();
            var event =
                    new ColumnCreatedEvent(
                            eventId, randomId(), randomId(), columnId, Instant.now());

            // act
            sendAndAwaitAck(event);

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
        void shouldPersistColumnDeleted_withColumnIdDetailAndEventTimestamp() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var columnId = randomId();
            var timestamp = Instant.now();
            var event =
                    new ColumnDeletedEvent(eventId, randomId(), randomId(), columnId, timestamp);

            // act
            sendAndAwaitAck(event);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                var row = findByEventId(eventId);
                                Assertions.assertThat(row.getAction())
                                        .isEqualTo(ActivityAction.COLUMN_DELETED);
                                Assertions.assertThat(row.getDetail())
                                        .isEqualTo("{\"columnId\":\"" + columnId + "\"}");
                                // Proves the row's timestamp comes from the event, not a fresh
                                // clock reading taken by the consumer (see
                                // ActivityLogConsumer.onActivityEvent's Javadoc). Millisecond
                                // tolerance because Avro's timestamp-millis logical type
                                // truncates by design (see
                                // shouldPopulateAllColumns_whenBoardCreatedEventIsSparsest below
                                // for the fuller explanation of this same tolerance).
                                Assertions.assertThat(row.getCreatedAt())
                                        .isCloseTo(
                                                timestamp, Assertions.within(1, ChronoUnit.MILLIS));
                            });
        }

        @Test
        void shouldProduceIdenticalDetail_whenTwoTaskMovedEventsShareSameIds() throws Exception {
            // arrange
            var taskId = randomId();
            var sourceColumnId = randomId();
            var targetColumnId = randomId();
            var firstEventId = UUID.randomUUID().toString();
            var secondEventId = UUID.randomUUID().toString();
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
            sendAndAwaitAck(firstEvent);
            sendAndAwaitAck(secondEvent);

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
        void shouldProduceTwoRows_whenTwoEventsShareSameBoardUserAndInstantButDifferentEventIds()
                throws Exception {
            // arrange
            var boardId = randomId();
            var userId = randomId();
            var sharedInstant = Instant.now();
            var firstEventId = UUID.randomUUID().toString();
            var secondEventId = UUID.randomUUID().toString();
            var firstEvent =
                    new ColumnCreatedEvent(
                            firstEventId, userId, boardId, randomId(), sharedInstant);
            var secondEvent =
                    new ColumnCreatedEvent(
                            secondEventId, userId, boardId, randomId(), sharedInstant);

            // act
            sendAndAwaitAck(firstEvent);
            sendAndAwaitAck(secondEvent);

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
        void shouldPopulateAllColumns_whenBoardCreatedEventIsSparsest() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var userId = randomId();
            var boardId = randomId();
            var timestamp = Instant.now();
            var event = new BoardCreatedEvent(eventId, userId, boardId, timestamp);

            // act
            sendAndAwaitAck(event);

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
                                // Since Phase 4 (Schema Registry), the wire-format precision floor
                                // is coarser than it used to be: `timestamp` is carried as Avro's
                                // timestamp-millis logical type (see AvroBoardCreatedEvent.avsc),
                                // whose generated accessor truncates to millisecond precision by
                                // design (confirmed by direct inspection of the generated code,
                                // 04-01-SUMMARY.md) -- not a bug, and not the same failure mode the
                                // original 1-microsecond tolerance here was written to absorb (the
                                // JSON pipeline's double-precision epoch-seconds encoding, which
                                // lost only sub-microsecond digits). An Instant.now() value
                                // carrying
                                // JVM nanosecond precision can now lose up to just under a full
                                // millisecond on its round trip through Avro encoding and JPA
                                // persistence, so the tolerance widens to match that floor.
                                Assertions.assertThat(row.getCreatedAt())
                                        .isCloseTo(
                                                timestamp, Assertions.within(1, ChronoUnit.MILLIS));
                            });
        }

        private ActivityLogEntity findByEventId(String eventId) {
            var rows =
                    activityLogRepository.findAll().stream()
                            .filter(row -> row.getEventId().equals(eventId))
                            .toList();
            Assertions.assertThat(rows).hasSize(1);
            return rows.getFirst();
        }
    }
}
