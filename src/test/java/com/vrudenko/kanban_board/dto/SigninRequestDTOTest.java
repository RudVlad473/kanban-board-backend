package com.vrudenko.kanban_board.dto;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.fluttercode.datafactory.impl.DataFactory;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class SigninRequestDTOTest {
  private Validator validator;
  DataFactory dataFactory = new DataFactory();

  private final String validEmail = dataFactory.getEmailAddress();
  private final String validDisplayName = dataFactory.getName();
  private final String validPassword =
      dataFactory
          .getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH)
          .toLowerCase()
          .concat(String.valueOf(dataFactory.getRandomWord(1)).toUpperCase())
          .concat(String.valueOf(dataFactory.getNumberBetween(0, 9)))
          .concat("$");

  @BeforeEach
  public void setup() {
    var factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @Test
  public void whenAllFieldsAreValid_thenNoViolations() {
    // arrange
    var dto = new SigninRequestDTO();
    dto.setEmail(validEmail);
    dto.setPassword(validPassword);

    // act

    // assert
    Assertions.assertThat(validator.validate(dto)).isEmpty();
  }

  @Test
  public void whenEmailIsMissing_thenOneViolation() {
    // arrange
    var dto = new SigninRequestDTO();
    dto.setEmail("");

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
    var dto = new SigninRequestDTO();
    dto.setEmail(validEmail);

    // act

    // assert
    var violations = validator.validate(dto);
    Assertions.assertThat(violations.size()).isZero();
  }

  @Test
  public void whenDisplayNameIsTooShort_thenOneViolation() {
    // arrange
    var dto = new SigninRequestDTO();
    dto.setEmail(validEmail);

    // act

    // assert
    var violations = validator.validate(dto);
    Assertions.assertThat(violations.size()).isEqualTo(1);
    Assertions.assertThat(violations.stream().findFirst().get().getPropertyPath().toString())
        .isEqualTo("displayName");
  }

  @Test
  public void whenDisplayNameIsTooLong_thenOneViolation() {
    // arrange
    var dto = new SigninRequestDTO();
    dto.setEmail(validEmail);

    // act

    // assert
    var violations = validator.validate(dto);
    Assertions.assertThat(violations.size()).isEqualTo(1);
    Assertions.assertThat(violations.stream().findFirst().get().getPropertyPath().toString())
        .isEqualTo("displayName");
  }

  @Test
  public void whenDisplayNameContainsDigits_thenOneViolation() {
    // arrange
    var dto = new SigninRequestDTO();
    dto.setEmail(validEmail);

    // act

    // assert
    var violations = validator.validate(dto);
    Assertions.assertThat(violations.size()).isEqualTo(1);
    Assertions.assertThat(violations.stream().findFirst().get().getPropertyPath().toString())
        .isEqualTo("displayName");
  }

  @Test
  public void whenDisplayNameContainsSpecialCharacters_thenOneViolation() {
    // arrange
    var dto = new SigninRequestDTO();
    dto.setEmail(validEmail);

    // act

    // assert
    var violations = validator.validate(dto);
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
                    .concat(dataFactory.getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH))),
            Map.entry("noLowercaseCharPassword", validPassword.toUpperCase()),
            Map.entry("noUppercaseCharPassword", validPassword.toLowerCase()),
            Map.entry(
                "shortPassword",
                new StringBuilder(validPassword)
                    .reverse()
                    .substring(0, ValidationConstants.MIN_PASSWORD_LENGTH - 1)),
            Map.entry(
                "longPassword",
                validPassword.concat(
                    dataFactory.getRandomWord(
                        ValidationConstants.MAX_PASSWORD_LENGTH - validPassword.length() + 1))),
            Map.entry(
                "noDigitPassword",
                validPassword
                    .replaceAll("\\d", "")
                    .concat(dataFactory.getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH))),
            Map.entry("emptyPassword", ""));

    for (var invalidPassword : invalidPasswords.entrySet()) {
      // arrange
      var dto = new SigninRequestDTO();
      dto.setEmail(validEmail);
      dto.setPassword(invalidPassword.getValue());

      // act

      // assert
      var violations = validator.validate(dto);
      System.out.println(invalidPassword.getKey() + " : " + invalidPassword.getValue());
      Assertions.assertThat(violations).hasSize(1);
      Assertions.assertThat(violations.stream().findFirst().get().getPropertyPath().toString())
          .isEqualTo("password");
    }
  }
}
