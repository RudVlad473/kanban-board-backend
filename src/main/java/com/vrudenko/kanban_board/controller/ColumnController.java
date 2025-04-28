package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.security.CurrentUserId;
import com.vrudenko.kanban_board.service.BoardService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS)
@PreAuthorize("isAuthenticated()")
public class ColumnController {
  @Autowired private BoardService boardService;

//  @GetMapping
//  public List<BoardResponseDTO> findAll(@CurrentUserId String userId) {
//    return this.boardService.findAll();
//  }
//
//  @PostMapping
//  public BoardResponseDTO save(@RequestBody SaveBoardRequestDTO dto) {
//    return this.boardService.save(dto);
//  }
}
