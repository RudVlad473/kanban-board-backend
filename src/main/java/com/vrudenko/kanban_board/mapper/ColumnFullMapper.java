package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.column_dto.ColumnFullResponseDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Middle level of GAP-04's nested board read composition ({@link BoardFullMapper} uses this, this
 * uses {@link TaskFullMapper}). {@code ColumnEntity.task} is a singular field name on a {@code
 * List}-typed field -- verified, existing, and deliberately not renamed (see {@link
 * BoardFullMapper}'s Javadoc for the full reasoning) -- so the explicit {@code @Mapping} below is
 * required: without it MapStruct silently leaves {@code tasks} null under {@code
 * ReportingPolicy.IGNORE}.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {TaskFullMapper.class})
public interface ColumnFullMapper {
    @Mapping(source = "task", target = "tasks")
    ColumnFullResponseDTO toColumnFullResponseDTO(ColumnEntity entity);
}
