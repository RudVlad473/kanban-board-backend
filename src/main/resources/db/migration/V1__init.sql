-- V1__init.sql
-- Reconstructs the schema as it existed before Epic 2 (optimistic locking),
-- v1.1 Phase 3 (activity_log), and quick task 260803-m3i (password_hash NOT NULL).
-- Those three deltas are intentionally NOT included here -- see V2/V3/V4.

CREATE TABLE users (
    id            varchar(255) NOT NULL PRIMARY KEY,
    email         varchar(255) NOT NULL,
    display_name  varchar(255),
    password_hash varchar(255),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE boards (
    id      varchar(255) NOT NULL PRIMARY KEY,
    name    varchar(255) NOT NULL,
    user_id varchar(255),
    CONSTRAINT fk_boards_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE columns (
    id       varchar(255) NOT NULL PRIMARY KEY,
    name     varchar(255) NOT NULL,
    board_id varchar(255),
    CONSTRAINT fk_columns_board FOREIGN KEY (board_id) REFERENCES boards (id)
);

CREATE TABLE tasks (
    id          varchar(255) NOT NULL PRIMARY KEY,
    column_id   varchar(255),
    title       varchar(32) NOT NULL,
    description varchar(512),
    CONSTRAINT fk_tasks_column FOREIGN KEY (column_id) REFERENCES columns (id)
);

CREATE TABLE subtasks (
    id            varchar(255) NOT NULL PRIMARY KEY,
    task_id       varchar(255),
    title         varchar(255) NOT NULL,
    is_completed  boolean NOT NULL DEFAULT false,
    CONSTRAINT fk_subtasks_task FOREIGN KEY (task_id) REFERENCES tasks (id)
);
