package com.vrudenko.kanban_board.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.constant.ErrorCode;
import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.exception.AppDuplicateResourceException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import io.vavr.control.Try;
import jakarta.persistence.EntityNotFoundException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@ControllerAdvice
public class GlobalExceptionHandler {
    @Autowired private ObjectMapper objectMapper;

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleEntityNotFound(EntityNotFoundException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(AppEntityNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleAppEntityNotFound(AppEntityNotFoundException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.ENTITY_NOT_FOUND.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    // GAP-05: a request body that fails to deserialize -- e.g. a theme value outside
    // ThemePreference's two members -- throws this before validation ever runs. Without this arm
    // it falls through to the Exception.class catch-all below and surfaces as a 500 instead of the
    // 400 a malformed request warrants.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGeneralException(Exception ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> handleAccessDeniedException(AccessDeniedException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(AppAccessDeniedException.class)
    public ResponseEntity<String> handleAppAccessDeniedException(AppAccessDeniedException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<String> handleMethodValidationException(
            HandlerMethodValidationException ex) {
        var listOfMessages =
                ex.getParameterValidationResults().stream()
                        .map(r -> r.getMethodParameter().getMethod());

        // god why
        Pair<String, HttpStatusCode> stringHttpStatusPair =
                Try.of(
                                () ->
                                        Pair.of(
                                                objectMapper.writeValueAsString(listOfMessages),
                                                ex.getStatusCode()))
                        .getOrElseGet(
                                (Throwable cause) ->
                                        Pair.of(ex.getMessage(), HttpStatus.BAD_REQUEST));
        return new ResponseEntity<>(
                stringHttpStatusPair.getFirst(), stringHttpStatusPair.getSecond());
    }

    // handles errors from db
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<String> handleOptimisticLockingFailure(
            OptimisticLockingFailureException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.CONFLICT);
    }

    // the checked, expected duplicate-name path (D-09) -- a service-layer existsByUserIdAndName
    // guard throws this before any insert/update is attempted
    @ExceptionHandler(AppDuplicateResourceException.class)
    public ResponseEntity<String> handleAppDuplicateResource(AppDuplicateResourceException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.CONFLICT);
    }

    // Catches a uk_boards_user_id_name violation that slips past the service-level check-then-act
    // window -- the race uk_boards_user_id_name (plan 01's V5) backstops.
    // AppDuplicateResourceException
    // extends this exception, so Spring resolves the more specific arm above first for the checked
    // path; this broader arm exists only for the unchecked race and must not be deleted as dead
    // code.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.CONFLICT);
    }

    // handles errors from authentication
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<String> handleBadCredentialsException(BadCredentialsException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult()
                .getAllErrors()
                .forEach(
                        (error) -> {
                            String fieldName = ((FieldError) error).getField();
                            String errorMessage = error.getDefaultMessage();
                            errors.put(fieldName, errorMessage);
                        });
        return ResponseEntity.badRequest().body(errors);
    }
}
