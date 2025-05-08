package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.SubtaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubtaskRepository extends JpaRepository<SubtaskEntity, String> {
    void deleteAllByTaskId(String taskId);
}
