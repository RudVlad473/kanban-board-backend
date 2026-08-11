package com.vrudenko.kanban_board.dto.subtask_dto;

import java.util.Optional;

import com.vrudenko.kanban_board.base.entity.BaseSubtask;
import com.vrudenko.kanban_board.dto.annotation.OptionalNotBlank;
import com.vrudenko.kanban_board.dto.annotation.SubtaskTitle;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
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
    @SubtaskTitle @OptionalNotBlank private String title;

    private Boolean isCompleted;

    // Mandatory, not one of the optional fields atLeastOneFieldPopulated() below counts —
    // omitting it would silently disable optimistic locking on this DTO (docs/CODE_STYLE.md
    // rule 6).
    @NotNull private Long version;

    @AssertTrue(message = "Either 'title' or 'isCompleted' (or both) must be provided.")
    private boolean atLeastOneFieldPopulated() {
        var isTitlePresent = Optional.ofNullable(getTitle()).isPresent();
        var isIsCompletedPresent = Optional.ofNullable(getIsCompleted()).isPresent();

        return isTitlePresent || isIsCompletedPresent;
    }
}
