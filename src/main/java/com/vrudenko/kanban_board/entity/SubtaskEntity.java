package com.vrudenko.kanban_board.entity;

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
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "subtasks")
public class SubtaskEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private String subtaskId;

  @ManyToOne
  @JoinColumn(name = "task_id")
  private TaskEntity task;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false)
  private String isCompleted;
}
