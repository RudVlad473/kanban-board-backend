package com.vrudenko.kanban_board.exception;

import jakarta.persistence.EntityNotFoundException;

public class AppEntityNotFoundException extends EntityNotFoundException {
    public AppEntityNotFoundException(String entityName) {
        super(entityName + " was not found");
    }
}
