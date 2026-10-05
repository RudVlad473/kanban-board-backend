package com.vrudenko.kanban_board.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Truncate every domain table plus the two Spring Session tables to zero rows in a single
 * statement, leaving nonprod genuinely empty with no reseed.
 *
 * <p>Decisions:
 *
 * <p><b>Flyway's migration-bookkeeping table is deliberately absent from the TRUNCATE list</b> (its
 * literal name is not spelled out in this file either). It records which migrations have run;
 * truncating it would make the next boot believe none applied and re-run all of them against a
 * schema that already has them, which fails on a duplicate object or silently corrupts the
 * schema-history relationship Flyway depends on. {@code spring_session}/{@code
 * spring_session_attributes} ARE included: {@code spring.session.jdbc.initialize-schema=always}
 * recreates their schema if ever missing, and a stale session referencing a just-deleted user is
 * not a clean baseline.
 *
 * <p><b>A separate bean, not a private method on {@link ResetService}:</b> {@code @Transactional}
 * is implemented by a JDK/CGLIB proxy that wraps only calls arriving from OUTSIDE the bean. {@code
 * this.truncateAll()} would skip the proxy, the annotation would be silently ignored and {@code
 * TRUNCATE} would run with no transaction.
 *
 * <p><b>{@code flush()} before / {@code clear()} after</b> follows the bulk-statement discipline of
 * {@code TaskService#deleteAllByColumn}. {@code flush()} pushes pending session changes out before
 * the native TRUNCATE so none are silently discarded; {@code clear()} detaches every tracked
 * entity, since their rows were deleted out from under the persistence context and treating them as
 * managed would risk a stale write later in the same transaction.
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
