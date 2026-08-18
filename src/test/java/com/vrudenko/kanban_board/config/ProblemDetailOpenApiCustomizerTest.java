package com.vrudenko.kanban_board.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Regression guard for API-01: proves the generated OpenAPI document declares the {@code
 * ProblemDetail} error envelope on every operation, and (Task 2's {@code
 * ProblemDetailSchemaFidelity} nested class, added after this task) that the declared schema
 * matches what both independent envelope producers ({@link
 * com.vrudenko.kanban_board.handler.GlobalExceptionHandler} and {@link
 * com.vrudenko.kanban_board.security.ProblemDetailAuthenticationEntryPoint}) actually emit.
 *
 * <p>Extends {@link AbstractAppMockMvcTest} rather than {@code AbstractPostgresContainerTest}
 * (which {@link OpenApiDocsTest} uses) deliberately: Task 2's fidelity assertions need {@code
 * signinCookie()} and a real owned board to provoke genuine {@code 404}/{@code 400} responses.
 * Splitting this class in two to spare this task's three fixture-free methods a fixture build would
 * break the cohesion of a contract that is only meaningful when its two halves — "every operation
 * declares the six codes" and "the declared schema is what actually gets emitted" — are read
 * together.
 *
 * <p>Reads the document exactly as {@link OpenApiDocsTest} does: {@code @Value} into the {@code
 * springdoc.api-docs.path} property, {@code mockMvc.perform(get(apiDocsPath))}, then parses the
 * response body with an {@link ObjectMapper}. Never autowires the {@code OpenAPI} bean — springdoc
 * caches the built document, so a test holding the live instance could mutate state shared with
 * every later assertion in the same Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProblemDetailOpenApiCustomizerTest extends AbstractAppMockMvcTest {

    private static final List<String> HTTP_METHOD_FIELD_NAMES =
            List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private static final List<String> ERROR_STATUS_CODES =
            List.of("400", "401", "403", "404", "409", "500");

    @Autowired private MockMvc mockMvc;

    @Value("${springdoc.api-docs.path}")
    private String apiDocsPath;

    JsonNode fetchDocument() throws Exception {
        var response = mockMvc.perform(get(apiDocsPath)).andReturn();
        var body = response.getResponse().getContentAsString();
        var objectMapper = new ObjectMapper();
        return objectMapper.readTree(body);
    }

    @Nested
    class ErrorResponseCoverage {

        @Test
        void shouldDeclareEveryStandardErrorResponse_whenEveryOperationIsInspected()
                throws Exception {
            // arrange
            var document = fetchDocument();
            var paths = document.path("paths");
            var failures = new ArrayList<String>();

            // act
            var pathNames = paths.fieldNames();
            while (pathNames.hasNext()) {
                var pathName = pathNames.next();
                var pathItem = paths.path(pathName);
                var methodNames = pathItem.fieldNames();
                while (methodNames.hasNext()) {
                    var methodName = methodNames.next();
                    if (!HTTP_METHOD_FIELD_NAMES.contains(methodName)) {
                        continue;
                    }
                    var operation = pathItem.path(methodName);
                    var operationLabel = methodName.toUpperCase(Locale.ROOT) + " " + pathName;
                    for (var statusCode : ERROR_STATUS_CODES) {
                        var response = operation.path("responses").path(statusCode);
                        if (response.isMissingNode()) {
                            failures.add(operationLabel + " -> missing status " + statusCode);
                            continue;
                        }
                        var mediaType =
                                response.path("content")
                                        .path(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                        if (mediaType.isMissingNode()) {
                            failures.add(
                                    operationLabel
                                            + " -> status "
                                            + statusCode
                                            + " missing application/problem+json content");
                            continue;
                        }
                        var ref = mediaType.path("schema").path("$ref").asText();
                        if (!ref.equals("#/components/schemas/ProblemDetail")) {
                            failures.add(
                                    operationLabel
                                            + " -> status "
                                            + statusCode
                                            + " schema ref is '"
                                            + ref
                                            + "', expected '#/components/schemas/ProblemDetail'");
                        }
                    }
                }
            }

            // assert: report every violation in one run, not just the first
            Assertions.assertThat(failures).isEmpty();
        }
    }
}
