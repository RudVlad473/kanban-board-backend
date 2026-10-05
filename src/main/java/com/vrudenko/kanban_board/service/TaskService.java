package com.vrudenko.kanban_board.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.vrudenko.kanban_board.config.EventIdGenerator;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.event.TaskCreatedEvent;
import com.vrudenko.kanban_board.event.TaskDeletedEvent;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.event.TaskUpdatedEvent;
import com.vrudenko.kanban_board.mapper.TaskMapper;
import com.vrudenko.kanban_board.repository.TaskRepository;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
    @Autowired private TaskRepository taskRepository;

    @Autowired private TaskMapper taskMapper;

    @Autowired private OwnershipVerifierService ownershipVerifierService;

    @Autowired private SubtaskService subtaskService;

    @Autowired private EntityManager entityManager;

    @Autowired private ApplicationEventPublisher eventPublisher;

    @Autowired private EventIdGenerator eventIdGenerator;

    /**
     * Create the task at the end of its column and publish {@code TaskCreatedEvent} after commit.
     *
     * <p>{@code @Transactional} is declared here, not left to {@link
     * ColumnService#addTaskByColumnId}, so the after-commit publish does not depend on the caller:
     * {@code @TransactionalEventListener} silently skips delivery with no active transaction, which
     * would drop the event with no error and no log line. {@code REQUIRED} propagation is a no-op
     * inside an existing transaction.
     */
    @Transactional
    public TaskResponseDTO save(SaveTaskRequestDTO dto, ColumnEntity column) {
        var task = taskMapper.fromSaveTaskRequestDTO(dto);
        task.setColumn(column);

        // Positions stay contiguous from zero in every mutation here, so the sibling count is the
        // next append-at-end index (TaskEntity.position's `= 0` initialiser is a NOT NULL
        // placeholder).
        task.setPosition(Ints.checkedCast(taskRepository.countByColumnId(column.getId())));

        taskRepository.save(task);

        eventPublisher.publishEvent(
                new TaskCreatedEvent(
                        eventIdGenerator.generate(),
                        column.getBoard().getUser().getId(),
                        column.getBoard().getId(),
                        column.getId(),
                        task.getId(),
                        Instant.now()));

        return taskMapper.toTaskResponseDTO(task);
    }

    @Transactional
    public List<TaskResponseDTO> findAllByColumnId(String userId, String columnId) {
        var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);

        return taskMapper.toTaskResponseDTOList(
                taskRepository.findAllByColumnId(pair.getSecond().getId()));
    }

    @Transactional
    public int getTaskCountByColumnId(String userId, String columnId) {
        var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);

        return Ints.checkedCast(taskRepository.countByColumnId(pair.getSecond().getId()));
    }

    public TaskEntity findById(String userId, String taskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, taskId);

        return pair.getSecond();
    }

    /**
     * Update the task if the caller's version matches, rejecting a stale write.
     *
     * <p>The explicit version check is required in addition to {@code @Version}: this
     * load-then-save flow runs inside one transaction, so Hibernate's dirty-check lock (which fires
     * on the UPDATE) cannot model "client read version N, another client wrote N+1, reject this
     * write" across separate HTTP requests. Comparing {@code dto.getVersion()} to the just-loaded
     * entity's version before any mutation turns that race into a rejected request instead of a
     * silent overwrite.
     */
    @Transactional
    public TaskResponseDTO updateById(String userId, String taskId, UpdateTaskRequestDTO dto) {
        var task = findById(userId, taskId);

        // dto.getVersion() is read only for this precondition and never assigned onto `task`: the
        // persisted version comes from Hibernate's own @Version increment.
        if (!task.getVersion().equals(dto.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Task was modified by another request, please refetch.");
        }

        if (Optional.ofNullable(dto.getTitle()).isPresent()) {
            task.setTitle(dto.getTitle());
        }
        if (Optional.ofNullable(dto.getDescription()).isPresent()) {
            task.setDescription(dto.getDescription());
        }

        taskRepository.save(task);

        // Hibernate bumps the in-memory @Version only when the UPDATE runs, normally at commit.
        // Flush so the response DTO carries the new version, not the stale pre-update one.
        entityManager.flush();

        // Published only after the version guard has passed, so a rejected update publishes
        // nothing. columnId comes from the verified task, never a path variable
        // (docs/CODE_STYLE.md rule 2).
        eventPublisher.publishEvent(
                new TaskUpdatedEvent(
                        eventIdGenerator.generate(),
                        task.getColumn().getBoard().getUser().getId(),
                        task.getColumn().getBoard().getId(),
                        task.getColumn().getId(),
                        task.getId(),
                        Instant.now()));

        return taskMapper.toTaskResponseDTO(task);
    }

    /**
     * Move the task to a target column and position, applying {@link #updateById}'s explicit
     * version check before mutating.
     *
     * <p>Ownership of the TARGET column is verified too, and a cross-board move is rejected before
     * the version check: a wrong-board target is a request-shape problem independent of
     * concurrency, so 400 is the more specific signal.
     *
     * <p>Decisions:
     *
     * <p><b>Renumbering contract:</b> {@code dto.getTargetPosition()} is nullable; {@code null}
     * means "append at the end of the target column", preserving the behaviour for clients that
     * never send it. Positions stay contiguous from zero within a column; a position beyond the
     * destination's sibling count is clamped to the end rather than rejected, so the natural
     * drag-to-end gesture always succeeds. The bulk shifts run as plain JPQL, which bypasses the
     * persistence context: they never touch the moved task's own pre-shift position, so the
     * still-managed {@code task} never goes stale, and shifted siblings do NOT have their
     * {@code @Version} bumped (bulk JPQL never loads them as managed entities). A client editing a
     * sibling task is not 409'd just because someone else reordered a different task in the same
     * column.
     */
    @Transactional
    public TaskResponseDTO moveToColumn(String userId, String taskId, MoveTaskRequestDTO dto) {
        var task = findById(userId, taskId);
        var sourceColumnId = task.getColumn().getId();
        var sourceBoardId = task.getColumn().getBoard().getId();

        var targetColumnPair =
                ownershipVerifierService.verifyOwnershipOfColumn(userId, dto.getTargetColumnId());
        var targetColumn = targetColumnPair.getSecond();

        if (!targetColumn.getBoard().getId().equals(sourceBoardId)) {
            throw new IllegalArgumentException(
                    "Cannot move a task to a column on a different board.");
        }

        if (!task.getVersion().equals(dto.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Task was modified by another request, please refetch.");
        }

        var oldPosition = task.getPosition();
        var targetColumnId = targetColumn.getId();
        var sameColumn = sourceColumnId.equals(targetColumnId);

        // The destination's current sibling count already includes the moving task when the move
        // is within the same column (it hasn't left yet), so the highest valid target index is
        // count - 1 there, versus count (a genuine new slot) when moving across columns.
        var destinationSiblingCount =
                Ints.checkedCast(taskRepository.countByColumnId(targetColumnId));
        var maxValidPosition = sameColumn ? destinationSiblingCount - 1 : destinationSiblingCount;
        var requestedPosition = dto.getTargetPosition();
        var effectivePosition =
                requestedPosition == null
                        ? maxValidPosition
                        : Math.min(requestedPosition, maxValidPosition);

        if (sameColumn) {
            // A same-column reorder is one signed shift over the positions strictly between
            // the old and new position.
            //
            // It composes the cross-column close-gap and open-slot steps and excludes the moved
            // task's own oldPosition on both ends, so its managed row is never touched.
            if (effectivePosition < oldPosition) {
                taskRepository.shiftPositions(
                        targetColumnId, 1, effectivePosition, oldPosition - 1);
            } else if (effectivePosition > oldPosition) {
                taskRepository.shiftPositions(
                        targetColumnId, -1, oldPosition + 1, effectivePosition);
            }
        } else {
            // Close the gap left in the source column.
            taskRepository.shiftPositions(sourceColumnId, -1, oldPosition + 1, Integer.MAX_VALUE);
            // Open the slot in the destination column.
            taskRepository.shiftPositions(targetColumnId, 1, effectivePosition, Integer.MAX_VALUE);
        }

        task.setColumn(targetColumn);
        task.setPosition(effectivePosition);
        taskRepository.save(task);

        // Same reason as updateById: force the UPDATE (and version increment) to happen now, so
        // the response DTO carries the new version instead of the stale pre-move one.
        entityManager.flush();

        eventPublisher.publishEvent(
                new TaskMovedEvent(
                        eventIdGenerator.generate(),
                        userId,
                        sourceBoardId,
                        task.getId(),
                        sourceColumnId,
                        targetColumn.getId(),
                        Instant.now()));

        return taskMapper.toTaskResponseDTO(task);
    }

    /**
     * Delete the task and publish {@code TaskDeletedEvent}.
     *
     * <p>The ids are captured into locals BEFORE the deletes run: afterwards nothing is left to
     * derive {@code boardId} from, and the event's consumer runs with no {@code SecurityContext}
     * and cannot look it up.
     */
    @Transactional
    public void deleteById(String userId, String taskId) {
        var task = findById(userId, taskId);

        var deletedTaskId = task.getId();
        var deletedColumnId = task.getColumn().getId();
        var deletedBoardId = task.getColumn().getBoard().getId();

        subtaskService.deleteAllByTaskId(userId, taskId);

        taskRepository.deleteById(task.getId());

        eventPublisher.publishEvent(
                new TaskDeletedEvent(
                        eventIdGenerator.generate(),
                        userId,
                        deletedBoardId,
                        deletedColumnId,
                        deletedTaskId,
                        Instant.now()));
    }

    /**
     * Delete a column's tasks and subtasks in batches, for callers that already verified ownership
     * of {@code column}, so the query count does not scale with the number of tasks.
     *
     * <p>The batch deletes are bulk JPQL, which bypasses the persistence context: anything still
     * tracked in this session (a caller looping over many columns or boards in one transaction,
     * e.g. account deletion) can go stale, so flushing and clearing afterward keeps it consistent
     * with the DB.
     *
     * <p>Decisions:
     *
     * <p><b>{@code @Version} bypass, by design:</b> {@code taskRepository.deleteAllByIdInBatch} and
     * {@code SubtaskRepository.deleteAllByTaskIdIn} (via {@link SubtaskService#deleteAllByTaskIds})
     * issue a raw bulk {@code DELETE ... WHERE id IN (...)}, which never loads the rows as managed
     * entities, so there is nothing to dirty-check {@code @Version} against. The deletes proceed
     * even if another transaction just bumped a row's version. This is an accepted, delete-wins
     * tradeoff: a delete racing a version-mismatched update discards the update's effect on a row
     * being removed anyway, and there is no "stale delete" to detect. Per-row {@code AND version =
     * ?} clauses do not fit a multi-row bulk statement (each row could expect a different version)
     * and would reintroduce the per-entity-load N+1 this batch delete avoids. Contrast {@link
     * ColumnService#deleteAllByBoardId}, whose column-delete step is a <i>derived</i>
     * (fetch-then-remove) delete and DOES honor {@code @Version}: the two paths are deliberately
     * asymmetric.
     *
     * <p><b>No per-task or per-subtask event is published:</b> this cascade fires from {@link
     * ColumnService#deleteById} or {@link ColumnService#deleteAllByBoardId}, whose own {@code
     * ColumnDeletedEvent}/{@code BoardDeletedEvent} is the event a caller sees. Fanning out
     * per-child events would reintroduce the N+1 (see {@link ColumnService#deleteAllByBoardId}).
     */
    @Transactional
    void deleteAllByColumn(ColumnEntity column) {
        var taskIds =
                taskRepository.findAllByColumnId(column.getId()).stream()
                        .map(TaskEntity::getId)
                        .toList();

        subtaskService.deleteAllByTaskIds(taskIds);
        taskRepository.deleteAllByIdInBatch(taskIds);

        entityManager.flush();
        entityManager.clear();
    }

    @Transactional
    public SubtaskResponseDTO addSubtaskByTaskId(
            String userId, String taskId, SaveSubtaskRequestDTO dto) {
        var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, taskId);

        var task = pair.getSecond();

        return subtaskService.save(task, dto);
    }

    @VisibleForTesting
    void deleteAll() {
        taskRepository.deleteAll();
    }
}
