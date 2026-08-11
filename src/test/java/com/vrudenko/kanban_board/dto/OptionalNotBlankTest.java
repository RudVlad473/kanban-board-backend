package com.vrudenko.kanban_board.dto;

import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Validator-tier coverage for {@link OptionalNotBlank}, proving "optional but not blank" on every
 * field it is applied to: a whitespace-only value is rejected with exactly one violation, a null
 * (omitted) value still passes, and a value with real content padded by whitespace still passes.
 */
public class OptionalNotBlankTest {
    private Validator validator;

    @BeforeEach
    public void setup() {
        var factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Nested
    class UpdateBoardRequestDTOTest {
        @Test
        void shouldReturnOneViolationOnName_whenNameIsWhitespaceOnly() {
            // arrange
            var dto = UpdateBoardRequestDTO.builder().name("   ").version(1L).build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(
                            violations.stream().findFirst().get().getPropertyPath().toString())
                    .isEqualTo("name");
        }

        @Test
        void shouldReturnNoViolations_whenNameIsNull() {
            // arrange
            var dto = UpdateBoardRequestDTO.builder().name(null).version(1L).build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).isEmpty();
        }

        @Test
        void shouldReturnNoViolations_whenNameHasContentPaddedByWhitespace() {
            // arrange
            var dto = UpdateBoardRequestDTO.builder().name(" Valid Name ").version(1L).build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).isEmpty();
        }
    }
}
