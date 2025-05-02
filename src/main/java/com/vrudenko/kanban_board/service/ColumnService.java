package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import com.vrudenko.kanban_board.mapper.ColumnMapper;
import com.vrudenko.kanban_board.repository.ColumnRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ColumnService {
  @Autowired private ColumnRepository columnRepository;
  @Autowired private ColumnMapper columnMapper;

  public List<ColumnResponseDTO> findAllByBoardId(String boardId) throws EntityNotFoundException {
    /*
     Since we check whether user owns the board in the 'findById' method,
     we don't need to replicate this logic here
     Same for handling of 'Optional' logic
    */

    return columnMapper.toColumnResponseDTOList(columnRepository.findAllByBoardId(boardId));
  }

  public int getColumnCountByBoardId(String boardId) {
    return findAllByBoardId(boardId).size();
  }

  public Optional<ColumnEntity> findById(String id) {
    return columnRepository.findById(id);
  }

  @Transactional
  public void deleteAllByBoardId(String userId, String boardId) {
    // taskService.deleteAllByColumnId(userId, boardId);

    columnRepository.deleteAllByBoardId(boardId);
  }

  public ColumnResponseDTO save(SaveColumnRequestDTO columnDTO, BoardEntity board) {
    var column = columnMapper.fromSaveColumnRequestDTO(columnDTO);
    column.setBoard(board);

    columnRepository.save(column);

    return columnMapper.toColumnResponseDTO(column);
  }

  @VisibleForTesting
  public void deleteAll() {
    columnRepository.deleteAll();
  }

  // TODO: implement delete logic
}
