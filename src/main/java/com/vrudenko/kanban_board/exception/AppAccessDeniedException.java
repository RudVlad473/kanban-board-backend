package com.vrudenko.kanban_board.exception;

import org.springframework.security.access.AccessDeniedException;

public class AppAccessDeniedException extends AccessDeniedException {
  public AppAccessDeniedException(String entityName) {
    super("You do not have access to that board" + entityName.toLowerCase());
  }
}
