package com.vrudenko.kanban_board.dto.subtask_dto;

import com.vrudenko.kanban_board.base.entity.BaseSubtask;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.annotation.SubtaskTitle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
@Builder
public class SaveSubtaskRequestDTO implements BaseSubtask {
    @SubtaskTitle private String title;
}
