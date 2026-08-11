package com.vrudenko.kanban_board.activitylog;

import java.util.Map;

import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.event.ActivityEvent;
import com.vrudenko.kanban_board.event.BoardCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnDeletedEvent;
import com.vrudenko.kanban_board.event.SubtaskCreatedEvent;
import com.vrudenko.kanban_board.event.TaskCreatedEvent;
import com.vrudenko.kanban_board.event.TaskDeletedEvent;
import com.vrudenko.kanban_board.event.TaskMovedEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The exact inverse of {@code ActivityLogConsumer.deriveActionAndDetailIds}: turns a persisted
 * {@link ActivityLogEntity} row back into the {@link ActivityEvent} that produced it. Test-only
 * verification tooling (SCHEMA-06) -- this is not production behaviour the application ever needs,
 * so it lives in the test source set rather than {@code src/main}.
 *
 * <p>Field recovery is total by construction: {@code eventId}, {@code userId}, {@code boardId} and
 * {@code timestamp} (from the row's {@code createdAt} -- the consumer never takes it from a fresh
 * clock reading, see {@code ActivityLogConsumer}'s Javadoc) come straight from columns; every
 * type-specific identifier comes from the row's {@code detail} JSON object, whose key set is fixed
 * per {@link com.vrudenko.kanban_board.entity.ActivityAction} and is read here by the exact same
 * key names {@code ActivityLogConsumer.deriveActionAndDetailIds} writes.
 *
 * <p>Deliberately never substitutes a default for an absent {@code detail} key: a row whose detail
 * cannot produce a complete event is precisely the finding SCHEMA-06's rehearsal exists to surface,
 * and defaulting it away would hide that finding rather than reveal it. {@link #reconstruct} throws
 * instead, naming the row's {@code eventId}, its action and the missing key.
 *
 * <p>Dispatch is an exhaustive {@code switch} over {@link
 * com.vrudenko.kanban_board.entity.ActivityAction} with no {@code default} arm, mirroring {@code
 * ActivityLogConsumer.deriveActionAndDetailIds} and {@code ActivityEventAvroMapper.toAvro} --
 * adding another action is then a compile error here until this switch is updated, rather than a
 * silently unmappable row at runtime.
 */
public class HistoricalActivityEventReconstructor {

    private final ObjectMapper objectMapper;

    /**
     * Takes the same {@link ObjectMapper} bean {@code ActivityLogConsumer} parses {@code detail}
     * with, rather than constructing a fresh one -- reconstruction should read {@code detail} back
     * exactly the way the shipped consumer's own JSON handling behaves.
     */
    public HistoricalActivityEventReconstructor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ActivityEvent reconstruct(ActivityLogEntity row) {
        Map<String, String> detail = parseDetail(row);
        return switch (row.getAction()) {
            case TASK_CREATED ->
                    new TaskCreatedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            requireKey(row, detail, "columnId"),
                            requireKey(row, detail, "taskId"),
                            row.getCreatedAt());
            case TASK_MOVED ->
                    new TaskMovedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            requireKey(row, detail, "taskId"),
                            requireKey(row, detail, "sourceColumnId"),
                            requireKey(row, detail, "targetColumnId"),
                            row.getCreatedAt());
            case TASK_DELETED ->
                    new TaskDeletedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            requireKey(row, detail, "columnId"),
                            requireKey(row, detail, "taskId"),
                            row.getCreatedAt());
            case BOARD_CREATED ->
                    new BoardCreatedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            row.getCreatedAt());
            case COLUMN_CREATED ->
                    new ColumnCreatedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            requireKey(row, detail, "columnId"),
                            row.getCreatedAt());
            case COLUMN_DELETED ->
                    new ColumnDeletedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            requireKey(row, detail, "columnId"),
                            row.getCreatedAt());
            case SUBTASK_CREATED ->
                    new SubtaskCreatedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            requireKey(row, detail, "taskId"),
                            requireKey(row, detail, "subtaskId"),
                            row.getCreatedAt());
        };
    }

    private Map<String, String> parseDetail(ActivityLogEntity row) {
        try {
            return objectMapper.readValue(
                    row.getDetail(), new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to parse detail JSON for eventId=" + row.getEventId(), e);
        }
    }

    /**
     * Never substitutes a default for a missing key -- see the class Javadoc for why. Throws naming
     * the row's {@code eventId}, its action, and the specific missing key, so a rehearsal failure
     * is immediately actionable rather than a bare {@code NullPointerException}.
     */
    private String requireKey(ActivityLogEntity row, Map<String, String> detail, String key) {
        String value = detail.get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "Historical row with eventId="
                            + row.getEventId()
                            + " and action="
                            + row.getAction()
                            + " is missing required detail key '"
                            + key
                            + "' -- cannot reconstruct a complete event.");
        }
        return value;
    }
}
