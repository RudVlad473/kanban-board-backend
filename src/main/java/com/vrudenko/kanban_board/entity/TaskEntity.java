package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.entity.BaseTask;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Set;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
// @EqualsAndHashCode(callSuper = false)
@Table(name = "tasks")
public class TaskEntity extends BaseEntity implements BaseTask {
    @ManyToOne
    @JoinColumn(name = "column_id")
    private ColumnEntity column;

    // Set, not List: see ColumnEntity.task's Javadoc-style comment -- Hibernate's
    // MultipleBagFetchException fires when 2+ List (bag) collections are fetch-joined in one
    // query at any nesting depth, so at most one collection in the board->column->task->subtasks
    // chain (BoardEntity.column) can remain a List. Safe: SubtaskEntity's equals/hashCode
    // includes only title/isCompleted/task, and task's own equals/hashCode is identity-based
    // (commented out on TaskEntity), so hashing a SubtaskEntity during Set population never
    // recurses.
    @OneToMany(mappedBy = "task")
    private Set<SubtaskEntity> subtasks;

    @Column(nullable = false, length = ValidationConstants.MAX_TASK_TITLE_LENGTH)
    private String title;

    @Column(length = ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH)
    private String description;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private Integer position = 0;
}
