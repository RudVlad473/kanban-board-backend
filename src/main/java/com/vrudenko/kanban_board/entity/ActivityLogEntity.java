package com.vrudenko.kanban_board.entity;

import java.time.Instant;

import com.vrudenko.kanban_board.constant.ValidationConstants;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single insert-only row of the per-board activity feed, written once by {@code
 * ActivityLogConsumer}. It has no {@code @Version} field because it is never updated.
 *
 * <p>Decisions:
 *
 * <p>{@code boardId} and {@code userId} are plain columns, deliberately not {@code @ManyToOne}
 * relations to {@link BoardEntity}/{@link UserEntity}. A foreign key would fail persistence
 * whenever the referenced board or user is already deleted, turning a routine race into a poison
 * message, and the consumer that builds this row runs on a listener thread with no security
 * context, so it cannot resolve those entities anyway.
 *
 * <p>{@code detail} holds raw structured identifiers only (a JSON object string): never a
 * pre-rendered sentence and never user-authored text such as a task title or description, column
 * name or board name. Human-readable rendering is a frontend concern, done from data the frontend
 * already has loaded.
 *
 * <p>{@code eventId} is a business dedupe key, not the row's identity, which is the base36 {@code
 * id} inherited from {@link BaseEntity}. {@code unique = true} here mirrors the database constraint
 * {@code uk_activity_log_event_id} carried by {@code
 * V6__change_activity_log_event_id_to_varchar.sql}; the migration, not this annotation, is what
 * production enforces (the real profile sets no {@code ddl-auto}). {@code eventId} holds a Base36
 * Snowflake-style id from {@code EventIdGenerator}, not a random {@link java.util.UUID}; rows
 * written before that change keep their UUID string form, so the column mixes both shapes, compared
 * for equality only, never parsed.
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
    private String eventId;

    @Column(nullable = false)
    private Instant createdAt;
}
