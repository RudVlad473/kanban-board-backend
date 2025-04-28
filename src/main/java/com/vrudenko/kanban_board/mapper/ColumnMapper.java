package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ColumnMapper {
  ColumnEntity fromSaveColumnRequestDTO(SaveColumnRequestDTO dto);

  SaveColumnRequestDTO toSaveColumnRequestDTO(ColumnEntity entity);

  List<SaveColumnRequestDTO> toSaveColumnRequestDTOList(List<ColumnEntity> entities);
}
