package com.vrudenko.kanban_board.dto.board_dto;

import com.vrudenko.kanban_board.base.BaseId;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.UUID;

@Getter
@Setter
public class DeleteBoardByIdRequestDTO implements BaseId {
  @NotNull @UUID private String id;
}
