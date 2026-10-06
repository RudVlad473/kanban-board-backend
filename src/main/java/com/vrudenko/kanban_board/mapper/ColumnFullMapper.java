package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.column_dto.ColumnFullResponseDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Map a column with its tasks, between BoardFullMapper and TaskFullMapper.
 *
 * ColumnEntity.task is a singular name on a Set-typed field, deliberately not
 * renamed (see BoardFullMapper), so the explicit @Mapping is required: without it
 * MapStruct silently leaves tasks null under ReportingPolicy.IGNORE.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {TaskFullMapper.class})
public interface ColumnFullMapper {
    @Mapping(source = "task", target = "tasks")
    ColumnFullResponseDTO toColumnFullResponseDTO(ColumnEntity entity);
}
