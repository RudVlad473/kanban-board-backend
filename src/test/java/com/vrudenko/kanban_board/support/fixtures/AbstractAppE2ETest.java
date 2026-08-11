package com.vrudenko.kanban_board.support.fixtures;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.util.Pair;

import static io.restassured.RestAssured.given;

public abstract class AbstractAppE2ETest extends AbstractAppTest {
    /** Env variables */
    @LocalServerPort protected int port;

    @Value("${server.servlet.session.cookie.name}")
    protected String COOKIE_NAME;

    @Value("${server.servlet.context-path}")
    protected String CONTEXT_PATH;

    /***/

    @Override
    @BeforeEach
    protected void setup() {
        super.setup();

        RestAssured.port = port;
        RestAssured.baseURI = "http://localhost";
        RestAssured.basePath = CONTEXT_PATH;
    }

    /**
     * @return first - cookie name, second - cookie value
     */
    protected Pair<String, String> signin() {
        var cookie =
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
                        .extract()
                        .cookie(COOKIE_NAME);

        return Pair.of(COOKIE_NAME, cookie);
    }
}
