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
 * <p>{@code DataIntegrityViolationException} is Spring's translation for the entire SQL "integrity
 * constraint violation" class (23xxx) -- not only the unique-constraint race this method is built
 * to absorb, but also, for example, a {@code NOT NULL} violation from a structurally-valid-but-
 * semantically-null event field. The catch block below re-checks {@code existsByEventId} before
 * deciding the exception was the intended duplicate race: if the row is present under this {@code
 * eventId} after the failed insert, the race happened as expected and the exception is absorbed; if
 * it is still absent, the violation was caused by something else entirely and must be rethrown so
 * it reaches {@code DefaultErrorHandler} and gets retried/dead-lettered like any other genuine
 * failure, rather than being silently dropped.
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
            // Only absorb this if the row is now actually present under this eventId -- otherwise
            // the violation was caused by something else (e.g. a NOT NULL violation on a
            // semantically-invalid event) and must escape so it is retried/dead-lettered, not
            // silently dropped as if it were a harmless duplicate.
            if (!activityLogRepository.existsByEventId(entry.getEventId())) {
                throw e;
            }
        }
    }
}
