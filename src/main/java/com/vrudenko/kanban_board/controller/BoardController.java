package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.DeleteBoardByIdRequestDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.service.BoardService;
import java.util.List;
import java.util.Optional;

import org.hibernate.validator.constraints.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BOARDS)
@Validated
@PreAuthorize("isAuthenticated()")
public class BoardController {
  @Autowired private BoardService boardService;

  @GetMapping
  public List<BoardResponseDTO> findAll() {
    return this.boardService.findAll();
  }

  @PostMapping
  public BoardResponseDTO save(@RequestBody SaveBoardRequestDTO dto) {
    return this.boardService.save(dto);
  }

  @DeleteMapping(ApiPaths.BOARD_ID)
  public ResponseEntity<Void> deleteById(@PathVariable @UUID String id) {
    var wasEntityDeleted = this.boardService.deleteById(id);

    var header = wasEntityDeleted ? ResponseEntity.noContent() : ResponseEntity.notFound();

    return header.build();
  }

  @PutMapping(ApiPaths.BOARD_ID)
  public ResponseEntity<Optional<BoardResponseDTO>> updateById(
      @PathVariable @UUID String id, @RequestBody SaveBoardRequestDTO dto) {
    var updatedBoard = boardService.updateById(id, dto);

    if (updatedBoard.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    return ResponseEntity.ok(updatedBoard);
  }
}
