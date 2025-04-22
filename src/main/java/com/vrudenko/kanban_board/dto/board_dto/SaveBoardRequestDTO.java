package com.vrudenko.kanban_board.dto.board_dto;

import com.vrudenko.kanban_board.base.BaseBoard;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SaveBoardRequestDTO implements BaseBoard {
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private final String NAME_LENGTH_VALIDATION_MESSAGE =
      "Board name cannot be less than "
          + ValidationConstants.MIN_BOARD_NAME_LENGTH
          + " character and more than "
          + ValidationConstants.MAX_BOARD_NAME_LENGTH
          + " characters";

  @NotBlank(message = "Board name cannot be empty")
  @Size(
      min = ValidationConstants.MIN_BOARD_NAME_LENGTH,
      max = ValidationConstants.MAX_BOARD_NAME_LENGTH,
      message = NAME_LENGTH_VALIDATION_MESSAGE)
  @Pattern(
      regexp = "^[a-zA-Z0-9 ]*$",
      message = "Board name may only contain letters, numbers & spaces")
  private String name;
}
