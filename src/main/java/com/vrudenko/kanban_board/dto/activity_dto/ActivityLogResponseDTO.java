package com.vrudenko.kanban_board.dto.activity_dto;

import java.time.Instant;

import com.vrudenko.kanban_board.entity.ActivityAction;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * A single row of a board's activity feed, as returned by {@code GET /boards/{boardId}/activity}.
 *
 * <p>It carries exactly five fields: the row's own {@code id} and {@code boardId} are omitted,
 * since the URL path already identifies the board. {@code detail} is raw identifier JSON, never
 * user-authored text; rendering it into a human-readable sentence is a frontend concern, done from
 * data the frontend already has loaded.
 *
 * <p>Decisions:
 *
 * <p>{@code eventId} is a deliberate, documented breaking change: it was a UUID string and is now a
 * {@link String} carrying either a legacy UUID string or a Base36 Snowflake-style id, which are
 * indistinguishable at this type by design, because {@code eventId} is a dedupe key compared for
 * equality only and never parsed. There is no frontend consumer of this endpoint, so the blast
 * radius is currently zero.
 */
@Getter
@Setter
@Builder
@EqualsAndHashCode
public class ActivityLogResponseDTO {
    private String eventId;
    private ActivityAction action;
    private String detail;
    private String userId;
    private Instant createdAt;
}
