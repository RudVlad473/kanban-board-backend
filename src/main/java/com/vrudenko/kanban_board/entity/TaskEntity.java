package com.vrudenko.kanban_board.entity;

import java.util.Set;

import com.vrudenko.kanban_board.base.entity.BaseTask;
import com.vrudenko.kanban_board.constant.ValidationConstants;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
// No @EqualsAndHashCode on purpose: identity equality, see the comment on `subtasks`.
@Table(name = "tasks")
public class TaskEntity extends BaseEntity implements BaseTask {
    @ManyToOne
    @JoinColumn(name = "column_id")
    private ColumnEntity column;

    // Set, not List: the board->column->task->subtasks fetch-join chain needs it (see
    // BoardRepository's Javadoc for the MultipleBagFetchException reasoning).
    //
    // Identity-based equals/hashCode keeps HashSet population safe: SubtaskEntity and TaskEntity
    // both use Object's, so a hash never recurses through a field.
    //
    // @OrderBy("id") gives the collection a deterministic order (a plain HashSet has none).
    // Generated ids are roughly creation-time-ordered, so id-ascending approximates insertion
    // order closely enough for the nested-vs-flat read equivalence test, without hardcoding a
    // position sequence that belongs to a separate ordering feature.
    @OneToMany(mappedBy = "task")
    @OrderBy("id")
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
