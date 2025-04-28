package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.mapper.ColumnMapper;
import com.vrudenko.kanban_board.repository.ColumnRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ColumnService {
  @Autowired BoardService boardService;
  @Autowired ColumnRepository columnRepository;
  @Autowired ColumnMapper columnMapper;

  public SaveColumnRequestDTO save(String userId, String boardId, SaveColumnRequestDTO dto) {
    var board = boardService.findById(userId, boardId);

    var column = columnMapper.fromSaveColumnRequestDTO(dto);
    column.setBoard(board);

    return columnMapper.toSaveColumnRequestDTO(columnRepository.save(column));
  }

  public List<SaveColumnRequestDTO> findAllByBoardId(String userId, String boardId) {
    /*
     Since we check whether user owns the board in the 'findById' method,
     we don't need to replicate this logic here
     Same for handling of 'Optional' logic
    */
    var board = boardService.findById(userId, boardId);

    return columnMapper.toSaveColumnRequestDTOList(
        columnRepository.findAllByBoardId(board.getId()));
  }

  // TODO: implement delete logic
}
