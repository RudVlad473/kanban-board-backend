package com.vrudenko.kanban_board.dto.user_dto;

import com.vrudenko.kanban_board.base.BaseUser;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SaveUserRequestDTO implements BaseUser {
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private final String DISPLAY_NAME_LENGTH_VALIDATION_MESSAGE =
      "Display name cannot be less than "
          + ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH
          + " character and more than "
          + ValidationConstants.MAX_USER_DISPLAY_NAME_LENGTH
          + " characters";

  @Size(
      min = ValidationConstants.MIN_BOARD_NAME_LENGTH,
      max = ValidationConstants.MAX_BOARD_NAME_LENGTH,
      message = DISPLAY_NAME_LENGTH_VALIDATION_MESSAGE)
  @Pattern(regexp = "^[a-zA-Z ]*$", message = "Display name may only contain letters & spaces")
  private String displayName;

  @NotBlank(message = "Email cannot be empty")
  @Email
  private String email;
}
