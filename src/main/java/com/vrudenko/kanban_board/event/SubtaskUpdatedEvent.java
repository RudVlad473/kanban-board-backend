package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announces that a subtask was updated (title and/or completion state). Carries identifiers, actor,
 * timestamp and the server-derived, post-mutation {@code isCompleted} boolean only (fork D-B,
 * resolved B2) — never the subtask's title, per the event package's no-user-authored-content rule.
 * {@code isCompleted} is admissible under D-01 because it is derived state read back from the
 * managed entity after the mutation, not user-authored text echoed from the request.
 */
public record SubtaskUpdatedEvent(
        String eventId,
        String userId,
        String boardId,
        String taskId,
        String subtaskId,
        boolean isCompleted,
        Instant timestamp)
        implements ActivityEvent {}
