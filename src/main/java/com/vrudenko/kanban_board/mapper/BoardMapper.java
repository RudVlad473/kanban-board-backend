package com.vrudenko.kanban_board.mapper;

import java.util.List;

import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BoardMapper {
    BoardResponseDTO toResponseDTO(BoardEntity dto);

    List<BoardResponseDTO> toResponseDTOList(List<BoardEntity> dto);

    // Leave id to BoardService#save, which assigns it after mapping via the inherited setId.
    //
    // It is never auto-mapped despite SaveBoardRequestDTO.id sharing a name with the primary key:
    // BoardEntity uses plain Lombok @Builder (not @SuperBuilder), so its builder has no id(...) for
    // a field inherited from BaseEntity and MapStruct's builder-based construction cannot reach it.
    // Verified: an explicit `@Mapping(target = "id", ignore = true)` is rejected as an unknown
    // target property.
    BoardEntity fromSaveBoardRequestDTO(SaveBoardRequestDTO dto);

    SaveBoardRequestDTO toSaveBoardRequestDTO(BoardEntity dto);

    UpdateBoardRequestDTO toUpdateBoardRequestDTO(BoardEntity dto);
}
