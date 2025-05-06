package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.entity.BaseTask;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@Table(name = "tasks")
public class TaskEntity extends BaseEntity implements BaseTask {
  @ManyToOne
  @JoinColumn(name = "column_id")
  private ColumnEntity column;

  @OneToMany(mappedBy = "task")
  private List<SubtaskEntity> subtasks;

  @Column(nullable = false, length = ValidationConstants.MAX_TASK_TITLE_LENGTH)
  private String title;

  @Column(length = ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH)
  private String description;
}
