package com.vrudenko.kanban_board.dto.subtask_dto;

import com.vrudenko.kanban_board.base.entity.BaseId;
import com.vrudenko.kanban_board.base.entity.BaseSubtask;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@EqualsAndHashCode
public class SubtaskResponseDTO implements BaseId, BaseSubtask {
    private String id;
    private String title;
    private Boolean isCompleted;
}
