package com.vrudenko.kanban_board.dto.user_dto;

import com.vrudenko.kanban_board.dto.annotation.AppEmail;
import com.vrudenko.kanban_board.dto.annotation.DisplayName;
import com.vrudenko.kanban_board.dto.annotation.Password;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
@Builder
public class SignupRequestDTO {
  @DisplayName private String displayName;

  @AppEmail private String email;

  @Password private String password;
}
