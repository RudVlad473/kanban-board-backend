package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.entity.BaseSubtask;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@Table(name = "subtasks")
public class SubtaskEntity extends BaseEntity implements BaseSubtask {
  @ManyToOne
  @JoinColumn(name = "task_id")
  private TaskEntity task;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false)
  private Boolean isCompleted;
}
