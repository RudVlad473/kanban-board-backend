package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.UpdateSubtaskRequestDTO;
import com.vrudenko.kanban_board.security.CurrentUserId;
import com.vrudenko.kanban_board.service.SubtaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        ApiPaths.BOARDS
                + ApiPaths.BOARD_ID
                + ApiPaths.COLUMNS
                + ApiPaths.COLUMN_ID
                + ApiPaths.TASKS
                + ApiPaths.TASK_ID
                + ApiPaths.SUBTASKS)
@PreAuthorize("isAuthenticated()")
class SubtaskController {
    @Autowired SubtaskService subtaskService;

    @GetMapping
    public ResponseEntity<List<SubtaskResponseDTO>> findAllByTaskId(
            @CurrentUserId String userId, @PathVariable @NotBlank String taskId) {
        return ResponseEntity.ok(subtaskService.findAllByTaskId(userId, taskId));
    }

    @DeleteMapping(ApiPaths.SUBTASK_ID)
    public ResponseEntity<Void> deleteById(
            @CurrentUserId String userId, @PathVariable @NotBlank String subtaskId) {
        subtaskService.deleteById(userId, subtaskId);

        return ResponseEntity.ok().build();
    }

    @PutMapping(ApiPaths.SUBTASK_ID)
    public ResponseEntity<SubtaskResponseDTO> updateById(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String subtaskId,
            @Valid @RequestBody UpdateSubtaskRequestDTO dto) {
        return ResponseEntity.ok(subtaskService.updateById(userId, subtaskId, dto));
    }
}
