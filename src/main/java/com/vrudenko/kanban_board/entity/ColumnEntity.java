package com.vrudenko.kanban_board.entity;

import java.util.Set;

import com.vrudenko.kanban_board.base.entity.BaseColumn;
import com.vrudenko.kanban_board.constant.ValidationConstants;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// No @Data or @EqualsAndHashCode on purpose: see the comment on `task`.
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

    // Set, not List: the board->column->task->subtasks fetch-join chain needs it (see
    // BoardRepository's Javadoc for the MultipleBagFetchException reasoning).
    //
    // Decisions:
    // Identity-based equals/hashCode, not field-based. ColumnEntity is a Set ELEMENT of
    // BoardEntity.column, and two sibling columns can share every field the old version varied on:
    // `name` is the only field with real per-column entropy (`board` is identical for siblings,
    // `task` is an empty Set for a column with no tasks), so a `name` collision (plausible with a
    // small random-word pool in tests) would merge two distinct columns. Identity equality is safe:
    // Hibernate's session-level identity map reuses one Java reference per row within a
    // persistence context. TaskEntity and SubtaskEntity made the same choice for the same reason.
    @OneToMany(mappedBy = "column")
    @OrderBy("id")
    private Set<TaskEntity> task;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private Integer position = 0;

    @Column(length = ValidationConstants.COLUMN_COLOR_LENGTH)
    private String color;
}
