package com.vrudenko.kanban_board.dto.column_dto;

import com.vrudenko.kanban_board.base.entity.BaseColumn;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
@Builder
public class SaveColumnRequestDTO implements BaseColumn {
  @NotBlank(message = "Column name cannot be empty")
  @Size(
      min = ValidationConstants.MIN_COLUMN_NAME_LENGTH,
      max = ValidationConstants.MAX_COLUMN_NAME_LENGTH,
      message = ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE)
  private String name;
}
