package com.vrudenko.kanban_board.activitylog;

import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Idempotent, insert-only persist for a mapped {@link ActivityLogEntity} row (ACTLOG-03, D-05). A
 * redelivered {@code eventId} completes normally through one of two layers, and neither one ever
 * escapes this method as an exception: the {@code existsByEventId} fast path handles the ordinary
 * sequential redelivery case with zero exceptions in the common path, and the {@link
 * DataIntegrityViolationException} catch below is the backstop for the narrow race window between
 * that check and the insert, arbitrated by the database's own unique constraint on {@code
 * event_id}. Anything that escapes this method is what {@code DefaultErrorHandler} retries and
 * eventually dead-letters, so a duplicate escaping here would exhaust three retries and pollute the
 * dead-letter topic with routine, non-poison traffic — exactly what D-05 forbids.
 *
 * <p>This method deliberately carries no declarative-transaction annotation. A constraint violation
 * marks any surrounding transaction rollback-only, so catching it inside one and completing
 * normally would not actually suppress the failure — the commit at method exit would still fail,
 * and the duplicate would still escape into the listener's error path, relocating rather than
 * avoiding exactly the outcome this method exists to prevent. Leaving the method undecorated lets
 * Spring Data's own per-call transaction own — and roll back — the failed insert on its own, so the
 * catch block below resumes into a clean state. {@code saveAndFlush} is required rather than {@code
 * save} because the insert must run inside this call for the constraint violation to surface at the
 * catch site here, instead of at some later, unrelated flush.
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
            // Backstop: the exists-check above raced with a concurrent redelivery and lost.
            // Still a duplicate, still completed normally per D-05 -- never escapes this method.
        }
    }
}
