package com.vrudenko.kanban_board.entity;

/**
 * Closed set of theme preferences a {@code users} row can persist. Only the two states the mock-up
 * shows exist; no third state is modeled.
 *
 * <p>An enum rather than a bare String per {@code docs/CODE_STYLE.md} rule 1: the compiler enforces
 * the closed set instead of an unconstrained free-form string column.
 */
public enum ThemePreference {
    LIGHT,
    DARK
}
