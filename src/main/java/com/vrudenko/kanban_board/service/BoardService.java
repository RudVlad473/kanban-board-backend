package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import com.vrudenko.kanban_board.repository.BoardRepository;
import java.util.List;
import java.util.Optional;
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

  public Boolean deleteById(String id) {
    var boardToDelete = boardRepository.findById(id);

    if (boardToDelete.isEmpty()) {
      return false;
    }

    boardRepository.deleteById(boardToDelete.get().getId());

    return true;
  }

  public Optional<BoardResponseDTO> updateById(String id, SaveBoardRequestDTO boardDTO) {
    var boardToUpdate = boardRepository.findById(id);

    if (boardToUpdate.isEmpty()) {
      return Optional.empty();
    }

    // TODO: Disallow duplicating board names for a single user

    boardToUpdate.get().setName(boardDTO.getName());

    var savedBoard = boardRepository.save(boardToUpdate.get());

    return Optional.of(boardMapper.toResponseDTO(savedBoard));
  }
}
