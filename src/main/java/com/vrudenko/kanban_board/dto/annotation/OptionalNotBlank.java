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
 * Rejects a whitespace-only {@code String} while leaving {@code null} (an omitted, optional field)
 * untouched.
 *
 * <p>The composing constraint is deliberately {@link Pattern}, not {@code @NotBlank}: every
 * built-in Bean Validation constraint treats {@code null} as valid — only {@code @NotNull} /
 * {@code @NotBlank} / {@code @NotEmpty} reject it — so composing {@code @Pattern} is what makes
 * "optional but not blank" work. Swapping the composing constraint for {@code @NotBlank} would
 * silently make every field this annotation is applied to mandatory.
 *
 * <p>{@code Pattern.Flag.DOTALL} is required because {@link Pattern} evaluates with {@code
 * Matcher.matches()} (a whole-string match), and an undotted {@code .} does not match a newline —
 * without {@code DOTALL} a legitimate multi-line value would be rejected outright.
 *
 * <p>This annotation is meant to be stacked alongside a field's existing composed annotation (which
 * owns the {@code @Size} and character-class rules for that field, e.g. {@link BoardName}, {@link
 * TaskTitle}, {@link SubtaskTitle}, {@link DisplayName}), never to replace it.
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
