package com.vrudenko.kanban_board.dto.board_dto;

import com.vrudenko.kanban_board.base.entity.BaseBoard;
import com.vrudenko.kanban_board.dto.annotation.BoardName;
import lombok.*;

/** DTO for {@link com.vrudenko.kanban_board.entity.BoardEntity} */
@Getter
@Setter
@Builder
@EqualsAndHashCode
public class UpdateBoardRequestDTO implements BaseBoard {
  @BoardName private String name;
}
