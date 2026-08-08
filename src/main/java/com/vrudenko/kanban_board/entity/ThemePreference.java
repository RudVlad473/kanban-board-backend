package com.vrudenko.kanban_board.entity;

/**
 * Closed set of theme preferences a {@code users} row can persist (D-11). Only the two states the
 * mock-up shows (MU-Th1..MU-Th3) exist; no third state is modeled. Modeled as an enum rather than a
 * bare String per {@code docs/CODE_STYLE.md} rule 1: the compiler enforces the closed set instead
 * of an unconstrained free-form string column.
 */
public enum ThemePreference {
    LIGHT,
    DARK
}
