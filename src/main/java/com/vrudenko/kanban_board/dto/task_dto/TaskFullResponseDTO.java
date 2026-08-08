package com.vrudenko.kanban_board.dto.task_dto;

import com.vrudenko.kanban_board.base.entity.BaseId;
import com.vrudenko.kanban_board.base.entity.BaseTask;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * The task level of GAP-04's nested board read. Carries {@code version} and {@code position} --
 * fields the flat {@link TaskResponseDTO} also carries -- plus its own subtasks. The leaf level
 * reuses the existing {@link com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO} as-is: a
 * subtask has no children of its own, so a "full" variant would be a pure duplicate.
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
