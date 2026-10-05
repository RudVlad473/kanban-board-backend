package com.vrudenko.kanban_board.repository;

import java.util.Collection;
import java.util.List;

import com.vrudenko.kanban_board.entity.SubtaskEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubtaskRepository extends JpaRepository<SubtaskEntity, String> {
    void deleteAllByTaskId(String taskId);

    // Explicit bulk JPQL delete, not the derived deleteAllByTaskIdIn.
    //
    // Spring Data JPA implements the derived form as fetch-then-remove-per-entity. A single DELETE
    // executes immediately, so it runs before a later bulk delete on `tasks` in the same
    // transaction; the derived form does not flush in time and causes an FK violation.
    @Modifying
    @Query("delete from SubtaskEntity s where s.task.id in :taskIds")
    void deleteAllByTaskIdIn(@Param("taskIds") Collection<String> taskIds);

    List<SubtaskEntity> findAllByTaskId(String taskId);
}
