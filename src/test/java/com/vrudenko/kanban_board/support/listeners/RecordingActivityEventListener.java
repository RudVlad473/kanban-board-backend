package com.vrudenko.kanban_board.support.listeners;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.vrudenko.kanban_board.event.ActivityEvent;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Real Spring wiring, not a mock (CODE_STYLE rule 4), that records every ActivityEvent
 * observed after its enclosing transaction commits.
 *
 * Generic over ActivityEvent, so it serves every event type this application publishes.
 */
@Component
public class RecordingActivityEventListener {
    private final List<ActivityEvent> recorded = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivityEvent(ActivityEvent event) {
        recorded.add(event);
    }

    public List<ActivityEvent> getRecorded() {
        return recorded;
    }

    public void clear() {
        recorded.clear();
    }
}
