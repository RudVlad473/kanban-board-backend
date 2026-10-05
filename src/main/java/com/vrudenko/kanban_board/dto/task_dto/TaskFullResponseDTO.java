package com.vrudenko.kanban_board.dto.task_dto;

import java.util.List;

import com.vrudenko.kanban_board.base.entity.BaseId;
import com.vrudenko.kanban_board.base.entity.BaseTask;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * The task level of the nested board read: {@code version} and {@code position}, as in the flat
 * {@link TaskResponseDTO}, plus its subtasks.
 *
 * <p>The leaf level reuses {@link com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO}: a
 * subtask has no children, so a "full" variant would be a pure duplicate.
 */
@Getter
@Setter
@EqualsAndHashCode
public class TaskFullResponseDTO implements BaseId, BaseTask {
    private String id;
    private String title;
    private String description;
    private Long version;
    private Integer position;
    private List<SubtaskResponseDTO> subtasks;
}
