package com.vrudenko.kanban_board.dto.annotation;

import java.lang.annotation.*;

import com.vrudenko.kanban_board.constant.ValidationConstants;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

/**
 * Validates an optional {@code #RRGGBB} hex color string, case-insensitive on input.
 *
 * <p>{@link OptionalNotBlank} is deliberately NOT stacked alongside this annotation (D-4, quick
 * task 260904-obv): {@link ValidationConstants#COLUMN_COLOR_PATTERN} already rejects a blank or
 * whitespace-only value by construction (no run of six hex digits can be all whitespace), so
 * stacking {@code @OptionalNotBlank} would produce two violations for the same blank input instead
 * of one, breaking the exactly-one-violation-per-invalid-input convention {@code
 * docs/CODE_STYLE.md} rule 4 depends on.
 *
 * <p>A value that matches the pattern is persisted verbatim -- no case normalization is applied
 * anywhere on the path, so {@code #AbCdEf} round-trips as {@code #AbCdEf}.
 */
@Documented
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Pattern(
        regexp = ValidationConstants.COLUMN_COLOR_PATTERN,
        message = ValidationConstants.COLUMN_COLOR_VALIDATION_MESSAGE)
public @interface ColumnColor {
    String message() default "Column color must be a #RRGGBB hex string";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
