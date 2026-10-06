package com.vrudenko.kanban_board.dto.column_dto;

import com.vrudenko.kanban_board.base.entity.BaseColumn;
import com.vrudenko.kanban_board.constant.ValidationConstants;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Update a column's name, which is deliberately mandatory, unlike every other single-field
 * Update*RequestDTO.
 *
 * Decisions:
 *
 * name is the DTO's only mutable property, so a version-only column update has no use
 * case: nothing else a caller could be changing would justify omitting it. The investigation behind
 * this found no test in BoardServiceTest / BoardControllerTest exercising a
 * version-only column update, and no mockup evidence of a "touch the resource without renaming it"
 * flow. @NotBlank here does the job @OptionalNotBlank (see
 * docs/CODE_STYLE.md rule 12) does on the other optional name/title fields, plus the null
 * rejection those fields deliberately keep, so an audit comparing this DTO to
 * UpdateBoardRequestDTO, UpdateTaskRequestDTO and UpdateSubtaskRequestDTO sees a
 * documented answer instead of an inconsistency.
 */
@Getter
@Setter
@Builder
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateColumnRequestDTO implements BaseColumn {
    @NotBlank(message = "Column name cannot be empty") @Size(
            min = ValidationConstants.MIN_COLUMN_NAME_LENGTH,
            max = ValidationConstants.MAX_COLUMN_NAME_LENGTH,
            message = ValidationConstants.COLUMN_NAME_LENGTH_VALIDATION_MESSAGE)
    private String name;

    @NotNull private Long version;
}
