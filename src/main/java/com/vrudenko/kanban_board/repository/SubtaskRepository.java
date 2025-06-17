package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.SubtaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubtaskRepository extends JpaRepository<SubtaskEntity, String> {
    void deleteAllByTaskId(String taskId);

    List<SubtaskEntity> findAllByTaskId(String taskId);
}
