package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.board_dto.BoardFullResponseDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Map a fetch-joined {@link BoardEntity} graph into one nested {@link BoardFullResponseDTO},
 * composing {@link ColumnFullMapper} and {@link TaskFullMapper} via MapStruct's {@code uses}.
 *
 * <p>The graph is fetched by a chained {@code LEFT JOIN FETCH} query (see {@link
 * com.vrudenko.kanban_board.repository.BoardRepository}) and mapped entirely inside the
 * {@code @Transactional} service method, so no unfetched association is touched outside a
 * transaction.
 *
 * <p>Decisions:
 *
 * <p>{@code BoardEntity.column} is a singular name on a {@code Set}-typed field, an existing
 * inconsistency deliberately not renamed here (a JPA field rename with an existing {@code mappedBy}
 * reference is out of scope). The explicit {@code @Mapping} below is required: MapStruct matches by
 * name and would otherwise silently leave {@code columns} null under {@code
 * ReportingPolicy.IGNORE}.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {ColumnFullMapper.class})
public interface BoardFullMapper {
    @Mapping(source = "column", target = "columns")
    BoardFullResponseDTO toBoardFullResponseDTO(BoardEntity entity);
}
