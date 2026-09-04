package com.vrudenko.kanban_board.dto.column_dto;

import java.util.List;

import com.vrudenko.kanban_board.base.entity.BaseColumn;
import com.vrudenko.kanban_board.base.entity.BaseId;
import com.vrudenko.kanban_board.dto.task_dto.TaskFullResponseDTO;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * The column level of GAP-04's nested board read. Carries {@code version} and {@code position} --
 * fields the flat {@link ColumnResponseDTO} also carries -- plus its own tasks, so the nested
 * response is never less informative than the flat fan-out it replaces.
 */
@Getter
@Setter
@EqualsAndHashCode
public class ColumnFullResponseDTO implements BaseId, BaseColumn {
    private String id;
    private String name;
    private Long version;
    private Integer position;
    private String color;
    private List<TaskFullResponseDTO> tasks;
}
