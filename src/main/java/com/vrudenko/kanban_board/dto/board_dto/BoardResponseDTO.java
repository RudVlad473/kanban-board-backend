package com.vrudenko.kanban_board.dto.board_dto;

import com.vrudenko.kanban_board.base.entity.BaseBoard;
import com.vrudenko.kanban_board.base.entity.BaseId;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
@Builder
public class BoardResponseDTO implements BaseId, BaseBoard {
    private String id;
    private String name;

    // D-13/A1: carried on the flat response (not just BoardFullResponseDTO, D-15's literal scope)
    // so POST /boards, PUT /boards/{boardId} and GET /boards can all chain a rename without an
    // extra /full fetch purely to re-read a number the prior response already knew -- the same
    // precedent ColumnResponseDTO already sets. See 07.1-05-PLAN.md's task 1 checkpoint.
    private Long version;
}
