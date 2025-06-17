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
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
    @Autowired private TaskRepository taskRepository;

    @Autowired private TaskMapper taskMapper;

    @Autowired private OwnershipVerifierService ownershipVerifierService;

    @Autowired private SubtaskService subtaskService;

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

    @Transactional
    public TaskResponseDTO updateById(String userId, String taskId, UpdateTaskRequestDTO dto) {
        var task = findById(userId, taskId);

        if (Optional.ofNullable(dto.getTitle()).isPresent()) {
            task.setTitle(dto.getTitle());
        }
        if (Optional.ofNullable(dto.getDescription()).isPresent()) {
            task.setDescription(dto.getDescription());
        }

        taskRepository.save(task);

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

        // TODO: delete all subtasks in batch using list of task ids
        for (var task : findAllByColumnId(userId, pair.getSecond().getId())) {
            subtaskService.deleteAllByTaskId(userId, task.getId());

            deleteById(userId, task.getId());
        }
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
