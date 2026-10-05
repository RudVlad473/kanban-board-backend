package com.vrudenko.kanban_board.handler;

import java.util.HashMap;
import java.util.Map;

import com.vrudenko.kanban_board.constant.ErrorCode;
import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.exception.AppDuplicateResourceException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Try;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
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
    public ResponseEntity<ProblemDetail> handleEntityNotFound(EntityNotFoundException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.ENTITY_NOT_FOUND.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(AppEntityNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleAppEntityNotFound(AppEntityNotFoundException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.ENTITY_NOT_FOUND.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.ILLEGAL_ARGUMENT.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    // A request body that fails to deserialize (e.g. a theme outside ThemePreference's two
    // members) throws this before validation runs; without this arm it falls through to the
    // Exception.class catch-all as a 500, not the 400 a malformed request warrants.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.MALFORMED_REQUEST_BODY.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneralException(Exception ex) {
        var problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.INTERNAL_ERROR.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDeniedException(AccessDeniedException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.ACCESS_DENIED.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(AppAccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAppAccessDeniedException(
            AppAccessDeniedException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.ACCESS_DENIED.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    // Map the built-in MVC method-validation failure to a 400 CONSTRAINT_VIOLATION.
    //
    // Decisions:
    // Class-level @Validated (on every @RestController, see LayeringArchTest) makes Spring's
    // HandlerMethod.MethodValidationInitializer skip its own built-in method validation for that
    // controller and route @PathVariable/@RequestParam constraint failures through the AOP
    // MethodValidationInterceptor, which raises jakarta.validation.ConstraintViolationException
    // (handleConstraintViolation below), not this exception. Do not delete this arm as dead code
    // although every controller carries @Validated: it keeps a constrained handler method a clean
    // 400 if it is ever reached without class-level @Validated, which the ArchUnit rule prevents
    // but cannot make structurally impossible for a handler outside @RestController scope.
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidationException(
            HandlerMethodValidationException ex) {
        var listOfMessages =
                ex.getParameterValidationResults().stream()
                        .map(r -> r.getMethodParameter().getMethod());

        Pair<String, HttpStatusCode> stringHttpStatusPair =
                Try.of(
                                () ->
                                        Pair.of(
                                                objectMapper.writeValueAsString(listOfMessages),
                                                ex.getStatusCode()))
                        .getOrElseGet(
                                (Throwable cause) ->
                                        Pair.of(ex.getMessage(), HttpStatus.BAD_REQUEST));

        var problem =
                ProblemDetail.forStatusAndDetail(
                        stringHttpStatusPair.getSecond(), stringHttpStatusPair.getFirst());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.CONSTRAINT_VIOLATION.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    // Keep a path-variable constraint failure a clean 400 CONSTRAINT_VIOLATION.
    //
    // Decisions:
    // Measured empirically: a blank or malformed @PathVariable @NotBlank on an @Validated
    // controller raised this exception unhandled, fell through to the Exception.class catch-all
    // and surfaced as a 500 on trivially-craftable input. Every @RestController now carries
    // class-level @Validated (LayeringArchTest), so this arm covers that failure everywhere.
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.CONSTRAINT_VIOLATION.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(
            OptimisticLockingFailureException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.OPTIMISTIC_LOCK_CONFLICT.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    // The checked, expected duplicate-name path: a service-layer guard throws this before any
    // insert or update is attempted.
    @ExceptionHandler(AppDuplicateResourceException.class)
    public ResponseEntity<ProblemDetail> handleAppDuplicateResource(
            AppDuplicateResourceException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.DUPLICATE_RESOURCE.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    // Catch a uk_boards_user_id_name violation that slips past the service-level check-then-act
    // window.
    //
    // AppDuplicateResourceException extends this exception, so Spring resolves the more specific
    // arm above for the checked path; this broader arm exists only for the unchecked race and
    // must not be deleted as dead code.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.DATA_INTEGRITY_VIOLATION.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentialsException(BadCredentialsException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.BAD_CREDENTIALS.name());

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValidException(
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

        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.VALIDATION_FAILED.name());
        problem.setProperty(ErrorCode.ERRORS_PROPERTY, errors);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
