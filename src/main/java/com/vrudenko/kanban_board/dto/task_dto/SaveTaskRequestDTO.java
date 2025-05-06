package com.vrudenko.kanban_board.dto.task_dto;

import com.vrudenko.kanban_board.base.entity.BaseTask;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.annotation.Description;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@EqualsAndHashCode
public class SaveTaskRequestDTO implements BaseTask {
  @NotBlank(message = "Task title cannot be empty")
  @Size(
      min = ValidationConstants.MIN_TASK_TITLE_LENGTH,
      max = ValidationConstants.MAX_TASK_TITLE_LENGTH,
      message = ValidationConstants.TASK_TITLE_LENGTH_VALIDATION_MESSAGE)
  String title;

  @Description String description;
}
