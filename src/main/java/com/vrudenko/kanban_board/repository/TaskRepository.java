package com.vrudenko.kanban_board.repository;

import java.util.List;

import com.vrudenko.kanban_board.entity.TaskEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {
    // Explicit @Query, not a derived-name rename, so existing call sites keep compiling. The
    // (position, id) sort is a total order: a tie on `position` (possible under a concurrent
    // insert) resolves deterministically instead of falling back to undefined row order.
    @Query(
            "select t from TaskEntity t where t.column.id = :columnId order by t.position asc, t.id asc")
    List<TaskEntity> findAllByColumnId(@Param("columnId") String columnId);

    // Doubles as the next-position probe for TaskService.save: positions are contiguous from zero,
    // so the sibling count is the next append-at-end slot.
    long countByColumnId(String columnId);

    /**
     * Shift every task's position within one column by delta, for positions in the
     * inclusive [fromPosition, toPosition] range, as a single bulk statement.
     *
     * Decisions:
     *
     * One statement instead of a per-row loop keeps the statement count constant regardless of
     * sibling count and narrows the concurrency race window to one statement.
     *
     * The t.column.id predicate is mandatory: without it the statement renumbers the
     * entire tasks table across every user's boards.
     *
     * Bulk JPQL bypasses the persistence context, so Hibernate does not know a row updated this
     * way is stale in an already-managed entity. Callers must scope the range to exclude the
     * position of any entity they still hold managed in the same transaction (
     * com.vrudenko.kanban_board.service.TaskService.moveToColumn always excludes the moved task's
     * own pre-shift position).
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
