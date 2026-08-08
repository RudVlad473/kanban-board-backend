package com.vrudenko.kanban_board.dto.column_dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Deliberately asymmetric with {@link com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO}'s
 * nullable {@code targetPosition}: a task move has a meaningful no-position semantic ("move column,
 * keep default placement"), whereas a column reorder request with no target position asks for
 * nothing at all — so here the field is mandatory.
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
