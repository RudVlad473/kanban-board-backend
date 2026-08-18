package com.vrudenko.kanban_board.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Plan 08-02 (RESET-01): the Postgres half of the nonprod reset. Truncates every domain table plus
 * the two Spring Session tables to zero rows in a single statement -- D-03's "genuinely empty, no
 * reseed" target state.
 *
 * <p><b>Flyway's own migration-bookkeeping table is deliberately absent from the TRUNCATE list</b>
 * (a negative grep gate in this plan's own verification enforces this, so its literal name is
 * deliberately not spelled out here either). It is not a domain table -- it records which
 * migrations have already run. Truncating it would make the next application boot believe no
 * migration has ever applied and re-run every one of them (V1-V7) against a schema that already has
 * them, which either fails outright on a duplicate object or silently corrupts the
 * schema-history/schema-shape relationship Flyway depends on. {@code spring_session}/{@code
 * spring_session_attributes} ARE included: {@code spring.session.jdbc.initialize-schema=always}
 * (application.properties) recreates those tables' schema if they were ever missing, so truncating
 * their rows is safe and -- per D-03 -- desirable, since a stale session referencing a just-deleted
 * user is not a clean baseline either.
 *
 * <p><b>Why a separate bean, not a private method on {@link ResetService}:</b> Spring's
 * {@code @Transactional} is implemented via a JDK/CGLIB proxy that wraps method calls arriving from
 * OUTSIDE the bean. A method on {@code ResetService} calling {@code this.truncateAll()} would never
 * go through that proxy, so the transaction annotation would be silently ignored and {@code
 * TRUNCATE} would run with no transaction at all. Putting it on a second, independently-proxied
 * bean and calling it from {@code ResetService} through the normal Spring-managed reference
 * sidesteps that self-invocation trap entirely.
 *
 * <p><b>{@code flush()} before / {@code clear()} after:</b> mirrors {@code
 * TaskService#deleteAllByColumn}'s documented discipline for a bulk statement that bypasses the
 * persistence context. {@code flush()} pushes any pending changes in the current session out to the
 * database before the native TRUNCATE runs, so nothing pending is silently discarded by the
 * TRUNCATE's own implicit transaction semantics; {@code clear()} detaches every entity Hibernate
 * was still tracking afterward, since every row those entities represented has just been deleted
 * out from under the persistence context and continuing to treat them as managed would risk a stale
 * write on whatever runs next in the same transaction.
 */
@Profile("nonprod")
@Service
public class ResetTruncateService {
    @PersistenceContext private EntityManager entityManager;

    @Transactional
    public void truncateAll() {
        entityManager.flush();

        entityManager
                .createNativeQuery(
                        "TRUNCATE TABLE users, boards, columns, tasks, subtasks, activity_log,"
                                + " spring_session_attributes, spring_session CASCADE")
                .executeUpdate();

        entityManager.clear();
    }
}
