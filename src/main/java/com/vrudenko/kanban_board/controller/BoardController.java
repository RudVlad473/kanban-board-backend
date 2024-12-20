package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.service.BoardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class BoardController {
  @Autowired private BoardService boardService;

  @RequestMapping("/boards")
  public List<BoardEntity> getAllBoards() {
    return this.boardService.getAllBoards();
  }
}
