package com.vrudenko.kanban_board.e2e.reset;

import java.util.List;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.controller.ResetController;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;

import io.restassured.RestAssured;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

    @Nested
    class ResetEndpoint {
        @Test
        void should_return204AndEmptyStores_when_calledWithTheCorrectToken() {
            // act
            var response =
                    given().header(ResetController.RESET_TOKEN_HEADER, CORRECT_TOKEN)
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
            var response = given().when().post(ApiPaths.RESET).then().extract();

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
                    .when()
                    .post(ApiPaths.RESET)
                    .then()
                    .statusCode(HttpStatus.NO_CONTENT.value());

            // act
            var response =
                    given().header(ResetController.RESET_TOKEN_HEADER, CORRECT_TOKEN)
                            .when()
                            .post(ApiPaths.RESET)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        }
    }
}
