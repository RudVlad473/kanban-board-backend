package com.vrudenko.kanban_board.dto.task_dto;

import com.vrudenko.kanban_board.base.entity.BaseTask;
import com.vrudenko.kanban_board.dto.annotation.Description;
import com.vrudenko.kanban_board.dto.annotation.TaskTitle;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@EqualsAndHashCode
public class UpdateTaskRequestDTO implements BaseTask {
    @TaskTitle String title;

    @Description String description;
}
