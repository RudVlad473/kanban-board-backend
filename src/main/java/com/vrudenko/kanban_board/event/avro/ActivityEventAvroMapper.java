package com.vrudenko.kanban_board.event.avro;

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

import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

/**
 * Translate between the plain ActivityEvent sealed interface and the Avro-generated
 * SpecificRecord classes, one per event type, generated from src/main/avro/*.avsc.
 *
 * Decisions:
 *
 * A plain @Component, not a MapStruct @Mapper interface: MapStruct generates a
 * mapper for one concrete source type to one concrete target type, and cannot generate one whose
 * source is a sealed interface dispatched by pattern matching over 14 unrelated record shapes. Do
 * not "fix" this into a @Mapper; it is not possible.
 *
 * No conversion is needed below. The timestamp-millis logical type on timestamp,
 * used by every .avsc schema, generates a native java.time.Instant accessor
 * (confirmed by inspection under gradle-avro-plugin 1.9.1 + Avro 1.12.1; see the comment next to
 * the avro plugin declaration in build.gradle). eventId is a plain Base36 string
 * with no logical type, and Avro's native int/boolean cover
 * ColumnReorderedEvent's positions and SubtaskUpdatedEvent's completion flag, so every
 * field passes straight through.
 */
@Component
public class ActivityEventAvroMapper {

    /**
     * Exhaustive switch over the sealed ActivityEvent, deliberately without a
     * default arm: a new event record is a compile error until the switch is updated, not an
     * unmappable event at runtime.
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
            case TaskUpdatedEvent e ->
                    AvroTaskUpdatedEvent.newBuilder()
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
            case BoardUpdatedEvent e ->
                    AvroBoardUpdatedEvent.newBuilder()
                            .setEventId(e.eventId())
                            .setUserId(e.userId())
                            .setBoardId(e.boardId())
                            .setTimestamp(e.timestamp())
                            .build();
            case BoardDeletedEvent e ->
                    AvroBoardDeletedEvent.newBuilder()
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
            case ColumnUpdatedEvent e ->
                    AvroColumnUpdatedEvent.newBuilder()
                            .setEventId(e.eventId())
                            .setUserId(e.userId())
                            .setBoardId(e.boardId())
                            .setColumnId(e.columnId())
                            .setTimestamp(e.timestamp())
                            .build();
            case ColumnReorderedEvent e ->
                    AvroColumnReorderedEvent.newBuilder()
                            .setEventId(e.eventId())
                            .setUserId(e.userId())
                            .setBoardId(e.boardId())
                            .setColumnId(e.columnId())
                            .setSourcePosition(e.sourcePosition())
                            .setTargetPosition(e.targetPosition())
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
            case SubtaskUpdatedEvent e ->
                    AvroSubtaskUpdatedEvent.newBuilder()
                            .setEventId(e.eventId())
                            .setUserId(e.userId())
                            .setBoardId(e.boardId())
                            .setTaskId(e.taskId())
                            .setSubtaskId(e.subtaskId())
                            .setIsCompleted(e.isCompleted())
                            .setTimestamp(e.timestamp())
                            .build();
            case SubtaskDeletedEvent e ->
                    AvroSubtaskDeletedEvent.newBuilder()
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
     * Dispatch on the 14 generated Avro types. Unlike toAvro(ActivityEvent), a
     * default arm is required, and it throws on an unrecognised record.
     *
     * SpecificRecord is an ordinary interface, not sealed, so the compiler cannot prove
     * exhaustiveness here.
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
            case AvroTaskUpdatedEvent r ->
                    new TaskUpdatedEvent(
                            r.getEventId(),
                            r.getUserId(),
                            r.getBoardId(),
                            r.getColumnId(),
                            r.getTaskId(),
                            r.getTimestamp());
            case AvroBoardCreatedEvent r ->
                    new BoardCreatedEvent(
                            r.getEventId(), r.getUserId(), r.getBoardId(), r.getTimestamp());
            case AvroBoardUpdatedEvent r ->
                    new BoardUpdatedEvent(
                            r.getEventId(), r.getUserId(), r.getBoardId(), r.getTimestamp());
            case AvroBoardDeletedEvent r ->
                    new BoardDeletedEvent(
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
            case AvroColumnUpdatedEvent r ->
                    new ColumnUpdatedEvent(
                            r.getEventId(),
                            r.getUserId(),
                            r.getBoardId(),
                            r.getColumnId(),
                            r.getTimestamp());
            case AvroColumnReorderedEvent r ->
                    new ColumnReorderedEvent(
                            r.getEventId(),
                            r.getUserId(),
                            r.getBoardId(),
                            r.getColumnId(),
                            r.getSourcePosition(),
                            r.getTargetPosition(),
                            r.getTimestamp());
            case AvroSubtaskCreatedEvent r ->
                    new SubtaskCreatedEvent(
                            r.getEventId(),
                            r.getUserId(),
                            r.getBoardId(),
                            r.getTaskId(),
                            r.getSubtaskId(),
                            r.getTimestamp());
            case AvroSubtaskUpdatedEvent r ->
                    new SubtaskUpdatedEvent(
                            r.getEventId(),
                            r.getUserId(),
                            r.getBoardId(),
                            r.getTaskId(),
                            r.getSubtaskId(),
                            r.getIsCompleted(),
                            r.getTimestamp());
            case AvroSubtaskDeletedEvent r ->
                    new SubtaskDeletedEvent(
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
