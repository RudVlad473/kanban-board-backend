package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.TaskEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {
    List<TaskEntity> findAllByColumnId(String columnId);

    // countByColumnId doubles as the "next position" probe for TaskService.save: since positions
    // are kept contiguous from zero by every mutation in this class, the current sibling count is
    // exactly the next append-at-end slot. No separate max-position query is needed.
    long countByColumnId(String columnId);

    /**
     * Bulk-shifts every task's {@code position} within one column by {@code delta}, for positions
     * in the inclusive [fromPosition, toPosition] range. A single bulk statement rather than a
     * per-row loop, mirroring {@link SubtaskRepository#deleteAllByTaskIdIn}'s precedent: statement
     * count stays constant regardless of sibling count, and the concurrency race window is the
     * width of one statement instead of a read-modify-write loop.
     *
     * <p>The {@code t.column.id} predicate is mandatory — a shift statement without it renumbers
     * the entire {@code tasks} table across every user's boards, not just the intended column.
     *
     * <p>Bulk JPQL bypasses the persistence context: Hibernate does not know a row it updates this
     * way is stale in any already-managed entity. Callers must scope the [fromPosition, toPosition]
     * range to exclude the position of any entity they still hold managed in the same transaction
     * (see {@link com.vrudenko.kanban_board.service.TaskService#moveToColumn}, which always
     * excludes the moved task's own pre-shift position from every range it passes here).
     */
    @Modifying
    @Query(
            "update TaskEntity t set t.position = t.position + :delta "
                    + "where t.column.id = :columnId "
                    + "and t.position >= :fromPosition and t.position <= :toPosition")
    void shiftPositions(
            @Param("columnId") String columnId,
            @Param("delta") int delta,
            @Param("fromPosition") int fromPosition,
            @Param("toPosition") int toPosition);
}
