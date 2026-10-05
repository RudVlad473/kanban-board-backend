package com.vrudenko.kanban_board.activitylog;

import java.util.LinkedHashMap;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
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
import com.vrudenko.kanban_board.event.avro.ActivityEventAvroMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turn each {@link ActivityEvent} published to {@link KafkaTopics#ACTIVITY} into a durable,
 * deduplicated {@link ActivityLogEntity} row via {@link ActivityLogRecorder}.
 *
 * <p>The listener thread has no security context and never re-verifies the mutation's
 * authorization, which was checked at publish time. This class therefore depends only on the event
 * package, {@link ActivityLogRecorder} and a plain {@link ObjectMapper}, and reads only the
 * server-derived identifiers each event carries.
 *
 * <p>Events arrive Avro-encoded and are mapped back to the domain event via {@link
 * ActivityEventAvroMapper} before the exhaustive switch runs.
 */
@Component
public class ActivityLogConsumer {
    public static final String GROUP_ID = "activity-log";

    @Autowired private ActivityLogRecorder activityLogRecorder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ActivityEventAvroMapper activityEventAvroMapper;

    @KafkaListener(topics = KafkaTopics.ACTIVITY, groupId = ActivityLogConsumer.GROUP_ID)
    public void onActivityEvent(SpecificRecord avroRecord) {
        ActivityEvent event = activityEventAvroMapper.toDomain(avroRecord);
        var mapped = deriveActionAndDetailIds(event);

        String detail;
        try {
            detail = objectMapper.writeValueAsString(mapped.detailIds());
        } catch (Exception e) {
            // A payload that cannot be serialised IS a genuine failure (unlike a duplicate) and
            // belongs on the retry-then-dead-letter path, so it is allowed to propagate.
            throw new IllegalStateException(
                    "Failed to serialise activity detail for eventId=" + event.eventId(), e);
        }

        var entity = new ActivityLogEntity();
        entity.setBoardId(event.boardId());
        entity.setUserId(event.userId());
        entity.setAction(mapped.action());
        entity.setDetail(detail);
        entity.setEventId(event.eventId());
        // Taken from the event's own timestamp, never a fresh clock reading, so the row reflects
        // when the mutation happened rather than when the consumer happened to catch up.
        entity.setCreatedAt(event.timestamp());

        activityLogRecorder.record(entity);
    }

    /**
     * Exhaustive switch over the sealed {@link ActivityEvent}, deliberately without a {@code
     * default} arm: a new event record becomes a compile error until the switch is updated, not a
     * silently absorbed message.
     */
    private ActionAndDetailIds deriveActionAndDetailIds(ActivityEvent event) {
        return switch (event) {
            case TaskCreatedEvent e -> {
                var ids = new LinkedHashMap<String, String>();
                ids.put("columnId", e.columnId());
                ids.put("taskId", e.taskId());
                yield new ActionAndDetailIds(ActivityAction.TASK_CREATED, ids);
            }
            case TaskMovedEvent e -> {
                var ids = new LinkedHashMap<String, String>();
                ids.put("taskId", e.taskId());
                ids.put("sourceColumnId", e.sourceColumnId());
                ids.put("targetColumnId", e.targetColumnId());
                yield new ActionAndDetailIds(ActivityAction.TASK_MOVED, ids);
            }
            case TaskDeletedEvent e -> {
                var ids = new LinkedHashMap<String, String>();
                ids.put("columnId", e.columnId());
                ids.put("taskId", e.taskId());
                yield new ActionAndDetailIds(ActivityAction.TASK_DELETED, ids);
            }
            case TaskUpdatedEvent e -> {
                var ids = new LinkedHashMap<String, String>();
                ids.put("columnId", e.columnId());
                ids.put("taskId", e.taskId());
                yield new ActionAndDetailIds(ActivityAction.TASK_UPDATED, ids);
            }
            case BoardCreatedEvent e ->
                    new ActionAndDetailIds(ActivityAction.BOARD_CREATED, new LinkedHashMap<>());
            case BoardUpdatedEvent e ->
                    new ActionAndDetailIds(ActivityAction.BOARD_UPDATED, new LinkedHashMap<>());
            case BoardDeletedEvent e ->
                    new ActionAndDetailIds(ActivityAction.BOARD_DELETED, new LinkedHashMap<>());
            case ColumnCreatedEvent e -> {
                var ids = new LinkedHashMap<String, String>();
                ids.put("columnId", e.columnId());
                yield new ActionAndDetailIds(ActivityAction.COLUMN_CREATED, ids);
            }
            case ColumnDeletedEvent e -> {
                var ids = new LinkedHashMap<String, String>();
                ids.put("columnId", e.columnId());
                yield new ActionAndDetailIds(ActivityAction.COLUMN_DELETED, ids);
            }
            case ColumnUpdatedEvent e -> {
                var ids = new LinkedHashMap<String, String>();
                ids.put("columnId", e.columnId());
                yield new ActionAndDetailIds(ActivityAction.COLUMN_UPDATED, ids);
            }
            case ColumnReorderedEvent e -> {
                // sourcePosition/targetPosition are ints, stringified here and parsed back by
                // HistoricalActivityEventReconstructor with the exact inverse conversion.
                var ids = new LinkedHashMap<String, String>();
                ids.put("columnId", e.columnId());
                ids.put("sourcePosition", String.valueOf(e.sourcePosition()));
                ids.put("targetPosition", String.valueOf(e.targetPosition()));
                yield new ActionAndDetailIds(ActivityAction.COLUMN_REORDERED, ids);
            }
            case SubtaskCreatedEvent e -> {
                var ids = new LinkedHashMap<String, String>();
                ids.put("taskId", e.taskId());
                ids.put("subtaskId", e.subtaskId());
                yield new ActionAndDetailIds(ActivityAction.SUBTASK_CREATED, ids);
            }
            case SubtaskUpdatedEvent e -> {
                var ids = new LinkedHashMap<String, String>();
                ids.put("taskId", e.taskId());
                ids.put("subtaskId", e.subtaskId());
                ids.put("isCompleted", String.valueOf(e.isCompleted()));
                yield new ActionAndDetailIds(ActivityAction.SUBTASK_UPDATED, ids);
            }
            case SubtaskDeletedEvent e -> {
                var ids = new LinkedHashMap<String, String>();
                ids.put("taskId", e.taskId());
                ids.put("subtaskId", e.subtaskId());
                yield new ActionAndDetailIds(ActivityAction.SUBTASK_DELETED, ids);
            }
        };
    }

    /**
     * Insertion-ordered on purpose: {@link LinkedHashMap}, so serialisation is byte-stable for a
     * given event type.
     *
     * <p>An immutable-set-backed factory map does not guarantee iteration order, which would make
     * the stored {@code detail} string vary run to run for identical input.
     */
    private record ActionAndDetailIds(
            ActivityAction action, LinkedHashMap<String, String> detailIds) {}
}
