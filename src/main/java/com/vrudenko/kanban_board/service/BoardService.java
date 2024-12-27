package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import com.vrudenko.kanban_board.repository.BoardRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BoardService {
  @Autowired private BoardRepository boardRepository;
  @Autowired private BoardMapper boardMapper;

  public List<BoardResponseDTO> findAll() {
    return boardMapper.toResponseDTOList(boardRepository.findAll());
  }

  public BoardResponseDTO save(SaveBoardRequestDTO boardDTO) {
    var savedBoard = boardRepository.save(boardMapper.fromSaveBoardRequestDTO(boardDTO));

    return boardMapper.toResponseDTO(savedBoard);
  }
}
