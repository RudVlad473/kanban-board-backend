package com.vrudenko.kanban_board.dto.board_dto;

import java.time.Instant;
import java.util.List;

import com.vrudenko.kanban_board.base.entity.BaseBoard;
import com.vrudenko.kanban_board.base.entity.BaseId;
import com.vrudenko.kanban_board.dto.column_dto.ColumnFullResponseDTO;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * The root of the nested board read: every column, each with its tasks, each with its subtasks, in
 * one document instead of the four-round-trip fan-out of the flat DTO endpoints.
 *
 * <p>It is the one deliberate exception to this codebase's flat-DTO convention.
 */
@Getter
@Setter
@EqualsAndHashCode
public class BoardFullResponseDTO implements BaseId, BaseBoard {
    private String id;
    private String name;

    // Carried alongside the column/task/subtask versions so a client reading the nested document
    // can issue a subsequent PUT /boards/{boardId} without a separate flat GET first.
    private Long version;

    // Carried here too, so a client reading this document need not GET /boards just to learn
    // when the board was created.
    private Instant createdAt;

    private List<ColumnFullResponseDTO> columns;
}
