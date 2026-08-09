package com.vrudenko.kanban_board.dto.board_dto;

import com.vrudenko.kanban_board.base.entity.BaseBoard;
import com.vrudenko.kanban_board.base.entity.BaseId;
import com.vrudenko.kanban_board.dto.column_dto.ColumnFullResponseDTO;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * The root of GAP-04's nested board read (see {@code 06-05-PLAN.md}'s {@code
 * flat_dto_exception_justification} block for why this is the one deliberate exception to this
 * codebase's flat-DTO convention). Carries every column, each with its tasks, each with its
 * subtasks, in one document -- replacing the four-round-trip fan-out the flat {@code
 * BoardResponseDTO}/{@code ColumnResponseDTO}/{@code TaskResponseDTO}/{@code SubtaskResponseDTO}
 * endpoints require today.
 */
@Getter
@Setter
@EqualsAndHashCode
public class BoardFullResponseDTO implements BaseId, BaseBoard {
    private String id;
    private String name;

    // D-13/D-15: the board's own version, alongside the column/task/subtask versions this
    // document already carries -- so a client reading the nested document has everything it needs
    // to issue a subsequent PUT /boards/{boardId} without a separate flat GET first.
    private Long version;

    private List<ColumnFullResponseDTO> columns;
}
