package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import java.util.List;

/**
 * If interface or an abstract class is used here, it should provide an implementation, otherwise it
 * won't work
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface BoardMapper {
  BoardResponseDTO toResponseDTO(BoardEntity dto);

  List<BoardResponseDTO> toResponseDTOList(List<BoardEntity> dto);

  BoardEntity fromSaveBoardRequestDTO(SaveBoardRequestDTO dto);

  SaveBoardRequestDTO toSaveBoardRequestDTO(BoardEntity dto);
}
