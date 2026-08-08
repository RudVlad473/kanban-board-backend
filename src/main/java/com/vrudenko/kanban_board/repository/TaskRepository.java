package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.TaskEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {
    // Explicit @Query (rather than a derived findAllByColumnIdOrderByPositionAscIdAsc rename) so
    // every existing call site keeps compiling unchanged. The (position, id) two-key sort is a
    // total order, not decoration: ids are creation-ordered ULIDs, so ties on `position` (see
    // TaskService#moveToColumn's Javadoc on the accepted concurrent-insert race) still resolve
    // deterministically instead of falling back to undefined database row order. Same precedent as
    // the activity feed's own two-key (createdAt, id) sort (Phase 3 Plan 03).
    @Query(
            "select t from TaskEntity t where t.column.id = :columnId order by t.position asc, t.id asc")
    List<TaskEntity> findAllByColumnId(@Param("columnId") String columnId);

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
