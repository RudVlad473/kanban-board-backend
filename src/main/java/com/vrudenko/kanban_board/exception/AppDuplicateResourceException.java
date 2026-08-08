package com.vrudenko.kanban_board.exception;

import org.springframework.dao.DataIntegrityViolationException;

public class AppDuplicateResourceException extends DataIntegrityViolationException {
    public AppDuplicateResourceException(String entityName) {
        super(entityName + " with that name already exists");
    }
}
