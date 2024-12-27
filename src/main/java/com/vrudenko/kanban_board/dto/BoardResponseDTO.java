package com.vrudenko.kanban_board.dto;

import com.vrudenko.kanban_board.base.BaseBoard;
import com.vrudenko.kanban_board.base.BaseId;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BoardResponseDTO implements BaseId, BaseBoard {
  private String id;
  private String name;
}
