package com.vrudenko.kanban_board.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "boards")
public class BoardEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private String boardId;

  @Column(nullable = false)
  private String name;

  @OneToMany(mappedBy = "board")
  private List<ColumnEntity> column;
}
