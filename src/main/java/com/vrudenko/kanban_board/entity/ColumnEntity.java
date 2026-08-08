package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.entity.BaseColumn;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// @Data (bundled equals/hashCode) and @EqualsAndHashCode(callSuper = false) deliberately dropped
// in favor of plain @Getter/@Setter -- see the field-level comment on `task` below for why.
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "columns")
public class ColumnEntity extends BaseEntity implements BaseColumn {
    @Column(nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "board_id")
    private BoardEntity board;

    // Set, not List: GAP-04's BoardRepository.findByIdWithColumnsTasksAndSubtasks chains a
    // LEFT JOIN FETCH across board->column->task->subtasks in one query. Every collection in that
    // chain (including BoardEntity.column) is a Set -- see BoardRepository's Javadoc for the full
    // MultipleBagFetchException / row-multiplication reasoning.
    //
    // Field-based equals/hashCode (this class's previous @Data/@EqualsAndHashCode) is unsafe for
    // a Hibernate-populated Set: ColumnEntity became a Set ELEMENT the moment BoardEntity.column
    // switched to Set<ColumnEntity>, and two sibling columns under the same board can share every
    // field this class actually varies on (name is the only field with real per-column entropy;
    // `board` is identical for all siblings by definition, `task` is an empty Set for every column
    // with no tasks yet, and `position` currently defaults to 0 for every column, since no
    // renumbering logic exists yet) -- a `name` collision (plausible with a small random-word
    // pool in tests) would incorrectly merge two distinct columns. Falling back to Object's
    // identity-based equals/hashCode (this class's now-plain @Getter/@Setter, no
    // @EqualsAndHashCode) is safe: Hibernate's session-level identity map guarantees the same Java
    // reference is reused for the same row within one persistence context, matching the fix
    // already applied to TaskEntity and SubtaskEntity for the identical reason.
    @OneToMany(mappedBy = "column")
    @OrderBy("id")
    private Set<TaskEntity> task;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private Integer position = 0;
}
