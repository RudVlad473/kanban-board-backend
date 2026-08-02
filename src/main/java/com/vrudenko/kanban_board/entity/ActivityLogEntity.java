package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single insert-only row of the per-board activity feed. Rows are written once by {@code
 * ActivityLogConsumer} and never updated, so unlike {@link TaskEntity}/{@link ColumnEntity} there
 * is no {@code @Version} field — there is nothing to protect against a concurrent overwrite.
 *
 * <p>{@code boardId} and {@code userId} are plain columns, deliberately not {@code @ManyToOne}
 * relations to {@link BoardEntity}/{@link UserEntity}. A foreign key would make persistence fail
 * whenever the referenced board or user has already been deleted, turning a routine race into a
 * poison message — and the consumer that builds this row runs on a listener thread with no security
 * context, so it has no way to resolve those entities even if it wanted to.
 *
 * <p>{@code detail} holds raw structured identifiers only (a JSON object string), never a
 * pre-rendered human-readable sentence and never any user-authored text such as a task title, task
 * description, column name or board name (D-01). Human-readable rendering is a frontend concern,
 * done from data the frontend already has loaded.
 *
 * <p>{@code eventId} is a business dedupe key, not this row's identity — the row's own identity is
 * the ULID {@code id} inherited from {@link BaseEntity}, matching every other entity in this
 * codebase. The {@code unique = true} constraint here is the fast-path mirror of the database-level
 * unique constraint the hand-written Postgres DDL bridge script also carries; the DDL script, not
 * this annotation, is what production actually enforces (the real profile sets no {@code
 * ddl-auto}).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "activity_log")
public class ActivityLogEntity extends BaseEntity {
    @Column(nullable = false)
    private String boardId;

    @Column(nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivityAction action;

    @Column(nullable = false, length = ValidationConstants.MAX_ACTIVITY_DETAIL_LENGTH)
    private String detail;

    @Column(nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false)
    private Instant createdAt;
}
