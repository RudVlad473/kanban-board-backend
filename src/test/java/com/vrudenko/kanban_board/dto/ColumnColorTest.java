package com.vrudenko.kanban_board.dto;

import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Validator-tier coverage for {@link com.vrudenko.kanban_board.dto.annotation.ColumnColor}, proving
 * the full boundary matrix: null (omitted) passes, a valid {@code #RRGGBB} value in either case
 * passes, and every malformed shape (missing hash, wrong digit count, non-hex letters,
 * trailing/leading whitespace) produces exactly one violation on {@code color}.
 */
public class ColumnColorTest {
    private Validator validator;

    @BeforeEach
    public void setup() {
        var factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Nested
    class SaveColumnRequestDTOTest {
        @Test
        void shouldReturnNoViolations_whenColorIsNull() {
            // arrange
            var dto = SaveColumnRequestDTO.builder().name("Column").color(null).build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).isEmpty();
        }

        @Test
        void shouldReturnNoViolations_whenColorIsLowercaseHex() {
            // arrange
            var dto = SaveColumnRequestDTO.builder().name("Column").color("#ff0000").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).isEmpty();
        }

        @Test
        void shouldReturnNoViolations_whenColorIsUppercaseHex() {
            // arrange
            var dto = SaveColumnRequestDTO.builder().name("Column").color("#FF0000").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).isEmpty();
        }

        @Test
        void shouldReturnNoViolations_whenColorIsMixedCaseHex() {
            // arrange
            var dto = SaveColumnRequestDTO.builder().name("Column").color("#AbCdEf").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).isEmpty();
        }

        @Test
        void shouldReturnOneViolationOnColor_whenColorIsEmptyString() {
            // arrange
            var dto = SaveColumnRequestDTO.builder().name("Column").color("").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(
                            violations.stream().findFirst().get().getPropertyPath().toString())
                    .isEqualTo("color");
        }

        @Test
        void shouldReturnOneViolationOnColor_whenColorIsWhitespaceOnly() {
            // arrange
            var dto = SaveColumnRequestDTO.builder().name("Column").color("   ").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(
                            violations.stream().findFirst().get().getPropertyPath().toString())
                    .isEqualTo("color");
        }

        @Test
        void shouldReturnOneViolationOnColor_whenColorIsMissingLeadingHash() {
            // arrange
            var dto = SaveColumnRequestDTO.builder().name("Column").color("ff0000").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(
                            violations.stream().findFirst().get().getPropertyPath().toString())
                    .isEqualTo("color");
        }

        @Test
        void shouldReturnOneViolationOnColor_whenColorHasFiveDigits() {
            // arrange
            var dto = SaveColumnRequestDTO.builder().name("Column").color("#ff000").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(
                            violations.stream().findFirst().get().getPropertyPath().toString())
                    .isEqualTo("color");
        }

        @Test
        void shouldReturnOneViolationOnColor_whenColorHasSevenDigits() {
            // arrange
            var dto = SaveColumnRequestDTO.builder().name("Column").color("#ff00000").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(
                            violations.stream().findFirst().get().getPropertyPath().toString())
                    .isEqualTo("color");
        }

        @Test
        void shouldReturnOneViolationOnColor_whenColorHasNonHexLetters() {
            // arrange
            var dto = SaveColumnRequestDTO.builder().name("Column").color("#gg0000").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(
                            violations.stream().findFirst().get().getPropertyPath().toString())
                    .isEqualTo("color");
        }

        @Test
        void shouldReturnOneViolationOnColor_whenColorHasTrailingNewline() {
            // arrange: proves @Pattern's Matcher.matches() whole-region semantics -- a trailing
            // newline after an otherwise-valid value must not slip past (trade-off 3).
            var dto = SaveColumnRequestDTO.builder().name("Column").color("#ff0000\n").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(
                            violations.stream().findFirst().get().getPropertyPath().toString())
                    .isEqualTo("color");
        }

        @Test
        void shouldReturnOneViolationOnColor_whenColorHasSurroundingSpaces() {
            // arrange: Bean Validation does not trim -- a value with real content padded by
            // whitespace must still be rejected.
            var dto = SaveColumnRequestDTO.builder().name("Column").color(" #ff0000 ").build();

            // act
            var violations = validator.validate(dto);

            // assert
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(
                            violations.stream().findFirst().get().getPropertyPath().toString())
                    .isEqualTo("color");
        }
    }
}
