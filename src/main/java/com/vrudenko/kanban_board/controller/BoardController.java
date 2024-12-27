package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.service.BoardService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/boards")
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
}
