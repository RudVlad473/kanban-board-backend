package com.vrudenko.kanban_board.dto;

import com.vrudenko.kanban_board.base.BaseBoard;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class SaveBoardRequestDTO implements BaseBoard {
  private final int MIN_NAME_LENGTH = 1;
  private final int MAX_NAME_LENGTH = 64;
  private final String NAME_LENGTH_VALIDATION_MESSAGE =
      "Board name cannot be less than "
          + MIN_NAME_LENGTH
          + " character and more than "
          + MAX_NAME_LENGTH
          + " characters";

  @NotBlank(message = "Board name cannot be empty")
  @Size(min = MIN_NAME_LENGTH, max = MAX_NAME_LENGTH, message = NAME_LENGTH_VALIDATION_MESSAGE)
  @Pattern(
      regexp = "^[a-zA-Z0-9 ]*$",
      message = "Board name may only contain letters, numbers & spaces")
  private String name;
}
