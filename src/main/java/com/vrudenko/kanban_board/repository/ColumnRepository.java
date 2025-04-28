package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.ColumnEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ColumnRepository extends JpaRepository<ColumnEntity, String> {
  List<ColumnEntity> findAllByBoardId(String boardId);
}
