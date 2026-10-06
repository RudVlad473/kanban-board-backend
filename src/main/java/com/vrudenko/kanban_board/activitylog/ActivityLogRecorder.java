package com.vrudenko.kanban_board.activitylog;

import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Insert an ActivityLogEntity row idempotently: a redelivered eventId completes
 * normally and never escapes as an exception.
 *
 * Decisions:
 *
 * Two layers absorb a duplicate. The existsByEventId fast path handles ordinary
 * sequential redelivery with no exception; the DataIntegrityViolationException catch is the
 * backstop for the race between that check and the insert, arbitrated by the database's unique
 * constraint on event_id. Whatever escapes this method is retried by
 * DefaultErrorHandler and eventually dead-lettered, so a duplicate escaping here would exhaust
 * three retries and pollute the dead-letter topic with routine, non-poison traffic.
 *
 * The catch re-checks existsByEventId. DataIntegrityViolationException is
 * Spring's translation of the whole SQL integrity-violation class (23xxx), not only the
 * unique-constraint race: a NOT NULL violation from a structurally valid but semantically
 * null event field raises it too. If the row is present under this eventId, the race
 * happened as expected and the exception is absorbed; if absent, something else caused it and it is
 * rethrown, to be retried or dead-lettered like any genuine failure rather than silently dropped.
 *
 * No declarative-transaction annotation, on purpose. A constraint violation marks a
 * surrounding transaction rollback-only, so catching it inside one would not suppress the failure:
 * the commit at method exit would still fail and the duplicate would still reach the listener's
 * error path. Undecorated, Spring Data's own per-call transaction owns and rolls back the failed
 * insert, so the catch resumes into a clean state. saveAndFlush is required rather than
 * save so the violation surfaces at this catch site, not at a later, unrelated flush.
 */
@Service
public class ActivityLogRecorder {
    @Autowired private ActivityLogRepository activityLogRepository;

    public void record(ActivityLogEntity entry) {
        if (activityLogRepository.existsByEventId(entry.getEventId())) {
            return;
        }

        try {
            activityLogRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            // Absorb only if the row is now present; otherwise something else violated a
            // constraint and must escape (see the class Javadoc).
            if (!activityLogRepository.existsByEventId(entry.getEventId())) {
                throw e;
            }
        }
    }
}
