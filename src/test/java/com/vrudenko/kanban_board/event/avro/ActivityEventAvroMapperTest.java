package com.vrudenko.kanban_board.event.avro;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.vrudenko.kanban_board.event.ActivityEvent;
import com.vrudenko.kanban_board.event.BoardCreatedEvent;
import com.vrudenko.kanban_board.event.BoardDeletedEvent;
import com.vrudenko.kanban_board.event.BoardUpdatedEvent;
import com.vrudenko.kanban_board.event.ColumnCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnDeletedEvent;
import com.vrudenko.kanban_board.event.ColumnReorderedEvent;
import com.vrudenko.kanban_board.event.ColumnUpdatedEvent;
import com.vrudenko.kanban_board.event.SubtaskCreatedEvent;
import com.vrudenko.kanban_board.event.SubtaskDeletedEvent;
import com.vrudenko.kanban_board.event.SubtaskUpdatedEvent;
import com.vrudenko.kanban_board.event.TaskCreatedEvent;
import com.vrudenko.kanban_board.event.TaskDeletedEvent;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.event.TaskUpdatedEvent;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Plain-context test of {@link ActivityEventAvroMapper}, which touches neither Kafka nor a DB.
 *
 * <p>Extends {@link com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest}
 * only because the test profile names no datasource, so the context needs a container to boot.
 */
@SpringBootTest
public class ActivityEventAvroMapperTest
        extends com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest {

    @Autowired private ActivityEventAvroMapper mapper;

    @Nested
    class RoundTripTest {

        @Test
        void shouldRoundTrip_whenTaskCreatedEvent() {
            // arrange
            var event =
                    new TaskCreatedEvent(
                            UUID.randomUUID().toString(),
                            "user-1",
                            "board-1",
                            "column-1",
                            "task-1",
                            Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroTaskCreatedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenTaskMovedEvent() {
            // arrange
            var event =
                    new TaskMovedEvent(
                            UUID.randomUUID().toString(),
                            "user-1",
                            "board-1",
                            "task-1",
                            "column-source",
                            "column-target",
                            Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroTaskMovedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenTaskDeletedEvent() {
            // arrange
            var event =
                    new TaskDeletedEvent(
                            UUID.randomUUID().toString(),
                            "user-1",
                            "board-1",
                            "column-1",
                            "task-1",
                            Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroTaskDeletedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenBoardCreatedEvent() {
            // arrange
            var event =
                    new BoardCreatedEvent(
                            UUID.randomUUID().toString(), "user-1", "board-1", Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroBoardCreatedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenColumnCreatedEvent() {
            // arrange
            var event =
                    new ColumnCreatedEvent(
                            UUID.randomUUID().toString(),
                            "user-1",
                            "board-1",
                            "column-1",
                            Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroColumnCreatedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenSubtaskCreatedEvent() {
            // arrange
            var event =
                    new SubtaskCreatedEvent(
                            UUID.randomUUID().toString(),
                            "user-1",
                            "board-1",
                            "task-1",
                            "subtask-1",
                            Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroSubtaskCreatedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenColumnDeletedEvent() {
            // arrange
            var event =
                    new ColumnDeletedEvent(
                            UUID.randomUUID().toString(),
                            "user-1",
                            "board-1",
                            "column-1",
                            Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroColumnDeletedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenBoardUpdatedEvent() {
            // arrange
            var event =
                    new BoardUpdatedEvent(
                            UUID.randomUUID().toString(), "user-1", "board-1", Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroBoardUpdatedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenBoardDeletedEvent() {
            // arrange
            var event =
                    new BoardDeletedEvent(
                            UUID.randomUUID().toString(), "user-1", "board-1", Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroBoardDeletedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenColumnUpdatedEvent() {
            // arrange
            var event =
                    new ColumnUpdatedEvent(
                            UUID.randomUUID().toString(),
                            "user-1",
                            "board-1",
                            "column-1",
                            Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroColumnUpdatedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenColumnReorderedEvent() {
            // arrange
            var event =
                    new ColumnReorderedEvent(
                            UUID.randomUUID().toString(),
                            "user-1",
                            "board-1",
                            "column-1",
                            3,
                            1,
                            Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroColumnReorderedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenTaskUpdatedEvent() {
            // arrange
            var event =
                    new TaskUpdatedEvent(
                            UUID.randomUUID().toString(),
                            "user-1",
                            "board-1",
                            "column-1",
                            "task-1",
                            Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroTaskUpdatedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenSubtaskUpdatedEvent() {
            // arrange
            var event =
                    new SubtaskUpdatedEvent(
                            UUID.randomUUID().toString(),
                            "user-1",
                            "board-1",
                            "task-1",
                            "subtask-1",
                            true,
                            Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroSubtaskUpdatedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        @Test
        void shouldRoundTrip_whenSubtaskDeletedEvent() {
            // arrange
            var event =
                    new SubtaskDeletedEvent(
                            UUID.randomUUID().toString(),
                            "user-1",
                            "board-1",
                            "task-1",
                            "subtask-1",
                            Instant.now());

            // act
            var avroRecord = mapper.toAvro(event);
            var roundTripped = mapper.toDomain(avroRecord);

            // assert
            Assertions.assertThat(avroRecord).isInstanceOf(AvroSubtaskDeletedEvent.class);
            assertRoundTripEqual(event, roundTripped);
        }

        /**
         * Compares field by field because Avro's generated {@code setTimestamp()} truncates to
         * {@link ChronoUnit#MILLIS}.
         *
         * <p>Why this is the way it is: a full-precision {@link Instant#now()} never round-trips
         * bit-identical through the {@code timestamp-millis} logical type (see the generated {@code
         * AvroTaskMovedEvent.setTimestamp}), so timestamp uses {@code isCloseTo} instead of
         * pre-truncating the input. Every other field, {@code eventId} included, is exact: neither
         * {@code uuid} nor plain strings lose precision.
         */
        private void assertRoundTripEqual(ActivityEvent original, ActivityEvent roundTripped) {
            Assertions.assertThat(roundTripped).isInstanceOf(original.getClass());
            Assertions.assertThat(roundTripped.eventId()).isEqualTo(original.eventId());
            Assertions.assertThat(roundTripped.userId()).isEqualTo(original.userId());
            Assertions.assertThat(roundTripped.boardId()).isEqualTo(original.boardId());
            Assertions.assertThat(roundTripped.timestamp())
                    .isCloseTo(original.timestamp(), Assertions.within(1, ChronoUnit.MILLIS));

            switch (original) {
                case TaskCreatedEvent o -> {
                    var r = (TaskCreatedEvent) roundTripped;
                    Assertions.assertThat(r.columnId()).isEqualTo(o.columnId());
                    Assertions.assertThat(r.taskId()).isEqualTo(o.taskId());
                }
                case TaskMovedEvent o -> {
                    var r = (TaskMovedEvent) roundTripped;
                    Assertions.assertThat(r.taskId()).isEqualTo(o.taskId());
                    Assertions.assertThat(r.sourceColumnId()).isEqualTo(o.sourceColumnId());
                    Assertions.assertThat(r.targetColumnId()).isEqualTo(o.targetColumnId());
                }
                case TaskDeletedEvent o -> {
                    var r = (TaskDeletedEvent) roundTripped;
                    Assertions.assertThat(r.columnId()).isEqualTo(o.columnId());
                    Assertions.assertThat(r.taskId()).isEqualTo(o.taskId());
                }
                case BoardCreatedEvent ignored -> {
                    // no additional fields beyond the shared ActivityEvent accessors
                }
                case ColumnCreatedEvent o -> {
                    var r = (ColumnCreatedEvent) roundTripped;
                    Assertions.assertThat(r.columnId()).isEqualTo(o.columnId());
                }
                case ColumnDeletedEvent o -> {
                    var r = (ColumnDeletedEvent) roundTripped;
                    Assertions.assertThat(r.columnId()).isEqualTo(o.columnId());
                }
                case SubtaskCreatedEvent o -> {
                    var r = (SubtaskCreatedEvent) roundTripped;
                    Assertions.assertThat(r.taskId()).isEqualTo(o.taskId());
                    Assertions.assertThat(r.subtaskId()).isEqualTo(o.subtaskId());
                }
                case BoardUpdatedEvent ignored -> {
                    // no additional fields beyond the shared ActivityEvent accessors
                }
                case BoardDeletedEvent ignored -> {
                    // no additional fields beyond the shared ActivityEvent accessors
                }
                case ColumnUpdatedEvent o -> {
                    var r = (ColumnUpdatedEvent) roundTripped;
                    Assertions.assertThat(r.columnId()).isEqualTo(o.columnId());
                }
                case ColumnReorderedEvent o -> {
                    var r = (ColumnReorderedEvent) roundTripped;
                    Assertions.assertThat(r.columnId()).isEqualTo(o.columnId());
                    Assertions.assertThat(r.sourcePosition()).isEqualTo(o.sourcePosition());
                    Assertions.assertThat(r.targetPosition()).isEqualTo(o.targetPosition());
                }
                case TaskUpdatedEvent o -> {
                    var r = (TaskUpdatedEvent) roundTripped;
                    Assertions.assertThat(r.columnId()).isEqualTo(o.columnId());
                    Assertions.assertThat(r.taskId()).isEqualTo(o.taskId());
                }
                case SubtaskUpdatedEvent o -> {
                    var r = (SubtaskUpdatedEvent) roundTripped;
                    Assertions.assertThat(r.taskId()).isEqualTo(o.taskId());
                    Assertions.assertThat(r.subtaskId()).isEqualTo(o.subtaskId());
                    Assertions.assertThat(r.isCompleted()).isEqualTo(o.isCompleted());
                }
                case SubtaskDeletedEvent o -> {
                    var r = (SubtaskDeletedEvent) roundTripped;
                    Assertions.assertThat(r.taskId()).isEqualTo(o.taskId());
                    Assertions.assertThat(r.subtaskId()).isEqualTo(o.subtaskId());
                }
            }
        }
    }

    @Nested
    class ToDomainTest {

        @Test
        void shouldThrow_whenRecordTypeIsUnrecognised() {
            // arrange
            var unknownRecord = new UnknownSpecificRecord();

            // act
            var exception = Assertions.catchException(() -> mapper.toDomain(unknownRecord));

            // assert
            Assertions.assertThat(exception).isInstanceOf(IllegalArgumentException.class);
            Assertions.assertThat(exception.getMessage()).contains("UnknownSpecificRecord");
        }
    }

    /**
     * A real {@link SpecificRecord} the mapper does not know, to hit {@link
     * ActivityEventAvroMapper#toDomain}'s required {@code default} arm: {@link SpecificRecord} is
     * not sealed.
     */
    private static final class UnknownSpecificRecord implements SpecificRecord {
        @Override
        public void put(int i, Object v) {}

        @Override
        public Object get(int i) {
            return null;
        }

        @Override
        public Schema getSchema() {
            return Schema.create(Schema.Type.NULL);
        }
    }
}
