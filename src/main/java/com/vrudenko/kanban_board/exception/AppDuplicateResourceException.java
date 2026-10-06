package com.vrudenko.kanban_board.exception;

import org.springframework.dao.DataIntegrityViolationException;

public class AppDuplicateResourceException extends DataIntegrityViolationException {
    public AppDuplicateResourceException(String entityName) {
        super(entityName + " with that name already exists");
    }

    private AppDuplicateResourceException(String message, boolean rawMessage) {
        super(message);
        assert rawMessage : "rawMessage discriminates this overload from the entity-name one above";
    }

    /**
     * Build the exception from an already-complete detail message, bypassing the entityName
     * template.
     *
     * That template ("entityName with that name already exists") reads wrong for a
     * duplicate signup email. A static factory, not a second String constructor: Java
     * cannot overload on parameter type alone here.
     */
    public static AppDuplicateResourceException withMessage(String message) {
        return new AppDuplicateResourceException(message, true);
    }
}
