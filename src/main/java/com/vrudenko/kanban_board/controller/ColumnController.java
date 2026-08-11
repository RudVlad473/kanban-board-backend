package com.vrudenko.kanban_board.controller;

import java.net.URI;
import java.util.List;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.ReorderColumnRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.UpdateColumnRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.security.CurrentUserId;
import com.vrudenko.kanban_board.service.ColumnService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS)
@PreAuthorize("isAuthenticated()")
public class ColumnController {
    @Autowired private ColumnService columnService;

    @GetMapping
    public ResponseEntity<List<ColumnResponseDTO>> findAllByBoardId(
            @CurrentUserId String userId, @PathVariable @NotBlank String boardId) {
        return ResponseEntity.ok(columnService.findAllByBoardId(userId, boardId));
    }

    @PostMapping(ApiPaths.COLUMN_ID)
    public ResponseEntity<TaskResponseDTO> addTaskByColumnId(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String columnId,
            @Valid @RequestBody SaveTaskRequestDTO dto,
            HttpServletRequest request) {
        return ResponseEntity.created(URI.create(request.getRequestURI()))
                .body(columnService.addTaskByColumnId(userId, columnId, dto));
    }

    @PutMapping(ApiPaths.COLUMN_ID)
    public ResponseEntity<ColumnResponseDTO> updateById(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String columnId,
            @Valid @RequestBody UpdateColumnRequestDTO dto) {
        return ResponseEntity.ok(columnService.updateById(userId, columnId, dto));
    }

    @DeleteMapping(ApiPaths.COLUMN_ID)
    public ResponseEntity<Void> deleteById(
            @PathVariable @NotBlank String columnId, @CurrentUserId String userId) {
        columnService.deleteById(userId, columnId);

        return ResponseEntity.ok().build();
    }

    // This class's mapping is already board-nested, so — unlike TaskMoveController, whose task
    // routes are not board-nested — no separate flat controller is needed for the reorder route.
    @PatchMapping(ApiPaths.COLUMN_ID + ApiPaths.REORDER)
    public ResponseEntity<ColumnResponseDTO> reorder(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String columnId,
            @Valid @RequestBody ReorderColumnRequestDTO dto) {
        return ResponseEntity.ok(columnService.reorder(userId, columnId, dto));
    }
}
