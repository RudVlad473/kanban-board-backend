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
 * every insert. {@code findAllByBoardId} leaves ordering to the caller through {@link Pageable},
 * deliberately not encoded in the method name.
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLogEntity, String> {
    boolean existsByEventId(String eventId);

    Page<ActivityLogEntity> findAllByBoardId(String boardId, Pageable pageable);

    // Explicit bulk JPQL delete, not the derived deleteAllByUserIdIn: a derived deleteBy... without
    // @Modifying runs a SELECT then one entityManager.remove() per row, scaling the query count
    // with the targeted users' activity rows, the N+1 pattern this codebase's JPA work removed.
    @Modifying
    @Query("delete from ActivityLogEntity a where a.userId in :userIds")
    void deleteAllByUserIdIn(@Param("userIds") List<String> userIds);
}
