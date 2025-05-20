package com.vrudenko.kanban_board.dto.board_dto;

import com.vrudenko.kanban_board.base.entity.BaseBoard;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@EqualsAndHashCode
public class SaveBoardRequestDTO implements BaseBoard {
  @NotBlank(message = "Board name must not be blank")
  @Size(
      min = ValidationConstants.MIN_BOARD_NAME_LENGTH,
      max = ValidationConstants.MAX_BOARD_NAME_LENGTH,
      message = ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE)
  @Pattern(
      regexp = "^[a-zA-Z0-9 ]*$",
      message = "Board name may only contain letters, numbers & spaces")
  private String name;
}
