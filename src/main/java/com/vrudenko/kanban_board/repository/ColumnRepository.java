package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.ColumnEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ColumnRepository extends JpaRepository<ColumnEntity, String> {
    List<ColumnEntity> findAllByBoardId(String boardId);

    void deleteAllByBoardId(String boardId);

    // Doubles as the "next position" probe for ColumnService.save, exactly like
    // TaskRepository#countByColumnId — positions are kept contiguous from zero, so the current
    // sibling count is the next append-at-end slot.
    long countByBoardId(String boardId);

    /**
     * Board-scoped analog of {@link TaskRepository#shiftPositions} — see that method's Javadoc for
     * the full rationale (single bulk statement, mandatory parent-id predicate, bulk-JPQL
     * persistence-context bypass). Here the parent scope is {@code board.id} rather than {@code
     * column.id}.
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
