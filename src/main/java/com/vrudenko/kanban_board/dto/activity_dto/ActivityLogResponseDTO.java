package com.vrudenko.kanban_board.dto.activity_dto;

import java.time.Instant;

import com.vrudenko.kanban_board.entity.ActivityAction;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * A single row of a board's activity feed, as returned by {@code GET /boards/{boardId}/activity}
 * (D-10). Deliberately carries exactly five fields: the row's own ULID {@code id} and its {@code
 * boardId} are both omitted — the board is already identified by the URL path, and repeating it on
 * every item would be redundant. {@code detail} is raw identifier JSON (never user-authored text,
 * per D-01); rendering it into a human-readable sentence is a frontend concern, done from data the
 * frontend already has loaded.
 *
 * <p>{@code eventId}'s JSON type is a deliberate, documented breaking change (GAP-07): it was
 * previously a UUID string, and is now a {@link String} carrying either a legacy UUID string or a
 * new Base36 Snowflake-style id, indistinguishable at this type level by design — {@code eventId}
 * is a dedupe key compared for equality only, never parsed by any consumer. There is no frontend
 * consumer of this endpoint today, so the blast radius of this change is currently zero.
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
