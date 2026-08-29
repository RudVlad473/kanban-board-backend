package com.vrudenko.kanban_board.e2e.reset;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ErrorCode;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.controller.ResetController;
import com.vrudenko.kanban_board.dto.reset_dto.ResetUsersRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.service.UserService;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.assertj.core.api.Assertions;
import org.fluttercode.datafactory.impl.DataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.RestAssured.given;

/**
 * HTTP-level proof of the reset endpoint's 204/403 contract (RESET-01, D-01), extending the
 * real-broker harness so a real reset actually runs the Kafka side too, not just the Postgres side.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("kafka")
@Tag("realSocket")
@ActiveProfiles({"test", "nonprod"})
@TestPropertySource(properties = {"app.reset.token=reset-controller-e2e-test-token-40chars-long"})
class ResetControllerE2ETest extends AbstractKafkaContainerTest {

    private static final String CORRECT_TOKEN = "reset-controller-e2e-test-token-40chars-long";
    private static final String WRONG_TOKEN = "totally-different-wrong-token-40chars-longx";

    @LocalServerPort private int port;

    @Value("${server.servlet.context-path}")
    private String contextPath;

    @Value("${server.servlet.session.cookie.name}")
    private String sessionCookieName;

    @PersistenceContext private EntityManager entityManager;

    @Autowired private UserService userService;

    private final DataFactory dataFactory = new DataFactory();

    @BeforeEach
    void setupRestAssured() {
        RestAssured.port = port;
        RestAssured.baseURI = "http://localhost";
        RestAssured.basePath = contextPath;
    }

    private long countRows(String table) {
        return ((Number)
                        entityManager
                                .createNativeQuery("SELECT count(*) FROM " + table)
                                .getSingleResult())
                .longValue();
    }

    private String randomEmail() {
        return "reset-controller-"
                + UUID.randomUUID().toString().toLowerCase(Locale.ROOT)
                + "@example.com";
    }

    private String randomPassword() {
        return dataFactory
                        .getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH)
                        .toLowerCase(Locale.ROOT)
                + "Aa1!";
    }

    private String signUpBareUser() {
        return userService
                .save(
                        SignupRequestDTO.builder()
                                .email(randomEmail())
                                .displayName(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                                .password(randomPassword())
                                .build())
                .getId();
    }

    @Nested
    class ResetEndpoint {
        @Test
        void should_return204AndEmptyStores_when_calledWithTheCorrectToken() {
            // act
            var response =
                    given().header(ResetController.RESET_TOKEN_HEADER, CORRECT_TOKEN)
                            .queryParam("fullReset", "true")
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            for (String table : List.of("users", "boards", "columns", "tasks", "subtasks")) {
                Assertions.assertThat(countRows(table)).isZero();
            }
        }

        @Test
        void should_return403ProblemDetail_when_tokenIsWrong() {
            // act
            var response =
                    given().header(ResetController.RESET_TOKEN_HEADER, WRONG_TOKEN)
                            .queryParam("fullReset", "true")
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
            Assertions.assertThat(response.body().jsonPath().getString("code"))
                    .isEqualTo("ACCESS_DENIED");
        }

        @Test
        void should_return403ProblemDetail_when_headerIsAbsent() {
            // act
            var response =
                    given().queryParam("fullReset", "true")
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert: byte-identical status and code to the wrong-token case above -- absence
            // is not distinguishable from mismatch.
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
            Assertions.assertThat(response.body().jsonPath().getString("code"))
                    .isEqualTo("ACCESS_DENIED");
        }

        @Test
        void should_return403_when_tokenIsBlank() {
            // act
            var response =
                    given().header(ResetController.RESET_TOKEN_HEADER, "")
                            .queryParam("fullReset", "true")
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        }

        @Test
        void should_notCreateASession_when_called() {
            // act
            var response =
                    given().header(ResetController.RESET_TOKEN_HEADER, CORRECT_TOKEN)
                            .queryParam("fullReset", "true")
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            Assertions.assertThat(response.cookie(sessionCookieName)).isNull();
            Assertions.assertThat(countRows("spring_session")).isZero();
        }

        @Test
        void should_return204Again_when_calledTwice() {
            // arrange
            given().header(ResetController.RESET_TOKEN_HEADER, CORRECT_TOKEN)
                    .queryParam("fullReset", "true")
                    .when()
                    .post(ApiPaths.RESET)
                    .then()
                    .statusCode(HttpStatus.NO_CONTENT.value());

            // act
            var response =
                    given().header(ResetController.RESET_TOKEN_HEADER, CORRECT_TOKEN)
                            .queryParam("fullReset", "true")
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        }
    }

    /**
     * HTTP-level proof of the targeted-delete route (quick task 260829-ii3). Every request here
     * carries NO {@code fullReset} query parameter, proving that is what a plain {@code POST}
     * reaches per {@link ResetController}'s {@code params}-based dispatch.
     */
    @Nested
    class DeleteUsersEndpoint {
        @Test
        void should_return204AndDeleteTheUser_when_calledWithTheCorrectTokenAndOneUserId() {
            // arrange
            var userId = signUpBareUser();

            // act
            var response =
                    given().header(ResetController.RESET_TOKEN_HEADER, CORRECT_TOKEN)
                            .contentType(ContentType.JSON)
                            .body(ResetUsersRequestDTO.builder().userIds(List.of(userId)).build())
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            Assertions.assertThat(userService.findAll()).extracting("id").doesNotContain(userId);
        }

        @Test
        void should_return400ValidationFailed_when_userIdsIsEmpty() {
            // act
            var response =
                    given().header(ResetController.RESET_TOKEN_HEADER, CORRECT_TOKEN)
                            .contentType(ContentType.JSON)
                            .body(ResetUsersRequestDTO.builder().userIds(List.of()).build())
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            Assertions.assertThat(response.body().jsonPath().getString("code"))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        }

        @Test
        void should_return404AndDeleteNothing_when_batchContainsOneUnknownId() {
            // arrange
            var realUserId = signUpBareUser();
            var bogusId = UUID.randomUUID().toString();

            // act
            var response =
                    given().header(ResetController.RESET_TOKEN_HEADER, CORRECT_TOKEN)
                            .contentType(ContentType.JSON)
                            .body(
                                    ResetUsersRequestDTO.builder()
                                            .userIds(List.of(realUserId, bogusId))
                                            .build())
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
            Assertions.assertThat(response.body().jsonPath().getString("code"))
                    .isEqualTo(ErrorCode.ENTITY_NOT_FOUND.name());
            Assertions.assertThat(userService.findAll()).extracting("id").contains(realUserId);
        }

        @Test
        void should_return403ProblemDetail_when_tokenIsWrong() {
            // arrange
            var userId = signUpBareUser();

            // act
            var response =
                    given().header(ResetController.RESET_TOKEN_HEADER, WRONG_TOKEN)
                            .contentType(ContentType.JSON)
                            .body(ResetUsersRequestDTO.builder().userIds(List.of(userId)).build())
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert: same shape as the full-reset path's own wrong-token 403.
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
            Assertions.assertThat(response.body().jsonPath().getString("code"))
                    .isEqualTo(ErrorCode.ACCESS_DENIED.name());
        }

        @Test
        void should_return403ProblemDetail_when_headerIsAbsent() {
            // arrange
            var userId = signUpBareUser();

            // act
            var response =
                    given().contentType(ContentType.JSON)
                            .body(ResetUsersRequestDTO.builder().userIds(List.of(userId)).build())
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert: no oracle on header absence, identical to the full-reset path's guarantee.
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
            Assertions.assertThat(response.body().jsonPath().getString("code"))
                    .isEqualTo(ErrorCode.ACCESS_DENIED.name());
        }
    }
}
