package com.vrudenko.kanban_board.dto.column_dto;

import com.vrudenko.kanban_board.base.BaseColumn;
import com.vrudenko.kanban_board.base.BaseId;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@EqualsAndHashCode
public class ColumnResponseDTO implements BaseId, BaseColumn {
  private String id;
  private String name;
}
