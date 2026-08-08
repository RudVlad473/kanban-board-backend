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
     * for GAP-04's nested read ({@code GET /boards/{boardId}/full}). {@code DISTINCT} de-duplicates
     * the root {@link BoardEntity} row Hibernate would otherwise return once per underlying SQL row
     * (a multi-level {@code JOIN FETCH} multiplies rows once per column/task/subtask combination)
     * -- verified this only collapses the query's ROOT result, not nested collections, which is why
     * every collection below is a {@code Set}, not a {@code List} (see next paragraph).
     *
     * <p><b>Two corrected findings from this plan's original design rationale, both verified
     * against a real Hibernate 6 build in this codebase, not assumed:</b>
     *
     * <ol>
     *   <li>{@code MultipleBagFetchException} fires on two-or-more {@code List} (bag) collections
     *       fetch-joined <i>anywhere in one query</i>, not only on siblings hanging off the same
     *       parent as originally assumed -- {@code board.column} and {@code column.task}, despite
     *       being at different nesting depths, already trips it.
     *   <li>Even a single surviving {@code List} collection in a multi-level fetch chain still
     *       accumulates duplicate elements: the ROOT entity dedup {@code DISTINCT} provides does
     *       NOT extend to collections nested under that root. With {@code board.column} still typed
     *       {@code List}, a column referenced by 14 underlying rows (7 sibling tasks with no
     *       subtasks, 1 task with 7 subtasks) appeared 14 times in {@code board.getColumn()} --
     *       observed directly via this plan's ordering-equivalence test before the fix.
     * </ol>
     *
     * <p>The fix: every collection in the board-&gt;column-&gt;task-&gt;subtasks chain ({@link
     * BoardEntity#getColumn()}, {@link com.vrudenko.kanban_board.entity.ColumnEntity#getTask()},
     * {@link com.vrudenko.kanban_board.entity.TaskEntity#getSubtasks()}) is {@code Set}, not {@code
     * List} -- zero bags, so {@code MultipleBagFetchException} cannot fire, and {@code Set}'s
     * identity-based deduplication (safe here -- see each field's own comment for why hashing an
     * element during population never recurses) collapses the row-multiplication duplicates a
     * {@code List} would keep. If a fourth {@code List}-typed association is ever added anywhere in
     * this query's fetch chain, both problems return.
     */
    @Query(
            "SELECT DISTINCT b FROM BoardEntity b "
                    + "LEFT JOIN FETCH b.column c "
                    + "LEFT JOIN FETCH c.task t "
                    + "LEFT JOIN FETCH t.subtasks s "
                    + "WHERE b.id = :boardId")
    Optional<BoardEntity> findByIdWithColumnsTasksAndSubtasks(@Param("boardId") String boardId);
}
