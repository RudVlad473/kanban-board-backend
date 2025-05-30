package com.vrudenko.kanban_board.dto.annotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

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
@NotBlank(message = "Email cannot be empty")
@Email
public @interface AppEmail {
  String message() default "Email cannot be empty";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
