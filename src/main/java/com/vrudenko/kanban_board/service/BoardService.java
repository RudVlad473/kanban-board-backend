package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import com.vrudenko.kanban_board.mapper.ColumnMapper;
import com.vrudenko.kanban_board.repository.BoardRepository;
import com.vrudenko.kanban_board.repository.ColumnRepository;
import com.vrudenko.kanban_board.repository.UserRepository;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class BoardService {
  @Autowired private BoardRepository boardRepository;
  @Autowired private BoardMapper boardMapper;
  @Autowired private UserService userService;
  @Autowired private ColumnService columnService;

  public List<BoardResponseDTO> findAllByUserId(String userId) {
    return boardMapper.toResponseDTOList(boardRepository.findAllByUserId(userId));
  }

  @VisibleForTesting
  public List<BoardResponseDTO> findAll() {
    return boardMapper.toResponseDTOList(boardRepository.findAll());
  }

  @Transactional
  public BoardResponseDTO save(String userId, SaveBoardRequestDTO boardDTO) {
    var user = userService.findById(userId);

    var board = boardMapper.fromSaveBoardRequestDTO(boardDTO);
    board.setUser(user);
    // TODO: Disallow duplicating board names for a single user
    var savedBoard = boardRepository.save(board);

    return boardMapper.toResponseDTO(savedBoard);
  }

  @Transactional
  public ColumnResponseDTO addColumnByBoardId(
      String userId, String boardId, SaveColumnRequestDTO columnDTO) {
    var board = findById(userId, boardId);

    return columnService.save(columnDTO, board);
  }

  @Transactional
  public void deleteById(String userId, String boardId) throws AccessDeniedException {
    var board = findById(userId, boardId);

    var userOwnsBoard = board.getUser().getId().equals(userId);
    if (!userOwnsBoard) {
      throw new AppAccessDeniedException("Board");
    }

    columnService.deleteAllByBoardId(userId, boardId);

    boardRepository.deleteById(boardId);
  }

  public BoardEntity findById(String userId, String boardId)
      throws AppEntityNotFoundException, AppAccessDeniedException {
    var user = userService.findById(userId);

    var board = boardRepository.findById(boardId);

    if (board.isEmpty()) {
      throw new AppEntityNotFoundException("Board");
    }

    var userOwnsBoard = board.get().getUser().getId().equals(user.getId());

    if (!userOwnsBoard) {
      throw new AppAccessDeniedException("Board");
    }

    return board.get();
  }

  @Transactional
  public Optional<BoardResponseDTO> updateById(
      String userId, String boardId, SaveBoardRequestDTO boardDTO)
      throws AppAccessDeniedException, AppEntityNotFoundException {
    var boardToUpdate = findById(userId, boardId);

    var userOwnsBoard = boardToUpdate.getUser().getId().equals(userId);
    if (!userOwnsBoard) {
      throw new AppAccessDeniedException("Board");
    }

    // TODO: Disallow duplicating board names for a single user

    boardToUpdate.setName(boardDTO.getName());

    var savedBoard = boardRepository.save(boardToUpdate);

    return Optional.of(boardMapper.toResponseDTO(savedBoard));
  }

  @VisibleForTesting
  void deleteAll() {
    for (var boardEntity : boardRepository.findAll()) {
      deleteById(boardEntity.getUser().getId(), boardEntity.getId());
    }
  }
}
