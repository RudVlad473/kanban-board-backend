package com.vrudenko.kanban_board.dto.user_dto;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginDTO extends SaveUserRequestDTO {
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private final String PASSWORD_LENGTH_VALIDATION_MESSAGE =
      "Password cannot be less than "
          + ValidationConstants.MIN_PASSWORD_LENGTH
          + " character and more than "
          + ValidationConstants.MAX_PASSWORD_LENGTH
          + " characters";

  /**
   * Minimum length: 8 characters; Maximum length: 64 characters (to prevent abuse); At least 1
   * uppercase letter; At least 1 lowercase letter; At least 1 digit; At least 1 special character
   */
  @NotBlank(message = "Password cannot be empty")
  @Size(
      min = ValidationConstants.MIN_PASSWORD_LENGTH,
      max = ValidationConstants.MAX_PASSWORD_LENGTH,
      message = PASSWORD_LENGTH_VALIDATION_MESSAGE)
  @Pattern(
      regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=[\\]{};':\"\\\\|,.<>/?]).+$",
      message =
          "Password must contain at least one uppercase letter, one lowercase letter, one number, and one special character")
  String password;
}
