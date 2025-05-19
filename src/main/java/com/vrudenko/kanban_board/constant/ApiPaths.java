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

  public static final String SUB_TASKS = "/subtasks";

  public static final String SIGNIN = "/signin";
  public static final String SIGNUP = "/signup";
  public static final String LOGOUT = "/logout";
}
