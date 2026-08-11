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
 * Column update DTO — {@code name} is deliberately mandatory (D-02, quick task 260811-ufu), unlike
 * every other single-field {@code Update*RequestDTO} in this codebase.
 *
 * <p>{@code name} is the DTO's only mutable property, so a version-only column update has no use
 * case: there is nothing else a caller could be changing that would justify omitting {@code name}.
 * The investigation behind this decision found no test in {@code BoardServiceTest} / {@code
 * BoardControllerTest} that exercises a version-only column update, and no mockup evidence of a
 * "touch the resource without renaming it" flow for a single-field DTO. {@code @NotBlank} here does
 * the job {@code @OptionalNotBlank} (see {@code docs/CODE_STYLE.md} rule 12) does elsewhere on this
 * codebase's other optional name/title fields, plus the additional null rejection those fields
 * deliberately keep — so a future audit comparing this DTO against {@code
 * UpdateBoardRequestDTO}/{@code UpdateTaskRequestDTO}/{@code UpdateSubtaskRequestDTO} sees a
 * documented answer instead of an inconsistency.
 *
 * <p>Follows the same class-level exemption-note precedent as {@link
 * com.vrudenko.kanban_board.dto.user_dto.UpdateThemeRequestDTO}.
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
