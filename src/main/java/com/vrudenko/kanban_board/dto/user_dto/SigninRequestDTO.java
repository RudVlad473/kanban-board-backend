package com.vrudenko.kanban_board.dto.user_dto;

import com.vrudenko.kanban_board.dto.annotation.AppEmail;
import com.vrudenko.kanban_board.dto.annotation.Password;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
public class SigninRequestDTO {
    @AppEmail
    private String email;

    @Password
    private String password;
}
