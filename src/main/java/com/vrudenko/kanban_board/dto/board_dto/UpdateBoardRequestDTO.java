package com.vrudenko.kanban_board.dto.board_dto;

import com.vrudenko.kanban_board.base.entity.BaseBoard;
import com.vrudenko.kanban_board.dto.annotation.BoardName;
import com.vrudenko.kanban_board.dto.annotation.OptionalNotBlank;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/** DTO for {@link com.vrudenko.kanban_board.entity.BoardEntity} */
@Getter
@Setter
@Builder
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateBoardRequestDTO implements BaseBoard {
    /** If more fields are added, validate that at least one is present, as UpdateTaskRequestDTO. */
    @BoardName @OptionalNotBlank private String name;

    // Required so BoardService.updateById can reject a stale write.
    //
    // There is no atLeastOneFieldPopulated() cross-check: name is the only other field, so there
    // is nothing to cross-check it against (docs/CODE_STYLE.md rule 6). Unlike
    // UpdateColumnRequestDTO, name here may be omitted (a version-only board update is accepted);
    // see that class's Javadoc for why the column's is mandatory.
    @NotNull private Long version;
}
