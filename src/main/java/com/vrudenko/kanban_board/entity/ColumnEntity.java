package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.entity.BaseColumn;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@Table(name = "columns")
public class ColumnEntity extends BaseEntity implements BaseColumn {
    @Column(nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "board_id")
    private BoardEntity board;

    // Set, not List: GAP-04's BoardRepository.findByIdWithColumnsTasksAndSubtasks chains a
    // LEFT JOIN FETCH across board->column->task->subtasks in one query, and Hibernate's
    // MultipleBagFetchException fires the moment more than one List (bag-semantics) collection
    // is fetch-joined in a single query -- regardless of nesting depth, not only for sibling
    // collections off the same parent (a real, verified Hibernate 6 behavior, contrary to this
    // plan's original design-rationale assumption). BoardEntity.column stays the one List the
    // query is allowed; this field and TaskEntity.subtasks are Set instead. Safe: TaskEntity's
    // own equals/hashCode is commented out (identity-based), so Hibernate hashing a TaskEntity
    // during Set population never recurses back into this collection or ColumnEntity.
    @OneToMany(mappedBy = "column")
    private Set<TaskEntity> task;

    @Version
    @EqualsAndHashCode.Exclude
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private Integer position = 0;
}
