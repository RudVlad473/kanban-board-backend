package com.vrudenko.kanban_board.constant;

/**
 * Be aware that you may need to concat these paths with 'context-path' from application.properties
 */
public final class ApiPaths {
    public static final String BOARDS = "/boards";
    public static final String BOARD_ID = "/{boardId}";

    public static final String COLUMNS = "/columns";
    public static final String COLUMN_ID = "/{columnId}";

    public static final String TASKS = "/tasks";
    public static final String TASK_ID = "/{taskId}";
    public static final String MOVE = "/move";
    public static final String REORDER = "/reorder";

    public static final String SUBTASKS = "/subtasks";
    public static final String SUBTASK_ID = "/{subtaskId}";

    public static final String ACTIVITY = "/activity";
    public static final String FULL = "/full";

    // Unused until plans 04-06 wire the routes that need them; landed here in wave 1 so the
    // migration/entity/constants foundation is a single plan and plans 04-06 can run in parallel
    // without contending over this file.
    public static final String USERS = "/users";
    public static final String ME = "/me";
    public static final String THEME = "/theme";

    public static final String SIGNIN = "/signin";
    public static final String SIGNUP = "/signup";
    public static final String LOGOUT = "/logout";

    /** Utilities */
    public static final String SWAGGER_UI = "/swagger-ui";

    // Spring Boot Actuator's default base path (management.server.port is not set, so it shares
    // this app's port/context-path). Declared as a constant here, matching SWAGGER_UI's precedent
    // of a framework-adjacent utility path living in this class rather than inline in
    // SecurityConfiguration -- unlike SWAGGER_DOCS_PATH, which is sourced from a genuinely
    // configurable property (springdoc.api-docs.path) and has no fixed default to name here.
    public static final String ACTUATOR_HEALTH = "/actuator/health";
}
