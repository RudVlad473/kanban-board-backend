package com.vrudenko.kanban_board.dto.board_dto;

import com.vrudenko.kanban_board.base.entity.BaseBoard;
import com.vrudenko.kanban_board.dto.annotation.BoardId;
import com.vrudenko.kanban_board.dto.annotation.BoardName;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@EqualsAndHashCode
public class SaveBoardRequestDTO implements BaseBoard {
    @NotBlank(message = "Board name must not be blank") @BoardName
    private String name;

    // Optional: null means the server generates the id (see BoardId and BoardService#save for the
    // assignment and uniqueness path).
    @BoardId private String id;
}
