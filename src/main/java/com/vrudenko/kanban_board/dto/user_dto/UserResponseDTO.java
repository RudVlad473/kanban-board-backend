package com.vrudenko.kanban_board.dto.user_dto;

import com.vrudenko.kanban_board.base.entity.BaseId;
import com.vrudenko.kanban_board.base.entity.BaseUser;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
public class UserResponseDTO implements BaseId, BaseUser {
    private String id;
    private String email;
    private String displayName;
}
