package com.vrudenko.kanban_board.event.avro;

import com.vrudenko.kanban_board.event.ActivityEvent;
import com.vrudenko.kanban_board.event.BoardCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnDeletedEvent;
import com.vrudenko.kanban_board.event.TaskCreatedEvent;
import com.vrudenko.kanban_board.event.TaskDeletedEvent;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * No mocks (CODE_STYLE rule 4 / plan Task 3): {@link ActivityEventAvroMapper} touches neither Kafka
 * nor a database, so a plain Spring context is sufficient to autowire it. Still extends {@link
 * com.vrudenko.kanban_board.AbstractPostgresContainerTest} because the test profile no longer names
 * a datasource (04.2, D-01) -- booting the full context requires a container even though this
 * class's own assertions never touch it.
 */
@SpringBootTest
public class ActivityEventAvroMapperTest
        extends com.vrudenko.kanban_board.AbstractPostgresContainerTest {

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

        /**
         * Field-for-field comparison rather than a single record {@code equals()} call. Avro's
         * generated {@code setTimestamp()} truncates to {@link ChronoUnit#MILLIS} (see {@code
         * AvroTaskMovedEvent.setTimestamp} — confirmed by reading the generated source this
         * session), so a full-precision {@link Instant#now()} used as test input never round-trips
         * as bit-identical; this is a real property of the {@code timestamp-millis} logical type,
         * not a test bug, and is asserted with {@code isCloseTo} rather than papered over by
         * pre-truncating the input (matching the precedent already set for the JSON pipeline's own
         * sub-millisecond loss, STATE.md Phase 03 Plan 01). Every other field (eventId included) is
         * asserted with exact equality, since neither the {@code uuid} logical type nor any plain
         * string field loses precision on this path.
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
     * A real (not mocked) {@link SpecificRecord} implementation the mapper does not know about —
     * exercises {@link ActivityEventAvroMapper#toDomain}'s required {@code default} arm, since
     * {@link SpecificRecord} is not sealed and the compiler cannot enumerate its implementations.
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
