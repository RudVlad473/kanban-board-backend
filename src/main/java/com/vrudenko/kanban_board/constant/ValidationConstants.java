package com.vrudenko.kanban_board.constant;

public final class ValidationConstants {
  public static final int MIN_BOARD_NAME_LENGTH = 1;
  public static final int MAX_BOARD_NAME_LENGTH = 64;
  public static final String NAME_LENGTH_VALIDATION_MESSAGE =
      "Board name cannot be less than "
          + ValidationConstants.MIN_BOARD_NAME_LENGTH
          + " character and more than "
          + ValidationConstants.MAX_BOARD_NAME_LENGTH
          + " characters";

  public static final int MIN_USER_DISPLAY_NAME_LENGTH = 3;
  public static final int MAX_USER_DISPLAY_NAME_LENGTH = 32;
  public static final String DISPLAY_NAME_LENGTH_VALIDATION_MESSAGE =
      "Display name cannot be less than "
          + ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH
          + " character and more than "
          + ValidationConstants.MAX_USER_DISPLAY_NAME_LENGTH
          + " characters";

  public static final int MIN_PASSWORD_LENGTH = 8;
  public static final int MAX_PASSWORD_LENGTH = 64;
  public static final String PASSWORD_LENGTH_VALIDATION_MESSAGE =
      "Password cannot be less than "
          + ValidationConstants.MIN_PASSWORD_LENGTH
          + " character and more than "
          + ValidationConstants.MAX_PASSWORD_LENGTH
          + " characters";

  public static final int MIN_COLUMN_NAME_LENGTH = 3;
  public static final int MAX_COLUMN_NAME_LENGTH = 32;
  public static final String COLUMN_NAME_LENGTH_VALIDATION_MESSAGE =
      "Column name cannot be less than "
          + ValidationConstants.MIN_COLUMN_NAME_LENGTH
          + " character and more than "
          + ValidationConstants.MAX_COLUMN_NAME_LENGTH
          + " characters";

  public static final int MIN_TASK_TITLE_LENGTH = 3;
  public static final int MAX_TASK_TITLE_LENGTH = 64;
  public static final String TASK_TITLE_LENGTH_VALIDATION_MESSAGE =
      "Task title cannot be less than "
          + ValidationConstants.MIN_TASK_TITLE_LENGTH
          + " character and more than "
          + ValidationConstants.MAX_TASK_TITLE_LENGTH
          + " characters";

  public static final int MIN_TASK_DESCRIPTION_LENGTH = 1;
  public static final int MAX_TASK_DESCRIPTION_LENGTH = 512;
  public static final String TASK_DESCRIPTION_LENGTH_VALIDATION_MESSAGE =
      "Task description cannot be less than "
          + ValidationConstants.MIN_TASK_TITLE_LENGTH
          + " character and more than "
          + ValidationConstants.MAX_TASK_TITLE_LENGTH
          + " characters";
}
