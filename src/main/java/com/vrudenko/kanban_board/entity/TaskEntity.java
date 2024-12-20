package com.vrudenko.kanban_board.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tasks")
public class TaskEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private String taskId;

  @ManyToOne
  @JoinColumn(name = "column_id")
  private ColumnEntity column;

  @OneToMany(mappedBy = "task")
  private List<SubtaskEntity> subtasks;

  @Column(nullable = false)
  private String title;

  @Column(length = 512)
  private String description;
}
