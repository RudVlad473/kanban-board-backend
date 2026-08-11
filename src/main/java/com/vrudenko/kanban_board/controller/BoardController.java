package com.vrudenko.kanban_board.controller;

import java.net.URI;
import java.util.List;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.board_dto.BoardFullResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.security.CurrentUserId;
import com.vrudenko.kanban_board.service.BoardService;
import com.vrudenko.kanban_board.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BOARDS)
@Validated
@PreAuthorize("isAuthenticated()")
public class BoardController {
    @Autowired private BoardService boardService;

    @Autowired private UserService userService;

    @GetMapping
    public ResponseEntity<List<BoardResponseDTO>> findAllByUserId(@CurrentUserId String userId) {
        var boards = this.boardService.findAllByUserId(userId);

        return ResponseEntity.ok(boards);
    }

    @PostMapping
    public ResponseEntity<BoardResponseDTO> save(
            @CurrentUserId String userId,
            @Valid @RequestBody SaveBoardRequestDTO dto,
            HttpServletRequest request) {
        return ResponseEntity.created(URI.create(request.getRequestURI()))
                .body(userService.addBoardByUserId(userId, dto));
    }

    @DeleteMapping(ApiPaths.BOARD_ID)
    public ResponseEntity<Void> deleteById(
            @PathVariable @NotBlank String boardId, @CurrentUserId String userId) {
        boardService.deleteById(userId, boardId);

        return ResponseEntity.ok().build();
    }

    @PutMapping(ApiPaths.BOARD_ID)
    public ResponseEntity<BoardResponseDTO> updateById(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String boardId,
            @Valid @RequestBody UpdateBoardRequestDTO dto) {
        return ResponseEntity.ok(boardService.updateById(userId, boardId, dto));
    }

    @PostMapping(ApiPaths.BOARD_ID + ApiPaths.COLUMNS)
    public ResponseEntity<ColumnResponseDTO> addColumnByBoardId(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String boardId,
            @Valid @RequestBody SaveColumnRequestDTO dto,
            HttpServletRequest request) {
        return ResponseEntity.created(URI.create(request.getRequestURI()))
                .body(boardService.addColumnByBoardId(userId, boardId, dto));
    }

    /**
     * GAP-04: one nested read returning the board with its columns, each column with its tasks, and
     * each task with its subtasks, in a single document -- see {@link
     * com.vrudenko.kanban_board.service.BoardService#findFullById} for the ownership/transaction
     * reasoning. Additive: the four existing flat GET endpoints above and on {@code
     * ColumnController}/{@code TaskController}/{@code SubtaskController} are unchanged.
     */
    @GetMapping(ApiPaths.BOARD_ID + ApiPaths.FULL)
    public ResponseEntity<BoardFullResponseDTO> findFullById(
            @CurrentUserId String userId, @PathVariable @NotBlank String boardId) {
        return ResponseEntity.ok(boardService.findFullById(userId, boardId));
    }
}
