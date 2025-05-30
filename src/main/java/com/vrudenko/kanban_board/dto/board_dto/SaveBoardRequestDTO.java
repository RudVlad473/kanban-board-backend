package com.vrudenko.kanban_board.dto.board_dto;

import com.vrudenko.kanban_board.base.entity.BaseBoard;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.annotation.BoardName;
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
  @BoardName
  private String name;
}
