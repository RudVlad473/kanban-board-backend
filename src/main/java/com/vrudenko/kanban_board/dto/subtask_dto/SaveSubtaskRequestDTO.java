package com.vrudenko.kanban_board.dto.subtask_dto;

import com.vrudenko.kanban_board.base.entity.BaseSubtask;
import com.vrudenko.kanban_board.dto.annotation.SubtaskTitle;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
@Builder
public class SaveSubtaskRequestDTO implements BaseSubtask {
    @NotBlank(message = "Subtask title cannot be empty") @SubtaskTitle
    private String title;
}
