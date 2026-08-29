package com.vrudenko.kanban_board.repository;

import java.util.List;

import com.vrudenko.kanban_board.entity.ActivityLogEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code existsByEventId} is the idempotency fast path {@code ActivityLogRecorder} checks before
 * every insert (ACTLOG-03). {@code findAllByBoardId} is declared here, in the plan that owns this
 * file, even though the read endpoint that consumes it lands in a later plan — defining the whole
 * repository contract up front keeps the two plans on separate files and lets Spring Data validate
 * the derived query name at context startup, where this plan's own tests catch a typo. Ordering is
 * deliberately not encoded in the method name; the caller supplies it through {@link Pageable}.
 *
 * <p>{@code existsByEventId}'s parameter is a {@link String} (GAP-07) — its dedupe semantics are
 * unchanged, only the type of the key being compared.
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLogEntity, String> {
    boolean existsByEventId(String eventId);

    Page<ActivityLogEntity> findAllByBoardId(String boardId, Pageable pageable);

    // Explicit bulk JPQL delete (quick task 260829-ii3), not the derived deleteAllByUserIdIn:
    // Spring Data JPA implements a derived deleteBy... method without @Modifying as a SELECT
    // followed by one entityManager.remove() per matched row, which would scale query count with
    // the number of activity rows for the targeted users -- exactly the pattern this codebase's
    // Epic 2 scope exists to eliminate. Mirrors SubtaskRepository.deleteAllByTaskIdIn.
    @Modifying
    @Query("delete from ActivityLogEntity a where a.userId in :userIds")
    void deleteAllByUserIdIn(@Param("userIds") List<String> userIds);
}
