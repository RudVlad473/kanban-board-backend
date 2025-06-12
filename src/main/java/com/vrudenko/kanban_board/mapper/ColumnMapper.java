package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ColumnMapper {
    ColumnEntity fromSaveColumnRequestDTO(SaveColumnRequestDTO dto);

    SaveColumnRequestDTO toSaveColumnRequestDTO(ColumnEntity entity);

    List<SaveColumnRequestDTO> toSaveColumnRequestDTOList(List<ColumnEntity> entities);

    ColumnResponseDTO toColumnResponseDTO(ColumnEntity entity);

    List<ColumnResponseDTO> toColumnResponseDTOList(List<ColumnEntity> entities);
}
