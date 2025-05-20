package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.mapper.TaskMapper;
import com.vrudenko.kanban_board.repository.TaskRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
  @Autowired private TaskRepository taskRepository;
  @Autowired private TaskMapper taskMapper;
  @Autowired private OwnershipVerifierService ownershipVerifierService;
  @Autowired private SubtaskService subtaskService;

  TaskResponseDTO save(SaveTaskRequestDTO dto, ColumnEntity column) {
    var task = taskMapper.fromSaveTaskRequestDTO(dto);
    task.setColumn(column);

    taskRepository.save(task);

    return taskMapper.toTaskResponseDTO(task);
  }

  List<TaskResponseDTO> findAllByColumnId(String userId, String columnId) {
    var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);

    return taskMapper.toTaskResponseDTOList(
        taskRepository.findAllByColumnId(pair.getSecond().getId()));
  }

  int getTaskCountByColumnId(String userId, String columnId) {
    var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);

    return Ints.checkedCast(taskRepository.countByColumnId(pair.getSecond().getId()));
  }

  // TODO: make a service interface
  TaskEntity findById(String userId, String taskId) {
    var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, taskId);

    return pair.getSecond();
  }

  TaskResponseDTO updateById(String userId, String taskId, SaveTaskRequestDTO dto) {
    var task = findById(userId, taskId);
    task.setTitle(dto.getTitle());
    task.setDescription(dto.getDescription());

    taskRepository.save(task);

    return taskMapper.toTaskResponseDTO(task);
  }

  void deleteById(String userId, String taskId) {
    var task = findById(userId, taskId);

    // TODO: delete all subtasks

    taskRepository.deleteById(task.getId());
  }

  void deleteAllByColumnId(String userId, String columnId) {
    var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);

    // TODO: delete all subtasks in batch using list of task ids
    for (var task : findAllByColumnId(userId, pair.getSecond().getId())) {
      subtaskService.deleteAllByTaskId(userId, task.getId());

      deleteById(userId, task.getId());
    }
  }

  public SubtaskResponseDTO addSubtaskByTaskId(String userId, String taskId, SaveSubtaskRequestDTO dto) {
    var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, taskId);

    var task = pair.getSecond();

    return subtaskService.save(task, dto);
  }

  @VisibleForTesting
  void deleteAll() {
    taskRepository.deleteAll();
  }
}
