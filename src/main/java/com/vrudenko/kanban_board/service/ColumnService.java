package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import com.vrudenko.kanban_board.config.EventIdGenerator;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.ReorderColumnRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.UpdateColumnRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import com.vrudenko.kanban_board.event.ColumnCreatedEvent;
import com.vrudenko.kanban_board.event.ColumnDeletedEvent;
import com.vrudenko.kanban_board.mapper.ColumnMapper;
import com.vrudenko.kanban_board.repository.ColumnRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class ColumnService {
    @Autowired private ColumnRepository columnRepository;

    @Autowired private ColumnMapper columnMapper;

    @Autowired private TaskService taskService;

    @Autowired private OwnershipVerifierService ownershipVerifierService;

    @Autowired private EntityManager entityManager;

    @Autowired private ApplicationEventPublisher eventPublisher;

    @Autowired private EventIdGenerator eventIdGenerator;

    /**
     * Deletes every column (and, per column, all of its tasks/subtasks via {@link
     * TaskService#deleteAllByColumn}) belonging to {@code boardId}.
     *
     * <p><b>Derived-delete vs. bulk-delete asymmetry:</b> {@code
     * columnRepository.deleteAllByBoardId} is a Spring Data JPA <i>derived</i> delete method (not
     * an explicit {@code @Modifying @Query}), which Spring Data implements as fetch-then-{@code
     * remove()}-per-entity, not a single bulk SQL statement. Because it loads each {@link
     * ColumnEntity} as a managed entity before removing it, it goes through Hibernate's normal
     * versioned-delete check and DOES honor {@code @Version} — unlike the sibling {@link
     * TaskService#deleteAllByColumn} bulk-JPQL task/subtask delete, which bypasses {@code @Version}
     * entirely (see that method's Javadoc for the accepted tradeoff). This means a column
     * concurrently modified between this method's {@code findAllByBoardId} fetch and the derived
     * delete's internal per-entity removal can surface {@code OptimisticLockingFailureException}
     * mid-batch, whereas the equivalent race on the task-delete path above it silently proceeds.
     * The two delete paths are intentionally inconsistent with each other; this is documented here
     * rather than reconciled, per the accepted tradeoff carried from research.
     */
    @Transactional
    public void deleteAllByBoardId(String userId, String boardId) {
        var pair = ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId);

        for (var column : columnRepository.findAllByBoardId(pair.getSecond().getId())) {
            taskService.deleteAllByColumn(column);
        }

        columnRepository.deleteAllByBoardId(pair.getSecond().getId());
    }

    /**
     * {@code @Transactional} here (rather than relying on the caller, {@link
     * BoardService#addColumnByBoardId}, already being {@code @Transactional}) makes the
     * after-commit {@code ColumnCreatedEvent} publish guarantee self-contained — see {@link
     * TaskService#save(com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO,
     * com.vrudenko.kanban_board.entity.ColumnEntity)}'s Javadoc for the full reasoning.
     */
    @Transactional
    public ColumnResponseDTO save(SaveColumnRequestDTO columnDTO, BoardEntity board) {
        var column = columnMapper.fromSaveColumnRequestDTO(columnDTO);
        column.setBoard(board);

        // Supersedes ColumnEntity.position's `= 0` field initialiser, exactly like
        // TaskService#save does for tasks — see that method's comment for the full reasoning.
        column.setPosition(Ints.checkedCast(columnRepository.countByBoardId(board.getId())));

        columnRepository.save(column);

        eventPublisher.publishEvent(
                new ColumnCreatedEvent(
                        eventIdGenerator.generate(),
                        board.getUser().getId(),
                        board.getId(),
                        column.getId(),
                        Instant.now()));

        return columnMapper.toColumnResponseDTO(column);
    }

    @Transactional
    public TaskResponseDTO addTaskByColumnId(
            String userId, String columnId, SaveTaskRequestDTO taskDTO) {
        var column = findById(userId, columnId);

        return taskService.save(taskDTO, column);
    }

    @Transactional
    public ColumnEntity findById(String userId, String columnId) {
        var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);

        return pair.getSecond();
    }

    /**
     * Explicit version check is required in addition to {@code @Version}: this load-then-save flow
     * runs entirely within one transaction, so Hibernate's own dirty-checking optimistic lock
     * (which fires on the UPDATE statement) does not by itself model the "client read at version N,
     * another client already wrote version N+1, this client's write should be rejected" scenario
     * across separate HTTP requests. Comparing the caller-supplied {@code dto.getVersion()} against
     * the just-loaded managed entity's version, before any field is mutated, is what actually
     * catches a stale read-then-write race and turns it into a rejected request instead of a silent
     * overwrite.
     */
    @Transactional
    public ColumnResponseDTO updateById(
            String userId, String columnId, UpdateColumnRequestDTO dto) {
        var column = findById(userId, columnId);

        // dto.getVersion() is read ONLY here, for this stale-write precondition check — it is
        // never assigned onto `column`. The version value that actually gets persisted is
        // generated entirely by Hibernate's own @Version increment mechanism when the UPDATE
        // statement runs (forced below via entityManager.flush()), independent of whatever value
        // the client sent.
        if (!column.getVersion().equals(dto.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Column was modified by another request, please refetch.");
        }

        column.setName(dto.getName());

        columnRepository.save(column);

        // Hibernate only bumps the in-memory @Version field once the UPDATE statement actually
        // runs, which normally happens at transaction commit, not at save(). Flushing here forces
        // that UPDATE (and the version increment) to happen before the response DTO is built, so
        // the caller sees the new version instead of the stale pre-update one (D-01).
        entityManager.flush();

        return columnMapper.toColumnResponseDTO(column);
    }

    /**
     * Structured on {@link #updateById}'s version-guarded mutation shape (load through the
     * ownership-verified loader, compare version before any write, flush so the response DTO
     * carries the post-update version) — see that method's Javadoc for why the explicit check is
     * required in addition to {@code @Version}.
     *
     * <p><b>Renumbering contract (GAP-03):</b> {@code dto.getTargetPosition()} is mandatory (unlike
     * {@link com.vrudenko.kanban_board.service.TaskService#moveToColumn}'s nullable equivalent — a
     * reorder request with no target position asks for nothing at all). The version compare runs
     * BEFORE any renumbering statement, so a rejected reorder leaves the board's column sequence
     * completely untouched. The bulk shift below is a single signed range over the positions
     * strictly between the column's current and target position — see {@link
     * com.vrudenko.kanban_board.repository.TaskRepository#shiftPositions}'s Javadoc for why this
     * composes correctly instead of double-shifting, and {@link
     * com.vrudenko.kanban_board.service.TaskService#moveToColumn} for the identical single-scope
     * derivation of this recipe.
     */
    @Transactional
    public ColumnResponseDTO reorder(String userId, String columnId, ReorderColumnRequestDTO dto) {
        var column = findById(userId, columnId);

        if (!column.getVersion().equals(dto.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Column was modified by another request, please refetch.");
        }

        var boardId = column.getBoard().getId();
        var oldPosition = column.getPosition();

        // The board's column count already includes this column, so the highest valid index is
        // count - 1 (reordering doesn't change how many columns the board has).
        var siblingCount = Ints.checkedCast(columnRepository.countByBoardId(boardId));
        var maxValidPosition = siblingCount - 1;
        var effectivePosition = Math.min(dto.getTargetPosition(), maxValidPosition);

        if (effectivePosition < oldPosition) {
            columnRepository.shiftPositions(boardId, 1, effectivePosition, oldPosition - 1);
        } else if (effectivePosition > oldPosition) {
            columnRepository.shiftPositions(boardId, -1, oldPosition + 1, effectivePosition);
        }

        column.setPosition(effectivePosition);
        columnRepository.save(column);

        // Same reason as updateById: force the UPDATE (and version increment) to happen now, so
        // the response DTO carries the new version instead of the stale pre-reorder one.
        entityManager.flush();

        return columnMapper.toColumnResponseDTO(column);
    }

    @VisibleForTesting
    public int getColumnCountByBoardId(String boardId) {
        return Ints.checkedCast(columnRepository.countByBoardId(boardId));
    }

    @Transactional
    public List<ColumnResponseDTO> findAllByBoardId(String userId, String boardId) {
        var pair = ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId);

        return columnMapper.toColumnResponseDTOList(
                columnRepository.findAllByBoardId(pair.getSecond().getId()));
    }

    /**
     * Deletes one column and cascades to its tasks/subtasks via the existing batched {@link
     * TaskService#deleteAllByColumn} — mirroring the per-column loop in {@link
     * #deleteAllByBoardId}, of which this is the single-column case. Deliberately carries no
     * non-empty-column guard: once ownership passes, the delete always cascades unconditionally,
     * matching {@link com.vrudenko.kanban_board.service.BoardService#deleteById}'s existing
     * behaviour (D-07) — this is a deliberate choice, not an oversight to "fix" by adding a
     * task-count check.
     *
     * <p>The ids below are captured into locals BEFORE the deletes run, on purpose — same reason as
     * {@link TaskService#deleteById}'s Javadoc: once {@code columnRepository.deleteById(...)}
     * executes there is nothing left to derive {@code boardId} from for the {@code
     * ColumnDeletedEvent}, and the Kafka consumer runs with no security context and cannot look it
     * up. {@code deletedPosition} is captured for the same reason, and is used afterward (GAP-03)
     * to close the gap the deleted column leaves in its board's sequence — without this, deleting a
     * middle column would leave a permanent hole instead of a contiguous-from-zero sequence.
     */
    @Transactional
    public void deleteById(String userId, String columnId) {
        var column = findById(userId, columnId);

        var deletedColumnId = column.getId();
        var deletedBoardId = column.getBoard().getId();
        var deletedPosition = column.getPosition();

        taskService.deleteAllByColumn(column);

        columnRepository.deleteById(deletedColumnId);

        columnRepository.shiftPositions(deletedBoardId, -1, deletedPosition + 1, Integer.MAX_VALUE);

        eventPublisher.publishEvent(
                new ColumnDeletedEvent(
                        eventIdGenerator.generate(),
                        userId,
                        deletedBoardId,
                        deletedColumnId,
                        Instant.now()));
    }
}
