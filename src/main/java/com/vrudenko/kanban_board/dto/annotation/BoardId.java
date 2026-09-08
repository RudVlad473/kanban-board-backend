package com.vrudenko.kanban_board.dto.annotation;

import java.lang.annotation.*;

import com.vrudenko.kanban_board.constant.ValidationConstants;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

/**
 * Validates an optional caller-supplied board id against the exact charset and length {@link
 * com.vrudenko.kanban_board.config.RandFlakeGenerator} can itself emit: lowercase base36 digits
 * only, at most {@link ValidationConstants#MAX_BOARD_ID_LENGTH} characters.
 *
 * <p>A {@code null} value is permitted and means the server generates the id -- that behaviour
 * comes from Jakarta's {@link Pattern} skipping {@code null} by default, not from anything declared
 * here, and is otherwise invisible at the field.
 *
 * <p>{@link OptionalNotBlank} is deliberately NOT stacked alongside this annotation, for the same
 * reason documented on {@link ColumnColor}: {@link ValidationConstants#BOARD_ID_PATTERN}'s closed
 * charset already rejects a blank or whitespace-only value by construction, so stacking would
 * produce two violations for one bad input instead of one, breaking the
 * one-violation-per-invalid-input convention {@code docs/CODE_STYLE.md} rule 4 depends on.
 */
@Documented
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Pattern(
        regexp = ValidationConstants.BOARD_ID_PATTERN,
        message = ValidationConstants.BOARD_ID_VALIDATION_MESSAGE)
@Schema(example = "8qfkj52yzi0w")
public @interface BoardId {
    String message() default
            "Board id must be a lowercase alphanumeric string matching the generator's format";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
