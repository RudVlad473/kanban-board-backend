package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import com.vrudenko.kanban_board.security.CurrentUserId;
import com.vrudenko.kanban_board.service.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS + ApiPaths.COLUMN_ID + ApiPaths.TASKS)
@PreAuthorize("isAuthenticated()")
class TaskController {
    @Autowired
    TaskService taskService;

    @GetMapping
    public ResponseEntity<List<TaskResponseDTO>> findAllByUserId(
            @CurrentUserId String userId, @PathVariable @NotBlank String columnId) {
        var boards = taskService.findAllByColumnId(userId, columnId);

        return ResponseEntity.ok(boards);
    }

    @DeleteMapping(ApiPaths.TASK_ID)
    public ResponseEntity<Void> deleteById(@PathVariable @NotBlank String taskId, @CurrentUserId String userId) {
        taskService.deleteById(userId, taskId);

        return ResponseEntity.ok().build();
    }

    @PutMapping(ApiPaths.TASK_ID)
    public ResponseEntity<TaskResponseDTO> updateById(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody UpdateTaskRequestDTO dto) {
        return ResponseEntity.ok(taskService.updateById(userId, taskId, dto));
    }

    @PostMapping(ApiPaths.TASK_ID + ApiPaths.SUB_TASKS)
    public ResponseEntity<SubtaskResponseDTO> addSubtaskByTaskId(
            @CurrentUserId String userId, @PathVariable @NotBlank String taskId, @Valid SaveSubtaskRequestDTO dto) {
        return ResponseEntity.ok(taskService.addSubtaskByTaskId(userId, taskId, dto));
    }
}
