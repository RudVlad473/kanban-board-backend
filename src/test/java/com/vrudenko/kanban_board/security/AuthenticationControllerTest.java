package com.vrudenko.kanban_board.security;

import static io.restassured.RestAssured.given;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.vrudenko.kanban_board.AbstractAppTest;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;

import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AuthenticationControllerTest extends AbstractAppTest {
    @LocalServerPort private int port;

    @Value("${server.servlet.session.cookie.name}")
    private String COOKIE_NAME;

    @Value("${server.servlet.context-path}")
    private String CONTEXT_PATH;

    @BeforeEach
    protected void setup() {
        super.setup();

        RestAssured.port = port;
        RestAssured.baseURI = "http://localhost";
        RestAssured.basePath = CONTEXT_PATH;
    }

    @Nested
    class Signin {
        @Nested
        class Authenticated {
            @Test
            void testWithValidCredential_shouldPopulateCookie_whenUserExists() throws Exception {
                // Arrange
                // Act
                given().contentType(ContentType.JSON)
                        .body(
                                SigninRequestDTO.builder()
                                        .email(getOwningUser().getEmail())
                                        .password(getOwningUserPassword())
                                        .build())
                        .when()
                        .post(ApiPaths.SIGNIN)
                        .then()
                        // Assert
                        .statusCode(HttpStatus.OK.value())
                        .cookie(COOKIE_NAME, notNullValue());
            }
        }

        @Nested
        class Unauthenticated {
            @Test
            // we don't want to populate cookies for each request, only for successful ones
            void testWithInvalidCredential_shouldNotPopulateCookie_whenUserDoesntExist()
                    throws Exception {
                // Arrange
                // Act
                var response =
                        given().contentType(ContentType.JSON)
                                .body(
                                        SigninRequestDTO.builder()
                                                .email(getOwningUser().getEmail())
                                                .password(getOwningUserPassword().concat("__"))
                                                .build())
                                .when()
                                .post(ApiPaths.SIGNIN);

                // Assert
                assertThat(response.getStatusCode(), is(HttpStatus.UNAUTHORIZED.value()));
                assertThat(response.getCookies(), not(hasKey(COOKIE_NAME)));
            }
        }
    }

    @Nested
    class Signup {
        @Nested
        class Authenticated {
            @Test
            void testWithValidCredential_shouldPopulateCookie_whenUserExists() throws Exception {
                // Arrange
                var body =
                        SignupRequestDTO.builder()
                                .email(dataFactory.getEmailAddress())
                                .password(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_PASSWORD_LENGTH))
                                .displayName(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                                .build();

                // Act
                given().contentType(ContentType.JSON)
                        .body(body)
                        .when()
                        .post(ApiPaths.SIGNUP)
                        .then()
                        // Assert
                        .statusCode(HttpStatus.CREATED.value())
                        .cookie(COOKIE_NAME, notNullValue());
            }
        }
    }
}
