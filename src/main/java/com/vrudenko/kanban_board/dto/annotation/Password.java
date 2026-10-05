package com.vrudenko.kanban_board.dto.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.vrudenko.kanban_board.constant.ValidationConstants;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Require 8-64 characters (the cap prevents abuse) with at least one uppercase letter, one
 * lowercase letter, one digit and one special character.
 */
@Documented
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@NotBlank(message = "Password cannot be empty") @Size(
        min = ValidationConstants.MIN_PASSWORD_LENGTH,
        max = ValidationConstants.MAX_PASSWORD_LENGTH,
        message = ValidationConstants.PASSWORD_LENGTH_VALIDATION_MESSAGE)
@Pattern(
        regexp =
                "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).+$",
        message =
                "Password must contain at least one uppercase letter, one lowercase letter, one number, and one special character")
// No @Schema example is published, deliberately: a password-shaped literal in source is exactly
// what the gitleaks pre-commit scan looks for. This rationale stays out of the published
// `description`, which would disclose the repo's secret-scanning setup to every API consumer.
@Schema(
        description =
                "8-64 characters; must contain at least one uppercase letter, one lowercase"
                        + " letter, one digit, and one special character.")
public @interface Password {
    String message() default
            "Password must contain at least one uppercase letter, one lowercase letter, one number, and one special character";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
