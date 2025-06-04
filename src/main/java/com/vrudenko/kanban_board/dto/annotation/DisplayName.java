package com.vrudenko.kanban_board.dto.annotation;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Size(
        min = ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH,
        max = ValidationConstants.MAX_USER_DISPLAY_NAME_LENGTH,
        message = ValidationConstants.DISPLAY_NAME_LENGTH_VALIDATION_MESSAGE)
@Pattern(regexp = "^[a-zA-Z ]*$", message = "Display name may only contain letters & spaces") public @interface DisplayName {
    String message() default ValidationConstants.DISPLAY_NAME_LENGTH_VALIDATION_MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
