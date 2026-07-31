package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.SubtaskEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubtaskRepository extends JpaRepository<SubtaskEntity, String> {
    void deleteAllByTaskId(String taskId);

    // Explicit bulk JPQL delete (rather than the derived deleteAllByTaskIdIn, which Spring Data
    // JPA implements as fetch-then-remove-per-entity): a single DELETE statement, and it executes
    // immediately, so it's guaranteed to run before a subsequent bulk delete on `tasks` in the
    // same transaction — the derived form doesn't flush in time for that, causing an FK violation.
    @Modifying
    @Query("delete from SubtaskEntity s where s.task.id in :taskIds")
    void deleteAllByTaskIdIn(@Param("taskIds") Collection<String> taskIds);

    List<SubtaskEntity> findAllByTaskId(String taskId);
}
