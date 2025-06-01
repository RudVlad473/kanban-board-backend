package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.security.CurrentUserId;
import com.vrudenko.kanban_board.service.ColumnService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
}
