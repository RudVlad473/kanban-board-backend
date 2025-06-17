package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.UpdateSubtaskRequestDTO;
import com.vrudenko.kanban_board.entity.SubtaskEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.mapper.SubtaskMapper;
import com.vrudenko.kanban_board.repository.SubtaskRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

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

        return subtaskMapper.toSubtaskResponseDTO(subtaskRepository.save(subtask));
    }

    @Transactional
    public List<SubtaskResponseDTO> findAllByTaskId(String userId, String taskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, taskId);

        return subtaskMapper.toSubtaskResponseDTOList(
                subtaskRepository.findAllByTaskId(pair.getSecond().getId()));
    }

    @Transactional
    SubtaskEntity findById(String userId, String taskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfSubtask(userId, taskId);

        return pair.getSecond();
    }

    @Transactional
    public SubtaskResponseDTO updateById(
            String userId, String taskId, UpdateSubtaskRequestDTO dto) {
        var subtask = findById(userId, taskId);

        if (Optional.ofNullable(dto.getTitle()).isPresent()) {
            subtask.setTitle(dto.getTitle());
        }

        if (Optional.ofNullable(dto.getIsCompleted()).isPresent()) {
            subtask.setIsCompleted(dto.getIsCompleted());
        }

        subtaskRepository.save(subtask);

        return subtaskMapper.toSubtaskResponseDTO(subtask);
    }

    @Transactional
    public void deleteById(String userId, String subtaskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfSubtask(userId, subtaskId);

        subtaskRepository.deleteById(pair.getSecond().getId());
    }

    SubtaskEntity findById(String id) {
        var subtask = subtaskRepository.findById(id);

        if (subtask.isEmpty()) {
            throw new AppEntityNotFoundException("Subtask");
        }

        return subtask.get();
    }

    void deleteAllByTaskId(String userId, String subtaskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, subtaskId);

        subtaskRepository.deleteAllByTaskId(pair.getSecond().getId());
    }
}
