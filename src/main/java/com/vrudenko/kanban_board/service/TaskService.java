package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.mapper.TaskMapper;
import com.vrudenko.kanban_board.repository.TaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
    @Autowired private TaskRepository taskRepository;

    @Autowired private TaskMapper taskMapper;

    @Autowired private OwnershipVerifierService ownershipVerifierService;

    @Autowired private SubtaskService subtaskService;

    @Autowired private EntityManager entityManager;

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
