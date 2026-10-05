package com.vrudenko.kanban_board.base.entity;

/**
 * Base entities are interfaces, not classes: Java cannot override fields, so Hibernate annotations
 * could not be applied to inherited ones, and methods can.
 */
public interface BaseBoard {
    String getName();
}
