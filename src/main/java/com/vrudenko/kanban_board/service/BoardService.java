package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import com.vrudenko.kanban_board.repository.BoardRepository;
import com.vrudenko.kanban_board.repository.UserRepository;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class BoardService {
  @Autowired private BoardRepository boardRepository;
  @Autowired private BoardMapper boardMapper;
  @Autowired private UserRepository userRepository;

  public List<BoardResponseDTO> findAllByUserId(String userId) {
    return boardMapper.toResponseDTOList(boardRepository.findAllByUserId(userId));
  }

  @VisibleForTesting
  public List<BoardResponseDTO> findAll() {
    return boardMapper.toResponseDTOList(boardRepository.findAll());
  }

  public BoardResponseDTO save(String userId, SaveBoardRequestDTO boardDTO) {
    var user = userRepository.findById(userId);
    var board = boardMapper.fromSaveBoardRequestDTO(boardDTO);
    board.setUser(user.get());
    // TODO: Disallow duplicating board names for a single user
    var savedBoard = boardRepository.save(board);

    return boardMapper.toResponseDTO(savedBoard);
  }

  public Boolean deleteById(String userId, String boardId) throws AccessDeniedException {
    var board = findById(userId, boardId);

    var userOwnsBoard = board.getUser().getId().equals(userId);
    if (!userOwnsBoard) {
      throw new AccessDeniedException("You do not have access to that board");
    }

    boardRepository.deleteById(boardId);

    return true;
  }

  public BoardEntity findById(String userId, String boardId) {
    var user = userRepository.findById(userId);

    if (user.isEmpty()) {
      throw new EntityNotFoundException();
    }

    var board = boardRepository.findById(boardId);

    if (board.isEmpty()) {
      throw new EntityNotFoundException("Board was not found");
    }

    var userOwnsBoard = board.get().getUser().getId().equals(userId);

    if (!userOwnsBoard) {
      throw new AccessDeniedException("You do not have access to this board");
    }

    return board.get();
  }

  public Optional<BoardResponseDTO> updateById(
      String userId, String boardId, SaveBoardRequestDTO boardDTO) throws AccessDeniedException {
    var boardToUpdate = findById(userId, boardId);

    var userOwnsBoard = boardToUpdate.getUser().getId().equals(userId);
    if (!userOwnsBoard) {
      throw new AccessDeniedException("You do not have access to that board");
    }

    // TODO: Disallow duplicating board names for a single user

    boardToUpdate.setName(boardDTO.getName());

    var savedBoard = boardRepository.save(boardToUpdate);

    return Optional.of(boardMapper.toResponseDTO(savedBoard));
  }
}
