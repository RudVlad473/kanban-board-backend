package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.TaskEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {
    List<TaskEntity> findAllByColumnId(String columnId);

    long countByColumnId(String columnId);
}
