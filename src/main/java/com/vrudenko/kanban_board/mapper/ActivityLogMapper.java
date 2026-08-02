package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.activity_dto.ActivityLogResponseDTO;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * No list variant is declared here — the service maps a {@link
 * org.springframework.data.domain.Page} through Spring Data's own element mapping ({@code
 * Page#map}), and a {@code Page} is not a {@link java.util.List}.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ActivityLogMapper {
    ActivityLogResponseDTO toActivityLogResponseDTO(ActivityLogEntity entity);
}
