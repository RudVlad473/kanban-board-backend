package com.vrudenko.kanban_board.dto;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.UpdateSubtaskRequestDTO;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the client-visible violation message for {@link
 * com.vrudenko.kanban_board.dto.annotation.SubtaskTitle}'s length constraint.
 *
 * <p>{@code @ReportAsSingleViolation} on {@code SubtaskTitle} collapses any failure of its
 * composing {@code @Size} constraint onto the composed annotation's own {@code message()} default,
 * {@code "Subtask title cannot be empty"} — which means the inner {@code @Size}'s {@code message}
 * attribute is never rendered to a caller, no matter what it is set to. This test pins that
 * behavior directly (falsified below, not merely asserted) so that the source-legibility fix of
 * correcting the inner message to {@code SUBTASK_TITLE_LENGTH_VALIDATION_MESSAGE} is not mistaken
 * for a behavior change. If {@code @ReportAsSingleViolation} is ever removed from {@code
 * SubtaskTitle}, the inner {@code @Size} message becomes client-visible for the first time, this
 * test goes red, and the constant's correctness starts to matter for real.
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
