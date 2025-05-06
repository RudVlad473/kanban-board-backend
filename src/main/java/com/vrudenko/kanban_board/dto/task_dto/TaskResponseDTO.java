package com.vrudenko.kanban_board.dto.task_dto;

import com.vrudenko.kanban_board.base.entity.BaseId;
import com.vrudenko.kanban_board.base.entity.BaseTask;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@EqualsAndHashCode
public class TaskResponseDTO implements BaseId, BaseTask {
  private String id;
  private String title;
  private String description;
}
