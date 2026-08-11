package com.vrudenko.kanban_board.dto;

import java.util.Locale;
import java.util.Map;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.Assertions;
import org.fluttercode.datafactory.impl.DataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SignupRequestDTOTest {
    private Validator validator;
    DataFactory dataFactory = new DataFactory();

    // Guaranteed-valid: dataFactory.getEmailAddress()'s word-based local-part branch occasionally
    // draws a multi-word entry from DataFactory's dirty corpus (e.g. the literal "or maybe") and
    // concatenates it with a second word with no separator, producing an email with an embedded
    // space that fails @AppEmail's @Email format check -- see
    // AbstractAppTest.generateValidEmail()'s Javadoc for the full root-cause writeup. This was the
    // exact, previously-unresolved cause of this file's own flakiness, documented below until now.
    private final String validEmail =
            RandomStringUtils.randomAlphabetic(10).toLowerCase(Locale.ROOT) + "@example.com";
    private final String validDisplayName = dataFactory.getName();
    // Locale.ROOT pinned explicitly: under a Turkish default locale, toLowerCase/toUpperCase
    // apply the dotted/dotless-I mapping, which would corrupt these password fixtures and
    // produce spurious failures in the "no uppercase char"/"no lowercase char" validation cases
    // below that depend on them.
    //
    // Guaranteed-bounded length: dataFactory.getRandomWord(MIN_PASSWORD_LENGTH) has no upper bound
    // on the returned word's length, so concatenating it with the two suffixes below risked
    // occasionally exceeding MAX_PASSWORD_LENGTH and failing @Password's @Size constraint --
    // RandomStringUtils.randomAlphabetic gives a length guarantee dataFactory's word corpus cannot.
    private final String validPassword =
            RandomStringUtils.randomAlphabetic(ValidationConstants.MIN_PASSWORD_LENGTH)
                    .toLowerCase(Locale.ROOT)
                    .concat("A")
                    .concat(String.valueOf(dataFactory.getNumberBetween(0, 9)))
                    .concat("$");
    private SignupRequestDTO validDTO;

    @BeforeEach
    public void setup() {
        var factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();

        validDTO =
                SignupRequestDTO.builder()
                        .email(validEmail)
                        .displayName(validDisplayName)
                        .password(validPassword)
                        .build();
    }

    @Test
    public void whenAllFieldsAreValid_thenNoViolations() {
        // arrange

        // act

        // assert
        Assertions.assertThat(validator.validate(validDTO)).isEmpty();
    }

    @Test
    public void whenEmailIsMissing_thenOneViolation() {
        // arrange
        var dto =
                SignupRequestDTO.builder()
                        .password(validPassword)
                        .displayName(validDisplayName)
                        .build();

        // act

        // assert
        var violations = validator.validate(dto);
        Assertions.assertThat(violations.size()).isEqualTo(1);
        Assertions.assertThat(violations.stream().findFirst().get().getPropertyPath().toString())
                .isEqualTo("email");
    }

    @Test
    public void whenDisplayNameIsMissing_thenNoViolation() {
        // arrange
        var dto = SignupRequestDTO.builder().email(validEmail).password(validPassword).build();

        // act

        // assert
        var violations = validator.validate(dto);
        Assertions.assertThat(violations.size()).isZero();
    }

    @Test
    public void whenDisplayNameIsTooShort_thenOneViolation() {
        // arrange
        validDTO.setDisplayName(
                dataFactory.getRandomWord(ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH - 1));

        // act

        // assert
        var violations = validator.validate(validDTO);
        Assertions.assertThat(violations.size()).isEqualTo(1);
        Assertions.assertThat(violations.stream().findFirst().get().getPropertyPath().toString())
                .isEqualTo("displayName");
    }

    @Test
    public void whenDisplayNameIsTooLong_thenOneViolation() {
        // arrange
        validDTO.setDisplayName(
                dataFactory.getRandomWord(ValidationConstants.MAX_USER_DISPLAY_NAME_LENGTH + 1));

        // act

        // assert
        var violations = validator.validate(validDTO);
        Assertions.assertThat(violations.size()).isEqualTo(1);
        Assertions.assertThat(violations.stream().findFirst().get().getPropertyPath().toString())
                .isEqualTo("displayName");
    }

    @Test
    public void whenDisplayNameContainsDigits_thenOneViolation() {
        // arrange
        validDTO.setDisplayName(
                validDTO.getDisplayName()
                        .concat(String.valueOf(dataFactory.getNumberBetween(0, 9))));

        // act

        // assert
        var violations = validator.validate(validDTO);
        Assertions.assertThat(violations.size()).isEqualTo(1);
        Assertions.assertThat(violations.stream().findFirst().get().getPropertyPath().toString())
                .isEqualTo("displayName");
    }

    @Test
    public void whenDisplayNameContainsSpecialCharacters_thenOneViolation() {
        // arrange
        validDTO.setDisplayName(validDTO.getDisplayName().concat("$"));

        // act

        // assert
        var violations = validator.validate(validDTO);
        Assertions.assertThat(violations.size()).isEqualTo(1);
        Assertions.assertThat(violations.stream().findFirst().get().getPropertyPath().toString())
                .isEqualTo("displayName");
    }

    @Test
    public void whenPasswordIsInvalid_thenOneViolations() {
        Map<String, String> invalidPasswords =
                Map.ofEntries(
                        Map.entry(
                                "noSpecialCharacterPassword",
                                validPassword
                                        .replaceAll("[^a-zA-Z0-9]", "")
                                        .concat(
                                                dataFactory.getRandomWord(
                                                        ValidationConstants.MIN_PASSWORD_LENGTH))),
                        Map.entry(
                                "noLowercaseCharPassword", validPassword.toUpperCase(Locale.ROOT)),
                        Map.entry(
                                "noUppercaseCharPassword", validPassword.toLowerCase(Locale.ROOT)),
                        Map.entry(
                                "shortPassword",
                                new StringBuilder(validPassword)
                                        .reverse()
                                        .substring(0, ValidationConstants.MIN_PASSWORD_LENGTH - 1)),
                        Map.entry(
                                "longPassword",
                                validPassword.concat(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MAX_PASSWORD_LENGTH
                                                        - validPassword.length()
                                                        + 1))),
                        Map.entry(
                                "noDigitPassword",
                                validPassword
                                        .replaceAll("\\d", "")
                                        .concat(
                                                dataFactory.getRandomWord(
                                                        ValidationConstants.MIN_PASSWORD_LENGTH))),
                        Map.entry("emptyPassword", ""));

        for (var invalidPassword : invalidPasswords.entrySet()) {
            // arrange
            validDTO.setPassword(invalidPassword.getValue());

            // act

            // assert
            var violations = validator.validate(validDTO);
            System.out.println(invalidPassword.getKey() + " : " + invalidPassword.getValue());
            Assertions.assertThat(violations).hasSize(1);
            Assertions.assertThat(
                            violations.stream().findFirst().get().getPropertyPath().toString())
                    .isEqualTo("password");
        }
    }
}
