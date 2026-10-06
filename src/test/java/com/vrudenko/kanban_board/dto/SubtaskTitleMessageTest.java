package com.vrudenko.kanban_board.dto;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.annotation.SubtaskTitle;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.UpdateSubtaskRequestDTO;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins com.vrudenko.kanban_board.dto.annotation.SubtaskTitle's length-constraint message
 * and SaveSubtaskRequestDTO.getTitle()'s null/whitespace/empty boundary matrix.
 *
 * Why this is the way it is: @ReportAsSingleViolation on SubtaskTitle collapses
 * any failure of its composing @Size onto the composed annotation's own message(),
 * "Subtask title cannot be empty", so the inner @Size message is never rendered to
 * a caller. This test pins that so correcting the inner message to
 * SUBTASK_TITLE_LENGTH_VALIDATION_MESSAGE is not mistaken for a behavior change. Falsifier:
 * if @ReportAsSingleViolation is removed, the inner message becomes client-visible and this
 * test goes red.
 */
class SubtaskTitleMessageTest {
    private Validator validator;

    @BeforeEach
    void setup() {
        var factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private String overLongTitle() {
        return "a".repeat(ValidationConstants.MAX_SUBTASK_TITLE_LENGTH + 1);
    }

    private String whitespaceOnlyTitle() {
        return " ".repeat(ValidationConstants.MIN_SUBTASK_TITLE_LENGTH);
    }

    @Nested
    class SaveSubtaskRequestDTOTest {
        @Test
        void shouldReturnOneViolation_withSubtaskTitleDefaultMessage_whenTitleIsTooLong() {
            // arrange
            var dto = SaveSubtaskRequestDTO.builder().title(overLongTitle()).build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(violations.iterator().next().getMessage())
                    .isEqualTo("Subtask title cannot be empty");
        }

        @Test
        void shouldReturnOneViolation_withSubtaskTitleDefaultMessage_whenTitleIsNull() {
            // arrange
            var dto = SaveSubtaskRequestDTO.builder().title(null).build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(violations.iterator().next().getMessage())
                    .isEqualTo("Subtask title cannot be empty");
        }

        @Test
        void shouldReturnOneViolation_withSubtaskTitleDefaultMessage_whenTitleIsWhitespaceOnly() {
            // arrange
            var dto = SaveSubtaskRequestDTO.builder().title(whitespaceOnlyTitle()).build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(violations.iterator().next().getMessage())
                    .isEqualTo("Subtask title cannot be empty");
        }

        @Test
        void shouldTriggerBothNotBlankAndSubtaskTitle_whenTitleIsEmptyString() {
            // arrange
            var dto = SaveSubtaskRequestDTO.builder().title("").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations)
                    .extracting(
                            violation ->
                                    violation
                                            .getConstraintDescriptor()
                                            .getAnnotation()
                                            .annotationType()
                                            .getSimpleName())
                    .containsExactlyInAnyOrder(
                            NotBlank.class.getSimpleName(), SubtaskTitle.class.getSimpleName());
            Assertions.assertThat(violations)
                    .allSatisfy(
                            violation ->
                                    Assertions.assertThat(violation.getPropertyPath().toString())
                                            .isEqualTo("title"));
        }
    }

    @Nested
    class UpdateSubtaskRequestDTOTest {
        @Test
        void shouldReturnOneViolation_withSubtaskTitleDefaultMessage_whenTitleIsTooLong() {
            // arrange
            var dto = UpdateSubtaskRequestDTO.builder().title(overLongTitle()).version(1L).build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(violations.iterator().next().getMessage())
                    .isEqualTo("Subtask title cannot be empty");
        }
    }
}
