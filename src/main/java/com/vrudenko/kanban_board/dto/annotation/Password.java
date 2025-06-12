package com.vrudenko.kanban_board.dto.annotation;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Minimum length: 8 characters; Maximum length: 64 characters (to prevent abuse); At least 1
 * uppercase letter; At least 1 lowercase letter; At least 1 digit; At least 1 special character
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
public @interface Password {
    String message() default
            "Password must contain at least one uppercase letter, one lowercase letter, one number, and one special character";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
