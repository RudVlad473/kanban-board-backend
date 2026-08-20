package com.vrudenko.kanban_board.activitylog;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;

import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.event.ActivityEvent;
import com.vrudenko.kanban_board.event.BoardCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnCreatedEvent;
import com.vrudenko.kanban_board.event.TaskCreatedEvent;
import com.vrudenko.kanban_board.event.TaskDeletedEvent;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves {@link HistoricalActivityEventReconstructor} is the exact inverse of the real, shipped
 * consumer pipeline (SCHEMA-06) -- never of a reimplementation of its mapping. Every positive case
 * publishes a real event of one action type through the real broker, waits for the real {@link
 * ActivityLogConsumer} to persist its row, loads that row back, reconstructs it, and asserts the
 * result equals the original event. {@code deriveActionAndDetailIds} is private and could drift
 * from a test that merely read its source; round-tripping through the real pipeline is what proves
 * the reconstructor tracks what the consumer actually does, not what it is believed to do.
 */
@SpringBootTest
@Tag("kafka")
class HistoricalActivityEventReconstructorTest extends AbstractKafkaContainerTest {

    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private ObjectMapper objectMapper;

    private HistoricalActivityEventReconstructor reconstructor;

    @BeforeEach
    void createReconstructor() {
        reconstructor = new HistoricalActivityEventReconstructor(objectMapper);
    }

    private String randomId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Polls until exactly one row carries {@code eventId}, matching every sibling class in this
     * package: the topic and consumer group are shared across the whole {@code activitylog}
     * package's cached Spring context, so matching must be scoped by {@code eventId} rather than
     * assuming the table starts empty.
     */
    private ActivityLogEntity awaitPersistedRow(String eventId) {
        var found = new ArrayList<ActivityLogEntity>();
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            var matches =
                                    activityLogRepository.findAll().stream()
                                            .filter(row -> row.getEventId().equals(eventId))
                                            .toList();
                            Assertions.assertThat(matches).hasSize(1);
                            found.clear();
                            found.addAll(matches);
                        });
        return found.getFirst();
    }

    /**
     * Compares every field except {@code timestamp} for exact equality, then compares {@code
     * timestamp} with an explicit 1-millisecond tolerance: Avro's {@code timestamp-millis} logical
     * type truncates to millisecond precision by design (confirmed by direct inspection, per
     * 04-01-SUMMARY.md and this project's identical resolution for {@code
     * ActivityLogConsumerE2ETest}), so an {@code Instant.now()} value carrying JVM nanosecond
     * precision can lose up to just under a full millisecond on its round trip through the real
     * pipeline before this reconstructor ever sees it. This is a documented property of the wire
     * format, not a loosened assertion.
     */
    private void assertReconstructedMatchesOriginal(
            ActivityEvent original, ActivityEvent reconstructed) {
        Assertions.assertThat(reconstructed)
                .usingRecursiveComparison()
                .ignoringFields("timestamp")
                .isEqualTo(original);
        Assertions.assertThat(reconstructed.timestamp())
                .isCloseTo(original.timestamp(), Assertions.within(1, ChronoUnit.MILLIS));
    }

    @Nested
    class ReconstructTest {

        @Test
        void shouldReconstructExactEvent_whenActionIsTaskCreated() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var event =
                    new TaskCreatedEvent(
                            eventId, randomId(), randomId(), randomId(), randomId(), Instant.now());

            // act
            sendAndAwaitAck(event);
            var row = awaitPersistedRow(eventId);
            var reconstructed = reconstructor.reconstruct(row);

            // assert
            Assertions.assertThat(row.getAction()).isEqualTo(ActivityAction.TASK_CREATED);
            assertReconstructedMatchesOriginal(event, reconstructed);
        }

        @Test
        void shouldReconstructExactEvent_whenActionIsTaskMoved() throws Exception {
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
            var row = awaitPersistedRow(eventId);
            var reconstructed = reconstructor.reconstruct(row);

            // assert
            Assertions.assertThat(row.getAction()).isEqualTo(ActivityAction.TASK_MOVED);
            assertReconstructedMatchesOriginal(event, reconstructed);
        }

        @Test
        void shouldReconstructExactEvent_whenActionIsTaskDeleted() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var event =
                    new TaskDeletedEvent(
                            eventId, randomId(), randomId(), randomId(), randomId(), Instant.now());

            // act
            sendAndAwaitAck(event);
            var row = awaitPersistedRow(eventId);
            var reconstructed = reconstructor.reconstruct(row);

            // assert
            Assertions.assertThat(row.getAction()).isEqualTo(ActivityAction.TASK_DELETED);
            assertReconstructedMatchesOriginal(event, reconstructed);
        }

        @Test
        void shouldReconstructExactEvent_whenActionIsBoardCreated() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var event = new BoardCreatedEvent(eventId, randomId(), randomId(), Instant.now());

            // act
            sendAndAwaitAck(event);
            var row = awaitPersistedRow(eventId);
            var reconstructed = reconstructor.reconstruct(row);

            // assert
            Assertions.assertThat(row.getAction()).isEqualTo(ActivityAction.BOARD_CREATED);
            assertReconstructedMatchesOriginal(event, reconstructed);
        }

        @Test
        void shouldReconstructExactEvent_whenActionIsColumnCreated() throws Exception {
            // arrange
            var eventId = UUID.randomUUID().toString();
            var event =
                    new ColumnCreatedEvent(
                            eventId, randomId(), randomId(), randomId(), Instant.now());

            // act
            sendAndAwaitAck(event);
            var row = awaitPersistedRow(eventId);
            var reconstructed = reconstructor.reconstruct(row);

            // assert
            Assertions.assertThat(row.getAction()).isEqualTo(ActivityAction.COLUMN_CREATED);
            assertReconstructedMatchesOriginal(event, reconstructed);
        }

        @Test
        void shouldThrow_whenDetailIsMissingRequiredKey() {
            // arrange -- constructed directly, never published: this proves the reconstructor's
            // own defensive behaviour, not a property of what the real consumer ever actually
            // writes.
            var eventId = UUID.randomUUID().toString();
            var row = new ActivityLogEntity();
            row.setEventId(eventId);
            row.setUserId(randomId());
            row.setBoardId(randomId());
            row.setAction(ActivityAction.TASK_CREATED);
            row.setDetail("{}");
            row.setCreatedAt(Instant.now());

            // act
            var exception = Assertions.catchException(() -> reconstructor.reconstruct(row));

            // assert
            Assertions.assertThat(exception).isInstanceOf(IllegalStateException.class);
            Assertions.assertThat(exception.getMessage())
                    .contains(eventId.toString())
                    .contains("TASK_CREATED")
                    .contains("columnId");
        }
    }
}
