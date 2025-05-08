package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.entity.SubtaskEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.mapper.SubtaskMapper;
import com.vrudenko.kanban_board.repository.SubtaskRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SubtaskService {
  @Autowired private SubtaskRepository subtaskRepository;
  @Autowired private SubtaskMapper subtaskMapper;
  @Autowired private OwnershipVerifierService ownershipVerifierService;

  @Transactional
  SubtaskResponseDTO save(TaskEntity task, SaveSubtaskRequestDTO dto) {
    var subtask = subtaskMapper.fromSaveSubtaskRequestDTO(dto);

    subtask.setIsCompleted(false);
    subtask.setTask(task);

    return subtaskMapper.toTaskResponseDTO(subtaskRepository.save(subtask));
  }

  SubtaskEntity findById(String id) {
    var subtask = subtaskRepository.findById(id);

    if (subtask.isEmpty()) {
      throw new AppEntityNotFoundException("Subtask");
    }

    return subtask.get();
  }

  void deleteAllByTaskId(String userId, String taskId) {
    var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, taskId);

    subtaskRepository.deleteAllByTaskId(pair.getSecond().getId());
  }
}
