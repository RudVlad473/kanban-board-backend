package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.security.CurrentUserId;
import com.vrudenko.kanban_board.service.TaskService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flat {@code /tasks/{taskId}/move} route. Cannot live on {@link TaskController} — its class-level
 * mapping is board/column-nested, and Spring composes class- and method-level
 * {@code @RequestMapping} paths additively, so a flat route structurally cannot be added there.
 */
@RestController
@RequestMapping(ApiPaths.TASKS)
@Validated
@PreAuthorize("isAuthenticated()")
class TaskMoveController {
    @Autowired TaskService taskService;

    @PatchMapping(ApiPaths.TASK_ID + ApiPaths.MOVE)
    public ResponseEntity<TaskResponseDTO> moveToColumn(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody MoveTaskRequestDTO dto) {
        return ResponseEntity.ok(taskService.moveToColumn(userId, taskId, dto));
    }
}
