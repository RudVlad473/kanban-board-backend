package com.vrudenko.kanban_board.event;

import java.time.Instant;

/**
 * Announce that a subtask was updated (title and/or completion state), carrying the post-mutation
 * isCompleted boolean.
 *
 * isCompleted is derived state read back from the managed entity after the mutation, not
 * user-authored text echoed from the request, so it is admissible.
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
