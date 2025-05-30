package com.vrudenko.kanban_board.dto.annotation;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import jakarta.validation.Constraint;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Size;

import java.lang.annotation.*;


@Documented
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Size(
    min = ValidationConstants.MIN_TASK_TITLE_LENGTH,
    max = ValidationConstants.MAX_TASK_TITLE_LENGTH,
    message = ValidationConstants.TASK_TITLE_LENGTH_VALIDATION_MESSAGE)
public @interface TaskTitle {}
