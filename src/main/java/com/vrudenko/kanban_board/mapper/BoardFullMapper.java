package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.board_dto.BoardFullResponseDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Root of GAP-04's nested board read composition. Composes with {@link ColumnFullMapper} (which
 * composes with {@link TaskFullMapper}, which reuses the existing {@link SubtaskMapper}) via
 * MapStruct's {@code uses} attribute to map a fetch-joined {@link BoardEntity} graph into a single
 * nested {@link BoardFullResponseDTO} document -- the one deliberate exception to this codebase's
 * flat-DTO convention, justified in {@code 06-05-PLAN.md}'s {@code
 * flat_dto_exception_justification} block: the entity graph is fetched eagerly via a chained {@code
 * LEFT JOIN FETCH} query (see {@link com.vrudenko.kanban_board.repository.BoardRepository}) and
 * mapped entirely inside the {@code @Transactional} service method, so no unfetched association is
 * ever touched outside a transaction.
 *
 * <p>{@code BoardEntity.column} is a singular field name on a {@code List}-typed field -- a real,
 * existing inconsistency in this codebase, not a typo, and deliberately not renamed here (a JPA
 * field rename with an existing {@code mappedBy} reference is out of this endpoint's scope). The
 * explicit {@code @Mapping} below is required because MapStruct matches by name and would otherwise
 * silently leave {@code columns} null under {@code ReportingPolicy.IGNORE}.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {ColumnFullMapper.class})
public interface BoardFullMapper {
    @Mapping(source = "column", target = "columns")
    BoardFullResponseDTO toBoardFullResponseDTO(BoardEntity entity);
}
