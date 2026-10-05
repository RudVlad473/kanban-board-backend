package com.vrudenko.kanban_board.repository;

import java.util.List;

import com.vrudenko.kanban_board.entity.ColumnEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ColumnRepository extends JpaRepository<ColumnEntity, String> {
    // Explicit @Query, not a derived-name rename, so existing call sites keep compiling. The
    // (position, id) sort is a total order.
    @Query(
            "select c from ColumnEntity c where c.board.id = :boardId order by c.position asc, c.id asc")
    List<ColumnEntity> findAllByBoardId(@Param("boardId") String boardId);

    void deleteAllByBoardId(String boardId);

    // Doubles as the next-position probe for ColumnService.save: positions are contiguous from
    // zero, so the sibling count is the next append-at-end slot.
    long countByBoardId(String boardId);

    /**
     * Shift every column's {@code position} within one board by {@code delta}, for positions in the
     * inclusive [fromPosition, toPosition] range, as a single bulk statement.
     *
     * <p>The {@code board.id} predicate is mandatory, and bulk JPQL bypasses the persistence
     * context: callers must exclude the position of any column they still hold managed in the same
     * transaction.
     */
    @Modifying
    @Query(
            "update ColumnEntity c set c.position = c.position + :delta "
                    + "where c.board.id = :boardId "
                    + "and c.position >= :fromPosition and c.position <= :toPosition")
    void shiftPositions(
            @Param("boardId") String boardId,
            @Param("delta") int delta,
            @Param("fromPosition") int fromPosition,
            @Param("toPosition") int toPosition);
}
