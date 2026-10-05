package com.vrudenko.kanban_board.repository;

import java.util.List;
import java.util.Optional;

import com.vrudenko.kanban_board.entity.BoardEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoardRepository extends JpaRepository<BoardEntity, String> {
    List<BoardEntity> findAllByUserId(String userId);

    boolean existsByUserIdAndName(String userId, String name);

    /**
     * Fetch-join the full board graph (columns, tasks, subtasks) in a single round trip, for {@code
     * GET /boards/{boardId}/full}.
     *
     * <p>{@code DISTINCT} de-duplicates the root {@link BoardEntity} row Hibernate would otherwise
     * return once per underlying SQL row (a multi-level {@code JOIN FETCH} multiplies rows once per
     * column/task/subtask combination). It collapses only the query's ROOT result, not nested
     * collections, which is why every collection in the chain is a {@code Set}, not a {@code List}.
     *
     * <p>Decisions:
     *
     * <p>Verified against a real Hibernate 6 build in this codebase, not assumed:
     *
     * <ol>
     *   <li>{@code MultipleBagFetchException} fires on two-or-more {@code List} (bag) collections
     *       fetch-joined <i>anywhere in one query</i>, not only on siblings under the same parent:
     *       {@code board.column} and {@code column.task}, at different depths, already trip it.
     *   <li>Even a single surviving {@code List} in a multi-level fetch chain accumulates
     *       duplicates: the root dedup {@code DISTINCT} provides does NOT extend to nested
     *       collections. With {@code board.column} still a {@code List}, a column referenced by 14
     *       underlying rows (7 sibling tasks with no subtasks, 1 task with 7 subtasks) appeared 14
     *       times in {@code board.getColumn()}, observed directly via the ordering-equivalence test
     *       before the fix.
     * </ol>
     *
     * <p>The fix: every collection in the board-&gt;column-&gt;task-&gt;subtasks chain ({@link
     * BoardEntity#getColumn()}, {@link com.vrudenko.kanban_board.entity.ColumnEntity#getTask()},
     * {@link com.vrudenko.kanban_board.entity.TaskEntity#getSubtasks()}) is a {@code Set}: zero
     * bags, so no {@code MultipleBagFetchException}, and {@code Set}'s identity-based deduplication
     * (safe because hashing an element during population never recurses; see each field's comment)
     * collapses the row-multiplication duplicates a {@code List} would keep. If a fourth {@code
     * List}-typed association is ever added anywhere in this fetch chain, both problems return.
     */
    @Query(
            "SELECT DISTINCT b FROM BoardEntity b "
                    + "LEFT JOIN FETCH b.column c "
                    + "LEFT JOIN FETCH c.task t "
                    + "LEFT JOIN FETCH t.subtasks s "
                    + "WHERE b.id = :boardId")
    Optional<BoardEntity> findByIdWithColumnsTasksAndSubtasks(@Param("boardId") String boardId);
}
