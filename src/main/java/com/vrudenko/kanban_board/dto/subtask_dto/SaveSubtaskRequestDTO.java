package com.vrudenko.kanban_board.dto.subtask_dto;

import com.vrudenko.kanban_board.base.entity.BaseSubtask;
import com.vrudenko.kanban_board.dto.annotation.SubtaskTitle;
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
