package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.entity.BaseBoard;
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
@Table(name = "columns")
public class ColumnEntity extends BaseEntity implements BaseBoard {
  @Column(nullable = false)
  private String name;

  @ManyToOne
  @JoinColumn(name = "board_id")
  private BoardEntity board;

  @OneToMany(mappedBy = "column")
  private List<TaskEntity> task;
}
