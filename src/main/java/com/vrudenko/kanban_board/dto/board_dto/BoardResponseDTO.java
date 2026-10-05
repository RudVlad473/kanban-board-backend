package com.vrudenko.kanban_board.dto.board_dto;

import java.time.Instant;

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

    // Carried on the flat response so POST /boards, PUT /boards/{boardId} and GET /boards can
    // chain a rename without an extra /full fetch just to re-read a version the prior response
    // already knew.
    private Long version;

    private Instant createdAt;
}
