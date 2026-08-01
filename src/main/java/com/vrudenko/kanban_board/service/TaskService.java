package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.mapper.TaskMapper;
import com.vrudenko.kanban_board.repository.TaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    public TaskResponseDTO save(SaveTaskRequestDTO dto, ColumnEntity column) {
        var task = taskMapper.fromSaveTaskRequestDTO(dto);
        task.setColumn(column);

        taskRepository.save(task);

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

        task.setColumn(targetColumn);
        taskRepository.save(task);

        // Same reason as updateById: force the UPDATE (and version increment) to happen now, so
        // the response DTO carries the new version instead of the stale pre-move one.
        entityManager.flush();

        eventPublisher.publishEvent(
                new TaskMovedEvent(
                        UUID.randomUUID(),
                        userId,
                        sourceBoardId,
                        task.getId(),
                        sourceColumnId,
                        targetColumn.getId(),
                        Instant.now()));

        return taskMapper.toTaskResponseDTO(task);
    }

    @Transactional
    public void deleteById(String userId, String taskId) {
        var task = findById(userId, taskId);

        subtaskService.deleteAllByTaskId(userId, taskId);

        taskRepository.deleteById(task.getId());
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
