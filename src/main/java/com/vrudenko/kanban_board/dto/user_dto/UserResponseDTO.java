package com.vrudenko.kanban_board.dto.user_dto;

import com.vrudenko.kanban_board.base.entity.BaseId;
import com.vrudenko.kanban_board.base.entity.BaseUser;
import com.vrudenko.kanban_board.entity.ThemePreference;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@EqualsAndHashCode
public class UserResponseDTO implements BaseId, BaseUser {
    private String id;
    private String email;
    private String displayName;

    // D-05/GAP-05: UserMapper.toResponseDTO maps this by name from UserEntity.theme, no explicit
    // @Mapping needed. Never nullable -- UserEntity.theme is NOT NULL with a LIGHT default (D-12).
    private ThemePreference theme;
}
