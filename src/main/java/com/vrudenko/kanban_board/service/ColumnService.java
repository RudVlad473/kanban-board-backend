package com.vrudenko.kanban_board.service;

import java.time.Instant;
import java.util.List;

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
import com.vrudenko.kanban_board.event.ColumnReorderedEvent;
import com.vrudenko.kanban_board.event.ColumnUpdatedEvent;
import com.vrudenko.kanban_board.mapper.ColumnMapper;
import com.vrudenko.kanban_board.repository.ColumnRepository;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
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
     * Delete every column of {@code boardId} and, per column, all of its tasks and subtasks via
     * {@link TaskService#deleteAllByColumn}.
     *
     * <p>Decisions:
     *
     * <p><b>Derived-delete vs. bulk-delete asymmetry:</b> {@code
     * columnRepository.deleteAllByBoardId} is a Spring Data <i>derived</i> delete (not an explicit
     * {@code @Modifying @Query}), implemented as fetch-then-{@code remove()} per entity. Loading
     * each {@link ColumnEntity} as a managed entity sends it through Hibernate's versioned-delete
     * check, so it DOES honor {@code @Version}, unlike the bulk-JPQL {@link
     * TaskService#deleteAllByColumn} (see its Javadoc). A column modified between this method's
     * {@code findAllByBoardId} fetch and the per-entity removal can therefore surface {@code
     * OptimisticLockingFailureException} mid-batch, whereas the equivalent race on the task-delete
     * path silently proceeds. Intentionally inconsistent: documented, not reconciled.
     *
     * <p><b>No per-column {@code ColumnDeletedEvent} is published:</b> this cascade fires from
     * {@link BoardService#deleteById}, whose own {@code BoardDeletedEvent} is the single event a
     * board delete emits. Deliberate: one event per cascaded column (and transitively per task and
     * subtask) would mean loading every child purely to publish, reintroducing the N+1 the batch
     * delete avoids, and could emit hundreds of events from one request into a bounded publish
     * queue.
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
     * Create the column at the end of its board and publish {@code ColumnCreatedEvent} after
     * commit.
     *
     * <p>{@code @Transactional} is declared here so the after-commit publish does not depend on the
     * caller; see {@link TaskService#save}.
     */
    @Transactional
    public ColumnResponseDTO save(SaveColumnRequestDTO columnDTO, BoardEntity board) {
        var column = columnMapper.fromSaveColumnRequestDTO(columnDTO);
        column.setBoard(board);

        // The sibling count is the next append-at-end position (the entity's `= 0` initialiser is
        // a NOT NULL placeholder).
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
     * Update the column if the caller's version matches, rejecting a stale write.
     *
     * <p>The explicit version check is required in addition to {@code @Version}: this
     * load-then-save flow runs inside one transaction, so Hibernate's dirty-check lock (which fires
     * on the UPDATE) cannot model "client read version N, another client wrote N+1, reject this
     * write" across separate HTTP requests. Comparing {@code dto.getVersion()} to the just-loaded
     * entity's version before any mutation turns that race into a rejected request instead of a
     * silent overwrite.
     */
    @Transactional
    public ColumnResponseDTO updateById(
            String userId, String columnId, UpdateColumnRequestDTO dto) {
        var column = findById(userId, columnId);

        // dto.getVersion() is read only for this precondition and never assigned onto `column`: the
        // persisted version comes from Hibernate's own @Version increment.
        if (!column.getVersion().equals(dto.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Column was modified by another request, please refetch.");
        }

        column.setName(dto.getName());

        columnRepository.save(column);

        // Hibernate bumps the in-memory @Version only when the UPDATE runs, normally at commit.
        // Flush so the response DTO carries the new version, not the stale pre-update one.
        entityManager.flush();

        // Published only after the version guard above has passed, so a rejected update publishes
        // nothing. Ids derived from the verified entity, never a raw path variable
        // (docs/CODE_STYLE.md rule 2).
        eventPublisher.publishEvent(
                new ColumnUpdatedEvent(
                        eventIdGenerator.generate(),
                        column.getBoard().getUser().getId(),
                        column.getBoard().getId(),
                        column.getId(),
                        Instant.now()));

        return columnMapper.toColumnResponseDTO(column);
    }

    /**
     * Move the column to {@code dto.getTargetPosition()} under {@link #updateById}'s version guard.
     *
     * <p><b>Renumbering contract:</b> {@code dto.getTargetPosition()} is mandatory (unlike {@link
     * TaskService#moveToColumn}'s nullable one: a reorder with no target asks for nothing). The
     * version compare runs BEFORE any renumbering statement, so a rejected reorder leaves the
     * board's column sequence untouched. The bulk shift is a single signed range over the positions
     * strictly between the current and target position; see {@link
     * com.vrudenko.kanban_board.repository.TaskRepository#shiftPositions} for why this composes
     * instead of double-shifting.
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

        // targetPosition is the clamped effectivePosition, never the raw requested value.
        eventPublisher.publishEvent(
                new ColumnReorderedEvent(
                        eventIdGenerator.generate(),
                        column.getBoard().getUser().getId(),
                        boardId,
                        column.getId(),
                        oldPosition,
                        effectivePosition,
                        Instant.now()));

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
     * Delete one column and cascade to its tasks and subtasks via {@link
     * TaskService#deleteAllByColumn}, the single-column case of {@link #deleteAllByBoardId}'s loop.
     *
     * <p>Decisions:
     *
     * <p>There is no non-empty-column guard: once ownership passes, the delete always cascades,
     * matching {@link BoardService#deleteById}. A deliberate choice, not an oversight to "fix" with
     * a task-count check.
     *
     * <p>The ids are captured into locals BEFORE the deletes run (see {@link
     * TaskService#deleteById}): afterwards nothing is left to derive {@code boardId} from, and the
     * Kafka consumer has no security context to look it up. {@code deletedPosition} is captured too
     * and closes the gap the deleted column leaves, so deleting a middle column keeps positions
     * contiguous from zero.
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
