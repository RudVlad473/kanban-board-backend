package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.BoardEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardRepository extends JpaRepository<BoardEntity, String> {
    List<BoardEntity> findAllByUserId(String userId);

    boolean existsByUserIdAndName(String userId, String name);

    /**
     * Fetch-joins the full board graph (columns -&gt; tasks -&gt; subtasks) in a single round trip,
     * for GAP-04's nested read ({@code GET /boards/{boardId}/full}). {@code DISTINCT} is required
     * to de-duplicate the root-row multiplication a multi-level {@code JOIN FETCH} produces (each
     * column/task/subtask combination otherwise repeats the board row).
     *
     * <p><b>{@code MultipleBagFetchException} -- corrected finding.</b> This plan's original design
     * rationale assumed Hibernate's {@code MultipleBagFetchException} only fires on two-or-more
     * {@code List} (bag) collections fetched from the <i>same</i> parent, and that a linear chain
     * at three different nesting depths would therefore be exempt. That assumption was wrong,
     * verified against a real Hibernate 6 build in this codebase: fetch-joining even two {@code
     * List} collections anywhere in one query -- {@code board.column} and {@code column.task},
     * despite being at different nesting depths, not siblings -- throws {@code
     * MultipleBagFetchException}. The actual restriction is "at most one {@code List}-typed
     * collection per query, full stop." The fix applied here: {@link
     * com.vrudenko.kanban_board.entity.ColumnEntity#getTask()} and {@link
     * com.vrudenko.kanban_board.entity.TaskEntity#getSubtasks()} were changed from {@code List} to
     * {@code Set} (see their field-level comments for why this is safe against the entities' Lombok
     * {@code equals}/{@code hashCode}), leaving {@link BoardEntity#getColumn()} as the one {@code
     * List} the query is allowed. If a second {@code List}-typed association is ever added anywhere
     * in this query's fetch chain, this breaks again.
     */
    @Query(
            "SELECT DISTINCT b FROM BoardEntity b "
                    + "LEFT JOIN FETCH b.column c "
                    + "LEFT JOIN FETCH c.task t "
                    + "LEFT JOIN FETCH t.subtasks s "
                    + "WHERE b.id = :boardId")
    Optional<BoardEntity> findByIdWithColumnsTasksAndSubtasks(@Param("boardId") String boardId);
}
