package com.vrudenko.kanban_board.activitylog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.event.ActivityEvent;
import com.vrudenko.kanban_board.event.BoardCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnCreatedEvent;
import com.vrudenko.kanban_board.event.TaskCreatedEvent;
import com.vrudenko.kanban_board.event.TaskDeletedEvent;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes every {@link ActivityEvent} published to {@link KafkaTopics#ACTIVITY} and turns it into
 * a durable, deduplicated {@link ActivityLogEntity} row via {@link ActivityLogRecorder}
 * (ACTLOG-02). Runs on a Kafka listener container thread, which carries no security context of its
 * own and never re-verifies who was allowed to trigger the underlying mutation — that check already
 * happened once, at publish time, through the same access-control chain every mutating endpoint
 * goes through. This class therefore depends on nothing but the event package, {@link
 * ActivityLogRecorder} and a plain {@link ObjectMapper}: it never loads a task, column, board or
 * user, and never reads a field a user typed by hand (D-01) — only the server-derived identifiers
 * each event already carries.
 */
@Component
public class ActivityLogConsumer {
    public static final String GROUP_ID = "activity-log";

    @Autowired private ActivityLogRecorder activityLogRecorder;
    @Autowired private ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.ACTIVITY, groupId = ActivityLogConsumer.GROUP_ID)
    public void onActivityEvent(ActivityEvent event) {
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
     * Exhaustive switch over the sealed {@link ActivityEvent} — deliberately no {@code default}
     * arm. Adding a sixth event record is then a compile error until this switch is updated,
     * turning a future missed event type into a build failure instead of a silently absorbed
     * message.
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
            case BoardCreatedEvent e ->
                    new ActionAndDetailIds(ActivityAction.BOARD_CREATED, new LinkedHashMap<>());
            case ColumnCreatedEvent e -> {
                var ids = new LinkedHashMap<String, String>();
                ids.put("columnId", e.columnId());
                yield new ActionAndDetailIds(ActivityAction.COLUMN_CREATED, ids);
            }
        };
    }

    /**
     * Insertion-ordered on purpose: {@link LinkedHashMap}, never an immutable-set-backed factory
     * map, so serialisation is byte-stable for a given event type. A same-arity static factory
     * method taking varargs key/value pairs does not guarantee iteration order, which would make
     * the stored {@code detail} string vary run to run for identical input.
     */
    private record ActionAndDetailIds(
            ActivityAction action, LinkedHashMap<String, String> detailIds) {}
}
