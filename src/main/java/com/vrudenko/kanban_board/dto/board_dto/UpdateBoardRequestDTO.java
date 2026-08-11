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
    /**
     * If more fields are added, don't forget to add validation so at least one of them are present
     * You can see example with UpdateTaskRequestDTO
     */
    @BoardName @OptionalNotBlank private String name;

    // D-13: required so BoardService.updateById can reject a stale write, matching
    // UpdateColumnRequestDTO/UpdateTaskRequestDTO/UpdateSubtaskRequestDTO's shape. No
    // atLeastOneFieldPopulated() cross-check -- name is this DTO's only field besides version, so
    // there is nothing to cross-check it against (docs/CODE_STYLE.md rule 6). As of quick task
    // 260811-ufu (D-02), this DTO's shape no longer matches UpdateColumnRequestDTO: name here may
    // genuinely be omitted (a version-only board update is accepted), whereas
    // UpdateColumnRequestDTO.name is deliberately mandatory -- see that class's Javadoc for why.
    @NotNull private Long version;
}
