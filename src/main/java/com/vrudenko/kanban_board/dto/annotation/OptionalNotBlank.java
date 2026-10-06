package com.vrudenko.kanban_board.dto.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

/**
 * Reject a whitespace-only String while leaving null (an omitted, optional field)
 * untouched.
 *
 * Stack it alongside a field's existing composed annotation (which owns that
 * field's @Size and character-class rules, e.g. BoardName, TaskTitle,
 * SubtaskTitle, DisplayName), never to replace it.
 *
 * Decisions:
 *
 * The composing constraint is Pattern, not @NotBlank: every built-in Bean
 * Validation constraint treats null as valid (only @NotNull / @NotBlank
 * / @NotEmpty reject it), so composing @Pattern is what makes "optional but not
 * blank" work. Swapping in @NotBlank would silently make every field this annotation is
 * applied to mandatory.
 *
 * Pattern.Flag.DOTALL is required because Pattern evaluates with
 * Matcher.matches() (a whole-string match), and an undotted . does not match a newline:
 * without DOTALL a legitimate multi-line value would be rejected outright.
 */
@Documented
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Pattern(regexp = ".*\\S.*", flags = Pattern.Flag.DOTALL) public @interface OptionalNotBlank {
    String message() default "must not be blank when provided";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
