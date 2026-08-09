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
     * Builds this exception from an already-complete detail message, bypassing the "{@code
     * entityName} with that name already exists" template {@link
     * #AppDuplicateResourceException(String)} renders -- that template reads wrong for cases like a
     * duplicate signup email ("user@example.com with that name already exists" is not a sentence).
     * A static factory rather than a second same-erasure {@code String} constructor, since Java
     * cannot overload on parameter type alone here.
     */
    public static AppDuplicateResourceException withMessage(String message) {
        return new AppDuplicateResourceException(message, true);
    }
}
