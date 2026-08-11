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
// @EqualsAndHashCode(callSuper = false)
@Table(name = "tasks")
public class TaskEntity extends BaseEntity implements BaseTask {
    @ManyToOne
    @JoinColumn(name = "column_id")
    private ColumnEntity column;

    // Set, not List: see BoardRepository's Javadoc for the full MultipleBagFetchException /
    // row-multiplication reasoning behind every collection in the GAP-04 fetch-join chain being a
    // Set. Safe against a HashSet-population hashCode call: both SubtaskEntity (this Set's
    // element type) and TaskEntity itself now use Object's identity-based equals/hashCode (see
    // each entity's own comment), never recursing back through a field.
    //
    // @OrderBy("id") gives this collection's iteration order a defined, deterministic ordering
    // (plain HashSet has none) that matches SubtaskRepository.findAllByTaskId's effective
    // no-explicit-ORDER-BY (natural/insertion) order closely enough for GAP-04's nested-vs-flat
    // equivalence test to hold -- ULIDs are roughly creation-time-ordered, so id-ascending
    // approximates insertion order without hardcoding a position sequence that belongs to a
    // separate ordering feature.
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
