package com.vrudenko.kanban_board.dto.subtask_dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vrudenko.kanban_board.base.entity.BaseSubtask;
import com.vrudenko.kanban_board.dto.annotation.SubtaskTitle;
import jakarta.validation.constraints.AssertTrue;
import java.util.Optional;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateSubtaskRequestDTO implements BaseSubtask {
    @SubtaskTitle private String title;

    private Boolean isCompleted;

    @AssertTrue(message = "Either 'title' or 'isCompleted' (or both) must be provided.")
    private boolean atLeastOneFieldPopulated() {
        var isTitlePresent = Optional.ofNullable(getTitle()).isPresent();
        var isIsCompletedPresent = Optional.ofNullable(getIsCompleted()).isPresent();

        return isTitlePresent || isIsCompletedPresent;
    }
}
