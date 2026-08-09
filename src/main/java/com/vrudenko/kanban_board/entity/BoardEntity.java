package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.entity.BaseBoard;
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
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@Table(name = "boards")
public class BoardEntity extends BaseEntity implements BaseBoard {
    @Column(nullable = false)
    private String name;

    // Set, not List, excluded from equals/hashCode, and explicitly ordered -- see
    // BoardRepository's Javadoc for the full MultipleBagFetchException / row-multiplication
    // reasoning behind every collection in the GAP-04 fetch-join chain being a Set.
    //
    // The @EqualsAndHashCode.Exclude here is a defensive belt-and-suspenders choice, not currently
    // load-bearing: ColumnEntity now uses identity-based equals/hashCode (its own comment explains
    // why), so it no longer recurses back into this field the way its previous @Data-generated
    // hashCode would have. A mutable collection still has no business being part of an entity's
    // equals/hashCode regardless, so the exclusion stays.
    //
    // @OrderBy("id") gives this collection's iteration order a defined, deterministic ordering
    // (plain HashSet has none) -- see TaskEntity.subtasks's comment for why id-ascending is the
    // right choice here, not a hardcoded position sequence.
    @EqualsAndHashCode.Exclude
    @OneToMany(mappedBy = "board")
    @OrderBy("id")
    private Set<ColumnEntity> column;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private UserEntity user;

    // Excluded from equals/hashCode for the same reason `column` above is: this field's whole
    // purpose (D-13) is to change on every update, and this class -- unlike ColumnEntity/
    // TaskEntity, which dropped field-based equals/hashCode entirely -- still derives them from
    // its fields via @EqualsAndHashCode. A mutable field driving equals/hashCode is exactly the
    // hazard those two entities' comments describe: an object already stored in a HashSet/HashMap
    // keyed by its old hashCode becomes unreachable once the field it was hashed on changes.
    @EqualsAndHashCode.Exclude
    @Version
    @Column(nullable = false)
    private Long version;
}
