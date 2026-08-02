package com.vrudenko.kanban_board.dto.activity_dto;

import com.vrudenko.kanban_board.entity.ActivityAction;
import java.time.Instant;
import java.util.UUID;
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
 */
@Getter
@Setter
@Builder
@EqualsAndHashCode
public class ActivityLogResponseDTO {
    private UUID eventId;
    private ActivityAction action;
    private String detail;
    private String userId;
    private Instant createdAt;
}
