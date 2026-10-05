package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.task_dto.TaskFullResponseDTO;
import com.vrudenko.kanban_board.entity.TaskEntity;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Map a task with its subtasks, reusing {@link SubtaskMapper} via {@code uses}.
 *
 * <p>{@code TaskEntity.subtasks} is already plural, so unlike the levels above no explicit
 * {@code @Mapping} is needed, and a subtask has no children, so no {@code SubtaskFullResponseDTO}
 * is needed either.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {SubtaskMapper.class})
public interface TaskFullMapper {
    TaskFullResponseDTO toTaskFullResponseDTO(TaskEntity entity);
}
