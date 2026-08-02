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
    @GetMapping
    public ResponseEntity<Page<ActivityLogResponseDTO>> findAllByBoardId(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String boardId,
            Pageable pageable) {
        return ResponseEntity.ok(activityLogService.findAllByBoardId(userId, boardId, pageable));
    }
}
