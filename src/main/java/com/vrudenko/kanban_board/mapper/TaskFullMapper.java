package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.task_dto.TaskFullResponseDTO;
import com.vrudenko.kanban_board.entity.TaskEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Leaf level of GAP-04's nested board read composition ({@link BoardFullMapper} uses {@link
 * ColumnFullMapper} uses this). Reuses the existing {@link SubtaskMapper} unchanged via {@code
 * uses} -- {@code TaskEntity.subtasks} is already plural (unlike {@code BoardEntity.column}/{@code
 * ColumnEntity.task}, see {@link ColumnFullMapper}), so no explicit {@code @Mapping} is needed at
 * this level, and a subtask has no children of its own, so no {@code SubtaskFullResponseDTO} is
 * needed either.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {SubtaskMapper.class})
public interface TaskFullMapper {
    TaskFullResponseDTO toTaskFullResponseDTO(TaskEntity entity);
}
