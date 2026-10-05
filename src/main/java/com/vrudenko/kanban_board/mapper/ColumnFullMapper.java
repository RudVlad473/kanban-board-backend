package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.column_dto.ColumnFullResponseDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Map a column with its tasks, between {@link BoardFullMapper} and {@link TaskFullMapper}.
 *
 * <p>{@code ColumnEntity.task} is a singular name on a {@code Set}-typed field, deliberately not
 * renamed (see {@link BoardFullMapper}), so the explicit {@code @Mapping} is required: without it
 * MapStruct silently leaves {@code tasks} null under {@code ReportingPolicy.IGNORE}.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {TaskFullMapper.class})
public interface ColumnFullMapper {
    @Mapping(source = "task", target = "tasks")
    ColumnFullResponseDTO toColumnFullResponseDTO(ColumnEntity entity);
}
