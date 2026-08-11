package com.vrudenko.kanban_board.dto.task_dto;

import java.util.Optional;

import com.vrudenko.kanban_board.base.entity.BaseTask;
import com.vrudenko.kanban_board.dto.annotation.Description;
import com.vrudenko.kanban_board.dto.annotation.TaskTitle;

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
public class UpdateTaskRequestDTO implements BaseTask {
    @TaskTitle String title;

    @Description String description;

    @NotNull private Long version;

    @AssertTrue(message = "Either 'title' or 'description' (or both) must be provided.")
    private boolean atLeastOneFieldPopulated() {
        var isTitlePresent = Optional.ofNullable(getTitle()).isPresent();
        var isDescriptionPresent = Optional.ofNullable(getDescription()).isPresent();

        return isTitlePresent || isDescriptionPresent;
    }
}
