package com.vrudenko.kanban_board.dto.annotation;

import java.lang.annotation.*;

import com.vrudenko.kanban_board.constant.ValidationConstants;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Documented
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Size(
        min = ValidationConstants.MIN_BOARD_NAME_LENGTH,
        max = ValidationConstants.MAX_BOARD_NAME_LENGTH,
        message = ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE)
@Pattern(
        regexp = "^[a-zA-Z0-9 ]*$",
        message = "Board name may only contain letters, numbers & spaces")
@Schema(example = "Platform Launch")
public @interface BoardName {
    String message() default "Board name cannot be empty";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
