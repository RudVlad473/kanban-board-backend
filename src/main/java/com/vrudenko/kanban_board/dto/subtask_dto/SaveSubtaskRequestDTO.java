package com.vrudenko.kanban_board.dto.subtask_dto;

import com.vrudenko.kanban_board.base.entity.BaseSubtask;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
@Builder
public class SaveSubtaskRequestDTO implements BaseSubtask {
    @NotBlank(message = "Title cannot be empty")
    @Size(
            min = ValidationConstants.MIN_SUBTASK_TITLE_LENGTH,
            max = ValidationConstants.MAX_SUBTASK_TITLE_LENGTH,
            message = ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE)
    private String title;
}
