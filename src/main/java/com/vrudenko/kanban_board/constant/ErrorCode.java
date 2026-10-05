package com.vrudenko.kanban_board.constant;

/**
 * Machine-readable error codes {@link com.vrudenko.kanban_board.handler.GlobalExceptionHandler}
 * attaches to every {@code ProblemDetail} error response as the {@code code} property.
 *
 * <p>The enum member names and the two property-key constants are a published API contract consumed
 * by the frontend: renaming any is a breaking change and must be coordinated with every client.
 */
public enum ErrorCode {
    ENTITY_NOT_FOUND,
    ILLEGAL_ARGUMENT,
    MALFORMED_REQUEST_BODY,
    INTERNAL_ERROR,
    ACCESS_DENIED,
    CONSTRAINT_VIOLATION,
    OPTIMISTIC_LOCK_CONFLICT,
    DUPLICATE_RESOURCE,
    DATA_INTEGRITY_VIOLATION,
    BAD_CREDENTIALS,
    VALIDATION_FAILED,
    UNAUTHENTICATED;

    /** {@code ProblemDetail} extension property key carrying this enum's {@code name()}. */
    public static final String CODE_PROPERTY = "code";

    /**
     * {@code ProblemDetail} extension property key carrying the per-field validation error map
     * ({@code Map<String, String>}, field name to message) on the {@link #VALIDATION_FAILED}
     * response.
     */
    public static final String ERRORS_PROPERTY = "errors";
}
