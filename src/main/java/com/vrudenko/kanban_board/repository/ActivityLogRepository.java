package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code existsByEventId} is the idempotency fast path {@code ActivityLogRecorder} checks before
 * every insert (ACTLOG-03). {@code findAllByBoardId} is declared here, in the plan that owns this
 * file, even though the read endpoint that consumes it lands in a later plan — defining the whole
 * repository contract up front keeps the two plans on separate files and lets Spring Data validate
 * the derived query name at context startup, where this plan's own tests catch a typo. Ordering is
 * deliberately not encoded in the method name; the caller supplies it through {@link Pageable}.
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLogEntity, String> {
    boolean existsByEventId(UUID eventId);

    Page<ActivityLogEntity> findAllByBoardId(String boardId, Pageable pageable);
}
