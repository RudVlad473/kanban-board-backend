package com.vrudenko.kanban_board.event.avro;

import com.vrudenko.kanban_board.event.ActivityEvent;
import com.vrudenko.kanban_board.event.BoardCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnDeletedEvent;
import com.vrudenko.kanban_board.event.SubtaskCreatedEvent;
import com.vrudenko.kanban_board.event.TaskCreatedEvent;
import com.vrudenko.kanban_board.event.TaskDeletedEvent;
import com.vrudenko.kanban_board.event.TaskMovedEvent;

import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

/**
 * Bidirectional translation between the plain, dependency-free {@link ActivityEvent} sealed
 * interface and the Avro-generated {@link SpecificRecord} classes under this package (D-03: one
 * generated record per event type, produced from {@code src/main/avro/*.avsc}).
 *
 * <p>Deliberately a plain {@code @Component}, not a MapStruct {@code @Mapper} interface — this
 * codebase's Entity&harr;DTO convention ({@link com.vrudenko.kanban_board.mapper.ActivityLogMapper}
 * and siblings) does not extend here. MapStruct's annotation processor generates a mapper for a
 * single concrete source type mapped to a single concrete target type; it cannot generate a mapper
 * whose source is a sealed interface dispatched by pattern matching over 7 unrelated record shapes.
 * Do not "fix" this class into a {@code @Mapper} interface — it is not possible, and rediscovering
 * that costs more than this paragraph.
 *
 * <p>The {@code timestamp-millis} logical type on {@code timestamp}, used by every {@code .avsc}
 * schema in this package, was confirmed by inspection (see the comment in {@code build.gradle} next
 * to the avro plugin declaration) to generate a native {@link java.time.Instant} accessor under
 * gradle-avro-plugin 1.9.1 + Avro 1.12.1. {@code eventId} carries no logical type (GAP-07 — it is a
 * Base36 Snowflake-style string, not a UUID) and generates a plain {@link String} accessor. No
 * manual epoch-millis/string conversion is therefore needed anywhere below — every field passes
 * straight through.
 */
@Component
public class ActivityEventAvroMapper {

    /**
     * Exhaustive switch over the sealed {@link ActivityEvent} — deliberately no {@code default}
     * arm, mirroring {@code ActivityLogConsumer.deriveActionAndDetailIds}. Adding another {@link
     * ActivityEvent} record is then a compile error here until this switch is updated, rather than
     * silently producing an unmappable event at runtime.
     */
    public SpecificRecord toAvro(ActivityEvent event) {
        return switch (event) {
            case TaskCreatedEvent e ->
                    AvroTaskCreatedEvent.newBuilder()
                            .setEventId(e.eventId())
                            .setUserId(e.userId())
                            .setBoardId(e.boardId())
                            .setColumnId(e.columnId())
                            .setTaskId(e.taskId())
                            .setTimestamp(e.timestamp())
                            .build();
            case TaskMovedEvent e ->
                    AvroTaskMovedEvent.newBuilder()
                            .setEventId(e.eventId())
                            .setUserId(e.userId())
                            .setBoardId(e.boardId())
                            .setTaskId(e.taskId())
                            .setSourceColumnId(e.sourceColumnId())
                            .setTargetColumnId(e.targetColumnId())
                            .setTimestamp(e.timestamp())
                            .build();
            case TaskDeletedEvent e ->
                    AvroTaskDeletedEvent.newBuilder()
                            .setEventId(e.eventId())
                            .setUserId(e.userId())
                            .setBoardId(e.boardId())
                            .setColumnId(e.columnId())
                            .setTaskId(e.taskId())
                            .setTimestamp(e.timestamp())
                            .build();
            case BoardCreatedEvent e ->
                    AvroBoardCreatedEvent.newBuilder()
                            .setEventId(e.eventId())
                            .setUserId(e.userId())
                            .setBoardId(e.boardId())
                            .setTimestamp(e.timestamp())
                            .build();
            case ColumnCreatedEvent e ->
                    AvroColumnCreatedEvent.newBuilder()
                            .setEventId(e.eventId())
                            .setUserId(e.userId())
                            .setBoardId(e.boardId())
                            .setColumnId(e.columnId())
                            .setTimestamp(e.timestamp())
                            .build();
            case ColumnDeletedEvent e ->
                    AvroColumnDeletedEvent.newBuilder()
                            .setEventId(e.eventId())
                            .setUserId(e.userId())
                            .setBoardId(e.boardId())
                            .setColumnId(e.columnId())
                            .setTimestamp(e.timestamp())
                            .build();
            case SubtaskCreatedEvent e ->
                    AvroSubtaskCreatedEvent.newBuilder()
                            .setEventId(e.eventId())
                            .setUserId(e.userId())
                            .setBoardId(e.boardId())
                            .setTaskId(e.taskId())
                            .setSubtaskId(e.subtaskId())
                            .setTimestamp(e.timestamp())
                            .build();
        };
    }

    /**
     * Dispatches on the 7 generated Avro types. Unlike {@link #toAvro(ActivityEvent)}, this side
     * requires a {@code default} arm: {@link SpecificRecord} is an ordinary interface, not sealed,
     * so the compiler cannot prove exhaustiveness here the way it can for {@link ActivityEvent}.
     * The {@code default} arm throws rather than silently dropping an unrecognised record.
     */
    public ActivityEvent toDomain(SpecificRecord record) {
        return switch (record) {
            case AvroTaskCreatedEvent r ->
                    new TaskCreatedEvent(
                            r.getEventId(),
                            r.getUserId(),
                            r.getBoardId(),
                            r.getColumnId(),
                            r.getTaskId(),
                            r.getTimestamp());
            case AvroTaskMovedEvent r ->
                    new TaskMovedEvent(
                            r.getEventId(),
                            r.getUserId(),
                            r.getBoardId(),
                            r.getTaskId(),
                            r.getSourceColumnId(),
                            r.getTargetColumnId(),
                            r.getTimestamp());
            case AvroTaskDeletedEvent r ->
                    new TaskDeletedEvent(
                            r.getEventId(),
                            r.getUserId(),
                            r.getBoardId(),
                            r.getColumnId(),
                            r.getTaskId(),
                            r.getTimestamp());
            case AvroBoardCreatedEvent r ->
                    new BoardCreatedEvent(
                            r.getEventId(), r.getUserId(), r.getBoardId(), r.getTimestamp());
            case AvroColumnCreatedEvent r ->
                    new ColumnCreatedEvent(
                            r.getEventId(),
                            r.getUserId(),
                            r.getBoardId(),
                            r.getColumnId(),
                            r.getTimestamp());
            case AvroColumnDeletedEvent r ->
                    new ColumnDeletedEvent(
                            r.getEventId(),
                            r.getUserId(),
                            r.getBoardId(),
                            r.getColumnId(),
                            r.getTimestamp());
            case AvroSubtaskCreatedEvent r ->
                    new SubtaskCreatedEvent(
                            r.getEventId(),
                            r.getUserId(),
                            r.getBoardId(),
                            r.getTaskId(),
                            r.getSubtaskId(),
                            r.getTimestamp());
            default ->
                    throw new IllegalArgumentException(
                            "Unknown Avro record type: " + record.getClass().getName());
        };
    }
}
