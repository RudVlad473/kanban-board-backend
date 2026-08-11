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
// @EqualsAndHashCode(callSuper = false) -- deliberately disabled (matches TaskEntity's identical
// precedent), not merely a version-field exclusion. GAP-04's TaskEntity.subtasks
// (Set<SubtaskEntity>)
// needs a hashCode/equals that safely and correctly identifies distinct rows during Hibernate's
// HashSet population. The previous field-based equals/hashCode (title, isCompleted, task) could
// NOT do that: two sibling subtasks under the same task with the same isCompleted value (the
// common case -- every subtask defaults to false) collide unless their titles happen to differ,
// which is not guaranteed. A real collision was observed directly: BoardFullReadTest's
// flat-vs-nested equivalence test lost a subtask this way before this fix. Falling back to
// Object's identity-based equals/hashCode is safe here (and matches TaskEntity, which already
// made this exact choice): Hibernate's session-level identity map guarantees the same Java
// reference is reused for the same row within one persistence context, so identity equality is
// correct for Set membership, not merely a workaround.
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
