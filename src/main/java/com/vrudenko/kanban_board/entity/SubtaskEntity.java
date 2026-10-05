package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.entity.BaseSubtask;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
// @EqualsAndHashCode(callSuper = false) is deliberately absent: identity-based equals/hashCode.
//
// Decisions:
// TaskEntity.subtasks is a Hibernate-populated Set<SubtaskEntity>, which needs equals/hashCode
// that identify distinct rows. The old field-based version (title, isCompleted, task) could not:
// two sibling subtasks under one task with the same isCompleted (every subtask defaults to false)
// collide unless their titles differ. Observed directly: BoardFullReadTest's flat-vs-nested
// equivalence test lost a subtask this way. Identity equality is safe because Hibernate's
// session-level identity map reuses one Java reference per row within a persistence context, so
// it is correct for Set membership, not a workaround.
@Table(name = "subtasks")
public class SubtaskEntity extends BaseEntity implements BaseSubtask {
    @ManyToOne
    @JoinColumn(name = "task_id")
    private TaskEntity task;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean isCompleted = false;

    @Version
    @Column(nullable = false)
    private Long version;
}
