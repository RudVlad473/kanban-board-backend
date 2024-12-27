package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.BaseBoard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "boards")
public class BoardEntity extends BaseEntity implements BaseBoard {
  @Column(nullable = false)
  private String name;

  @OneToMany(mappedBy = "board")
  private List<ColumnEntity> column;
}
