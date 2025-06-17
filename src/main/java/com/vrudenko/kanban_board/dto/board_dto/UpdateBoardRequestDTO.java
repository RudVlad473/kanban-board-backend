package com.vrudenko.kanban_board.dto.board_dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vrudenko.kanban_board.base.entity.BaseBoard;
import com.vrudenko.kanban_board.dto.annotation.BoardName;
import lombok.*;

/** DTO for {@link com.vrudenko.kanban_board.entity.BoardEntity} */
@Getter
@Setter
@Builder
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateBoardRequestDTO implements BaseBoard {
    /**
     * If more fields are added, don't forget to add validation so at least one of them are present
     * You can see example with UpdateTaskRequestDTO
     */
    @BoardName private String name;
}
