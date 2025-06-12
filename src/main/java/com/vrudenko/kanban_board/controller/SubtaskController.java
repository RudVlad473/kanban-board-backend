package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        ApiPaths.BOARDS
                + ApiPaths.BOARD_ID
                + ApiPaths.COLUMNS
                + ApiPaths.COLUMN_ID
                + ApiPaths.TASKS
                + ApiPaths.TASK_ID
                + ApiPaths.SUBTASKS
                + ApiPaths.SUBTASK_ID)
@PreAuthorize("isAuthenticated()")
class SubtaskController {}
