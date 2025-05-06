package com.vrudenko.kanban_board.base.entity;

/**
 * Interfaces are used for base entities instead of classes because you can't override fields in
 * Java therefore you can't override field to apply Hibernate's annotations to them so methods are
 * used instead
 */
public interface BaseBoard {
  String getName();
}
