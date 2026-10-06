package com.vrudenko.kanban_board.activitylog;

import java.util.Map;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The exact inverse of ActivityLogConsumer.deriveActionAndDetailIds: turns a persisted
 * ActivityLogEntity row back into the ActivityEvent that produced it.
 *
 * Test-only verification tooling, so it lives in the test source set, not src/main.
 * Field recovery is total by construction: eventId, userId, boardId and
 * timestamp (from the row's createdAt) come from columns; every type-specific
 * identifier comes from the row's detail JSON, read by the key names the consumer writes.
 *
 * Decisions: it never substitutes a default for an absent detail key. A row whose detail
 * cannot produce a complete event is the finding the rehearsal exists to surface, and defaulting it
 * away would hide that. reconstruct throws instead, naming the row's eventId, its
 * action and the missing key. Dispatch is an exhaustive switch over
 * com.vrudenko.kanban_board.entity.ActivityAction with no default arm, mirroring
 * ActivityLogConsumer.deriveActionAndDetailIds and ActivityEventAvroMapper.toAvro, so
 * adding an action is a compile error here until the switch is updated.
 */
public class HistoricalActivityEventReconstructor {

    private final ObjectMapper objectMapper;

    /**
     * Takes the same ObjectMapper bean ActivityLogConsumer parses detail
     * with, so reconstruction reads detail exactly as the shipped consumer does.
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
            case TASK_UPDATED ->
                    new TaskUpdatedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            requireKey(row, detail, "columnId"),
                            requireKey(row, detail, "taskId"),
                            row.getCreatedAt());
            case BOARD_UPDATED ->
                    new BoardUpdatedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            row.getCreatedAt());
            case BOARD_DELETED ->
                    new BoardDeletedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            row.getCreatedAt());
            case COLUMN_UPDATED ->
                    new ColumnUpdatedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            requireKey(row, detail, "columnId"),
                            row.getCreatedAt());
            case COLUMN_REORDERED ->
                    new ColumnReorderedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            requireKey(row, detail, "columnId"),
                            Integer.parseInt(requireKey(row, detail, "sourcePosition")),
                            Integer.parseInt(requireKey(row, detail, "targetPosition")),
                            row.getCreatedAt());
            case SUBTASK_CREATED ->
                    new SubtaskCreatedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            requireKey(row, detail, "taskId"),
                            requireKey(row, detail, "subtaskId"),
                            row.getCreatedAt());
            case SUBTASK_UPDATED ->
                    new SubtaskUpdatedEvent(
                            row.getEventId(),
                            row.getUserId(),
                            row.getBoardId(),
                            requireKey(row, detail, "taskId"),
                            requireKey(row, detail, "subtaskId"),
                            Boolean.parseBoolean(requireKey(row, detail, "isCompleted")),
                            row.getCreatedAt());
            case SUBTASK_DELETED ->
                    new SubtaskDeletedEvent(
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
     * Throws naming the row's eventId, its action and the missing key, so a rehearsal
     * failure is actionable, not a bare NullPointerException. Never substitutes a default
     * (see the class Javadoc).
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
