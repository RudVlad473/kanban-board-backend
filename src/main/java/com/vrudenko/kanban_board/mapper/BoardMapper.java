package com.vrudenko.kanban_board.mapper;

import java.util.List;

import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;

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

    // id is never auto-mapped here despite SaveBoardRequestDTO.id sharing a name with
    // BoardEntity's primary key: BoardEntity uses plain Lombok @Builder (not @SuperBuilder), and
    // that generated builder has no id(...) method for a field inherited from BaseEntity, so
    // MapStruct's builder-based construction cannot reach it -- verified by attempting an explicit
    // `@Mapping(target = "id", ignore = true)` here, which MapStruct itself rejected as an unknown
    // target property. BoardService#save assigns the id explicitly, after this mapping, via
    // BoardEntity's inherited setId (from BaseEntity's Lombok @Setter, a separate code path the
    // builder does not use).
    BoardEntity fromSaveBoardRequestDTO(SaveBoardRequestDTO dto);

    SaveBoardRequestDTO toSaveBoardRequestDTO(BoardEntity dto);

    UpdateBoardRequestDTO toUpdateBoardRequestDTO(BoardEntity dto);
}
