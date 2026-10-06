package com.vrudenko.kanban_board.dto.annotation;

import java.lang.annotation.*;

import com.vrudenko.kanban_board.constant.ValidationConstants;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

/**
 * Validate an optional #RRGGBB hex color string, case-insensitive on input and persisted
 * verbatim.
 *
 * No case normalization is applied anywhere on the path, so #AbCdEf round-trips as
 * #AbCdEf.
 *
 * Decisions:
 *
 * OptionalNotBlank is deliberately NOT stacked alongside this annotation:
 * ValidationConstants.COLUMN_COLOR_PATTERN already rejects a blank or whitespace-only value (no
 * run of six hex digits can be all whitespace), so stacking would produce two violations for the
 * same blank input, breaking the exactly-one-violation-per-invalid-input convention
 * docs/CODE_STYLE.md rule 4 depends on.
 */
@Documented
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Pattern(
        regexp = ValidationConstants.COLUMN_COLOR_PATTERN,
        message = ValidationConstants.COLUMN_COLOR_VALIDATION_MESSAGE)
@Schema(example = "#1AB2C3")
public @interface ColumnColor {
    String message() default "Column color must be a #RRGGBB hex string";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
