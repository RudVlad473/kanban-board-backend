package com.vrudenko.kanban_board.dto.user_dto;

import com.vrudenko.kanban_board.base.BaseBoard;
import com.vrudenko.kanban_board.base.BaseId;
import com.vrudenko.kanban_board.base.BaseUser;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

// TODO: make shared dto annotation
@Getter
@Setter
@EqualsAndHashCode
public class UserResponseDTO implements BaseId, BaseUser {
  private String id;
  private String email;
  private String displayName;
}
