package com.vrudenko.kanban_board.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import io.vavr.control.Try;
import jakarta.persistence.EntityNotFoundException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<String> handleAppEntityNotFound(AppEntityNotFoundException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
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
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.LOCKED);
    }

    // handles errors from authentication
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<String> handleBadCredentialsException(
            BadCredentialsException ex) {
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
