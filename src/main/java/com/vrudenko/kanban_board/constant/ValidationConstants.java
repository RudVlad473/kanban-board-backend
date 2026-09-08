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

    public static final int COLUMN_COLOR_LENGTH = 7;
    public static final String COLUMN_COLOR_PATTERN = "^#[0-9a-fA-F]{6}$";
    public static final String COLUMN_COLOR_VALIDATION_MESSAGE =
            "Column color must be a #RRGGBB hex string";

    // RandFlakeGenerator packs a Snowflake-shaped positive long (1 unused sign bit + 41 timestamp
    // bits + 22 sequence bits) and renders it via Long.toString(payload, 36) -- lowercase base36
    // digits only, always positive, so a leading '-' and any uppercase letter are values this
    // application never issues. MAX_BOARD_ID_LENGTH is the generator's real ceiling
    // (Long.toString(Long.MAX_VALUE, 36).length()), pinned by BoardIdTest so this constant and the
    // generator cannot drift apart silently.
    public static final int MAX_BOARD_ID_LENGTH = 13;
    public static final String BOARD_ID_PATTERN = "^[0-9a-z]{1," + MAX_BOARD_ID_LENGTH + "}$";
    public static final String BOARD_ID_VALIDATION_MESSAGE =
            "Board id must be a lowercase alphanumeric string of at most "
                    + ValidationConstants.MAX_BOARD_ID_LENGTH
                    + " characters";

    public static final int MIN_TASK_TITLE_LENGTH = 3;
    public static final int MAX_TASK_TITLE_LENGTH = 32;
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

    public static final int MIN_SUBTASK_TITLE_LENGTH = 3;
    public static final int MAX_SUBTASK_TITLE_LENGTH = 32;
    public static final String SUBTASK_TITLE_LENGTH_VALIDATION_MESSAGE =
            "Subtask title cannot be less than "
                    + ValidationConstants.MIN_SUBTASK_TITLE_LENGTH
                    + " character and more than "
                    + ValidationConstants.MAX_SUBTASK_TITLE_LENGTH
                    + " characters";

    // The widest event (TaskMovedEvent: three ULIDs plus keys) serialises to roughly 130
    // characters; 2000 is deliberate headroom for future event shapes rather than a tight fit.
    public static final int MAX_ACTIVITY_DETAIL_LENGTH = 2000;
}
