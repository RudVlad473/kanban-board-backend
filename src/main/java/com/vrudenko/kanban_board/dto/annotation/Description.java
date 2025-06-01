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
    min = ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
    max = ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH,
    message = ValidationConstants.TASK_DESCRIPTION_LENGTH_VALIDATION_MESSAGE)
public @interface Description {
    String message() default "Description cannot be empty";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
