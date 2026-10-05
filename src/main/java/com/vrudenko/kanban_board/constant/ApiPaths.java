package com.vrudenko.kanban_board.constant;

/**
 * Context-path-relative route constants: prepend {@code server.servlet.context-path} from
 * application.properties for the externally resolvable URL.
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

    public static final String USERS = "/users";
    public static final String ME = "/me";
    public static final String THEME = "/theme";

    public static final String SIGNIN = "/signin";
    public static final String SIGNUP = "/signup";
    public static final String LOGOUT = "/logout";

    public static final String SWAGGER_UI = "/swagger-ui";

    // Actuator's default base path; management.server.port is unset, so it shares this app's port
    // and context-path. Unlike SWAGGER_DOCS_PATH, which comes from springdoc.api-docs.path and has
    // no fixed default to name here.
    public static final String ACTUATOR_HEALTH = "/actuator/health";

    // The nonprod-only data-reset endpoint (@Profile("nonprod"), ResetController). The external
    // URL is /api/admin/reset because server.servlet.context-path is /api.
    public static final String RESET = "/admin/reset";
}
