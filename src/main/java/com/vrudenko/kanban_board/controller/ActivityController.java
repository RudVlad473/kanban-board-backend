package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.activity_dto.ActivityLogResponseDTO;
import com.vrudenko.kanban_board.security.CurrentUserId;
import com.vrudenko.kanban_board.service.ActivityLogService;

import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.ACTIVITY)
@Validated
@PreAuthorize("isAuthenticated()")
public class ActivityController {
    @Autowired private ActivityLogService activityLogService;

    // Page size is already clamped by spring.data.web.pageable.max-page-size
    // (application.properties), so no per-endpoint size guard is needed here.
    //
    // This is the first paginated endpoint in the codebase, so the response shape used here is a
    // deliberate, tracked convention future paginated endpoints should copy: it returns the raw
    // Spring Data `Page<T>` (serialized as `PageImpl`) rather than wrapping it in
    // `org.springframework.data.web.PagedModel`. Spring Data documents the `PageImpl` shape as not
    // guaranteed to be stable across versions and logs a startup warning to that effect;
    // `PagedModel` would give a documented, versioned contract instead, at the cost of changing
    // every existing consumer's parsing (top-level `content`/`totalElements`/`totalPages` become
    // nested under `page`). That tradeoff has not been made yet -- if it ever is, it should be
    // applied consistently to every paginated endpoint at once, not silently drift endpoint by
    // endpoint.
    @GetMapping
    public ResponseEntity<Page<ActivityLogResponseDTO>> findAllByBoardId(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String boardId,
            Pageable pageable) {
        return ResponseEntity.ok(activityLogService.findAllByBoardId(userId, boardId, pageable));
    }
}
