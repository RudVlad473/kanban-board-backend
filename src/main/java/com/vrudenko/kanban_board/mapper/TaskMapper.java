package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.entity.TaskEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TaskMapper {
  TaskEntity fromSaveTaskRequestDTO(SaveTaskRequestDTO dto);

  TaskResponseDTO toTaskResponseDTO(TaskEntity entity);

  List<TaskResponseDTO> toTaskResponseDTOList(List<TaskEntity> entities);
}
