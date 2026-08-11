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
     * {@code @Transactional} here (rather than relying on the caller, {@link
     * ColumnService#addTaskByColumnId}, already being {@code @Transactional}) makes the
     * after-commit {@code TaskCreatedEvent} publish guarantee self-contained:
     * {@code @TransactionalEventListener} silently skips delivery when no transaction is active, so
     * a future direct call to this method with no {@code @Transactional} caller would otherwise
     * drop the event with no error and no log line. {@code REQUIRED} propagation is a no-op inside
     * an existing transaction, so current callers see no behaviour change.
     */
    @Transactional
    public TaskResponseDTO save(SaveTaskRequestDTO dto, ColumnEntity column) {
        var task = taskMapper.fromSaveTaskRequestDTO(dto);
        task.setColumn(column);

        // Supersedes TaskEntity.position's `= 0` field initialiser, which exists only so plan 01
        // could ship a NOT NULL column safely. The real next-slot position is the current sibling
        // count in this column — positions are kept contiguous from zero by every mutation in this
        // class, so "current count" and "next append-at-end index" are the same number.
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

    // TODO: make a service interface
    public TaskEntity findById(String userId, String taskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, taskId);

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
    public TaskResponseDTO updateById(String userId, String taskId, UpdateTaskRequestDTO dto) {
        var task = findById(userId, taskId);

        // dto.getVersion() is read ONLY here, for this stale-write precondition check — it is
        // never assigned onto `task`. The version value that actually gets persisted is generated
        // entirely by Hibernate's own @Version increment mechanism when the UPDATE statement runs
        // (forced below via entityManager.flush()), independent of whatever value the client sent.
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

        // Hibernate only bumps the in-memory @Version field once the UPDATE statement actually
        // runs, which normally happens at transaction commit, not at save(). Flushing here forces
        // that UPDATE (and the version increment) to happen before the response DTO is built, so
        // the caller sees the new version instead of the stale pre-update one (D-01).
        entityManager.flush();

        return taskMapper.toTaskResponseDTO(task);
    }

    /**
     * Reuses the exact explicit version-check-before-mutate pattern from {@link #updateById} (see
     * its Javadoc for why the explicit check is required in addition to {@code @Version}). Also
     * verifies ownership of the TARGET column (not just the task) via {@link
     * OwnershipVerifierService#verifyOwnershipOfColumn}, and rejects a move across board boundaries
     * (MOVE-03) before the version check — a wrong-board target is a request-shape problem
     * independent of concurrency, so 400 is the more specific signal to return first.
     *
     * <p><b>Renumbering contract (GAP-03/D-04):</b> {@code dto.getTargetPosition()} is nullable —
     * {@code null} means "append at the end of the target column," preserving the pre-D-04 move
     * behaviour for clients that never send it. Positions are kept contiguous from zero within
     * their column at all times; a request for a position beyond the destination's sibling count is
     * clamped to the end rather than rejected, so the natural drag-to-end gesture always succeeds.
     * The bulk shifts below run as plain JPQL, which bypasses the persistence context — they
     * deliberately never touch the moved task's own pre-shift position, so the still-managed {@code
     * task} entity in this method never goes stale, and shifted siblings do NOT have their
     * {@code @Version} bumped (bulk JPQL never loads them as managed entities): a client editing a
     * sibling task should not be 409'd just because someone else reordered a different task in the
     * same column.
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
            // Same-column reorder: steps 2 and 3 of the plan's cross-column recipe compose into
            // one signed shift over the range strictly between the old and new position, excluding
            // the moved task's own oldPosition on both ends so its still-managed row is never
            // touched by the bulk statement.
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
     * The ids below are captured into locals BEFORE the deletes run, on purpose: once {@code
     * taskRepository.deleteById(...)} executes there is nothing left to derive {@code boardId} from
     * for the {@code TaskDeletedEvent}, and Phase 3's consumer runs with no {@code SecurityContext}
     * and cannot look it up itself.
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

    @Transactional
    public void deleteAllByColumnId(String userId, String columnId) {
        var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);

        deleteAllByColumn(pair.getSecond());
    }

    /**
     * For callers (e.g. {@link ColumnService#deleteAllByBoardId}) that already verified ownership
     * of {@code column} — skips re-verifying it and batches the subtask/task deletes instead of
     * looping one delete per task, so the query count doesn't scale with the number of tasks.
     *
     * <p>The batch deletes below are bulk JPQL statements, which bypass the persistence context —
     * Hibernate doesn't know the deleted rows are gone, so anything still tracked in this session
     * (relevant when a caller loops this over many columns/boards in one transaction, e.g. account
     * deletion) can go stale. Flushing and clearing afterward keeps the session consistent with the
     * DB for whatever runs next in the same transaction.
     *
     * <p><b>{@code @Version} bypass, by design:</b> {@code taskRepository.deleteAllByIdInBatch} and
     * {@code SubtaskRepository.deleteAllByTaskIdIn} (invoked via {@link
     * SubtaskService#deleteAllByTaskIds}) both issue a raw bulk JPQL/SQL {@code DELETE ... WHERE id
     * IN (...)} statement. Bulk statements never load the target rows as managed entities, so there
     * is nothing for Hibernate to dirty-check {@code @Version} against — these deletes proceed
     * unconditionally even if the row's version was concurrently bumped by another transaction a
     * moment earlier. This is an <b>accepted, delete-wins tradeoff</b>, not an oversight: a delete
     * racing a version-mismatched update simply discards the update's effect on a row that is being
     * removed anyway, which is the correct outcome for a delete (there is no "stale delete" to
     * detect — the row either exists to be deleted or it doesn't). Retrofitting per-row {@code AND
     * version = ?} clauses onto a multi-row bulk statement doesn't fit its semantics (each row
     * could have a different expected version) and would reintroduce the per-entity-load N+1 cost
     * this batch delete exists to avoid — so it is intentionally not done here. Contrast with
     * {@link ColumnService#deleteAllByBoardId}, whose column-delete step is a <i>derived</i>
     * (fetch-then- remove-per-entity) delete and therefore DOES honor {@code @Version} — the two
     * sibling delete paths are deliberately asymmetric.
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
