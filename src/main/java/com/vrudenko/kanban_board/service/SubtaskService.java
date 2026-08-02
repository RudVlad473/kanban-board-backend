package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.UpdateSubtaskRequestDTO;
import com.vrudenko.kanban_board.entity.SubtaskEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.mapper.SubtaskMapper;
import com.vrudenko.kanban_board.repository.SubtaskRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
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

    void deleteAllByTaskId(String userId, String subtaskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, subtaskId);

        subtaskRepository.deleteAllByTaskId(pair.getSecond().getId());
    }

    /**
     * Batch variant for callers that already verified ownership of the parent task(s) (e.g. a
     * column-level bulk delete) — avoids re-verifying per task id.
     */
    void deleteAllByTaskIds(List<String> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }

        subtaskRepository.deleteAllByTaskIdIn(taskIds);
    }
}
