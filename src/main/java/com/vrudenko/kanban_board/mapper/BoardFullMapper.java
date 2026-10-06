package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.board_dto.BoardFullResponseDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Map a fetch-joined BoardEntity graph into one nested BoardFullResponseDTO,
 * composing ColumnFullMapper and TaskFullMapper via MapStruct's uses.
 *
 * The graph is fetched by a chained LEFT JOIN FETCH query (see
 * com.vrudenko.kanban_board.repository.BoardRepository) and mapped entirely inside
 * the @Transactional service method, so no unfetched association is touched outside a
 * transaction.
 *
 * Decisions:
 *
 * BoardEntity.column is a singular name on a Set-typed field, an existing
 * inconsistency deliberately not renamed here (a JPA field rename with an existing mappedBy
 * reference is out of scope). The explicit @Mapping below is required: MapStruct matches by
 * name and would otherwise silently leave columns null under
 * ReportingPolicy.IGNORE.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {ColumnFullMapper.class})
public interface BoardFullMapper {
    @Mapping(source = "column", target = "columns")
    BoardFullResponseDTO toBoardFullResponseDTO(BoardEntity entity);
}
