package com.vrudenko.kanban_board.dto.column_dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Require targetPosition, unlike MoveTaskRequestDTO's nullable one.
 *
 * A task move has a meaningful no-position meaning ("move column, keep default placement"),
 * while a column reorder with no target position asks for nothing at all.
 */
@Getter
@Setter
@Builder
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReorderColumnRequestDTO {
    @NotNull private Long version;

    @NotNull @Min(0) private Integer targetPosition;
}
