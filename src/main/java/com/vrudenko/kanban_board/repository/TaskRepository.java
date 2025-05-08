package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {
  List<TaskEntity> findAllByColumnId(String columnId);

  long countByColumnId(String columnId);
}
