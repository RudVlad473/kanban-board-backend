package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.entity.UserEntity;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import com.vrudenko.kanban_board.repository.BoardRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BoardService {
  @Autowired private BoardRepository boardRepository;
  @Autowired private BoardMapper boardMapper;
  @Autowired private ColumnService columnService;
  @Autowired private OwnershipVerifierService ownershipVerifierService;

  public List<BoardResponseDTO> findAllByUserId(String userId) {
    return boardMapper.toResponseDTOList(boardRepository.findAllByUserId(userId));
  }

  @Transactional
  public ColumnResponseDTO addColumnByBoardId(
      String userId, String boardId, SaveColumnRequestDTO columnDTO) {
    var board = findById(userId, boardId);

    return columnService.save(columnDTO, board);
  }

  @Transactional
  public void deleteById(String userId, String boardId) {
    var board = findById(userId, boardId);

    columnService.deleteAllByBoardId(userId, board.getId());

    boardRepository.deleteById(board.getId());
  }

  @Transactional
  public void deleteAllByUserId(String userId) {
    var boardsOwnedByUser = findAllByUserId(userId);

    for (var board : boardsOwnedByUser) {
      deleteById(userId, board.getId());
    }
  }

  public BoardEntity findById(String userId, String boardId) {
    var pair = ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId);

    return pair.getSecond();
  }

  @Transactional
  public BoardResponseDTO updateById(
      String userId, String boardId, UpdateBoardRequestDTO boardDTO) {
    var boardToUpdate = findById(userId, boardId);

    // TODO: Disallow duplicating board names for a single user

    boardToUpdate.setName(boardDTO.getName());

    var savedBoard = boardRepository.save(boardToUpdate);

    return boardMapper.toResponseDTO(savedBoard);
  }

  public BoardResponseDTO save(SaveBoardRequestDTO dto, UserEntity user) {
    var board = boardMapper.fromSaveBoardRequestDTO(dto);
    board.setUser(user);

    boardRepository.save(board);

    return boardMapper.toResponseDTO(board);
  }

  @VisibleForTesting
  void deleteAll() {
    for (var boardEntity : boardRepository.findAll()) {
      deleteById(boardEntity.getUser().getId(), boardEntity.getId());
    }
  }

  @VisibleForTesting
  public List<BoardResponseDTO> findAll() {
    return boardMapper.toResponseDTOList(boardRepository.findAll());
  }
}
