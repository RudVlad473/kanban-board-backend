package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * If interface or an abstract class is used here, it should provide an implementation, otherwise it
 * won't work
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BoardMapper {
    BoardResponseDTO toResponseDTO(BoardEntity dto);

    List<BoardResponseDTO> toResponseDTOList(List<BoardEntity> dto);

    BoardEntity fromSaveBoardRequestDTO(SaveBoardRequestDTO dto);

    SaveBoardRequestDTO toSaveBoardRequestDTO(BoardEntity dto);

    UpdateBoardRequestDTO toUpdateBoardRequestDTO(BoardEntity dto);
}
