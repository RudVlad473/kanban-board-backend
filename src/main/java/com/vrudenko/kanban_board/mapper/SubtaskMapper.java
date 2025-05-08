package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.entity.SubtaskEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SubtaskMapper {
  SubtaskEntity fromSaveSubtaskRequestDTO(SaveSubtaskRequestDTO dto);

  SubtaskResponseDTO toTaskResponseDTO(SubtaskEntity entity);
}
