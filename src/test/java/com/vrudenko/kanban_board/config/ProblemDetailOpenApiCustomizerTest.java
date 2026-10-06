package com.vrudenko.kanban_board.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ErrorCode;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Regression guard: the OpenAPI document declares the ProblemDetail envelope on every
 * operation, and it matches what both envelope producers emit.
 *
 * Why this is the way it is: extends AbstractAppMockMvcTest, not
 * AbstractPostgresContainerTest, because ProblemDetailSchemaFidelity needs
 * signinCookie() and an owned board to provoke real 404/400 responses. Splitting the class would
 * break the cohesion of a contract that is meaningful only when "every operation declares the six
 * codes" and "the declared schema is what gets emitted" are read together. The document is read as
 * in OpenApiDocsTest; the OpenAPI bean is never autowired, because springdoc caches
 * it and a live instance would share mutable state with later assertions.
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

        @Test
        void shouldDocumentAtLeastTwentyOperations_whenSpecIsGenerated() throws Exception {
            // arrange
            var document = fetchDocument();
            var paths = document.path("paths");
            var operationCount = 0;

            // act: count every operation the sweep walks, so an empty `paths` object cannot satisfy
            // an every-operation sweep over zero operations
            var pathNames = paths.fieldNames();
            while (pathNames.hasNext()) {
                var pathItem = paths.path(pathNames.next());
                var methodNames = pathItem.fieldNames();
                while (methodNames.hasNext()) {
                    if (HTTP_METHOD_FIELD_NAMES.contains(methodNames.next())) {
                        operationCount++;
                    }
                }
            }

            // assert: floor of 20 with slack under the 24 operations observed when written; the
            // `nonprod`-profiled reset controller is absent under the `test` profile
            Assertions.assertThat(operationCount).isGreaterThanOrEqualTo(20);
        }

        @Test
        void shouldPreserveGeneratedSuccessResponse_whenCustomizerHasRun() throws Exception {
            // arrange
            var document = fetchDocument();

            // act
            var boardsGetResponses =
                    document.path("paths").path(ApiPaths.BOARDS).path("get").path("responses");

            // assert: the customizer inserts, never replaces, springdoc's generated 200
            Assertions.assertThat(boardsGetResponses.has("200")).isTrue();
        }
    }

    @Nested
    class ProblemDetailSchemaFidelity {

        @Test
        void shouldDeclareCodeEnumMatchingErrorCodeValues_whenSchemaIsGenerated() throws Exception {
            // arrange
            var document = fetchDocument();
            var codeEnumNode =
                    document.path("components")
                            .path("schemas")
                            .path("ProblemDetail")
                            .path("properties")
                            .path(ErrorCode.CODE_PROPERTY)
                            .path("enum");

            // act
            var declaredCodes = new HashSet<String>();
            for (JsonNode node : codeEnumNode) {
                declaredCodes.add(node.asText());
            }
            var expectedCodes = new HashSet<String>();
            for (var errorCode : ErrorCode.values()) {
                expectedCodes.add(errorCode.name());
            }

            // assert: set equality -- membership is the contract, not incidental ordering
            Assertions.assertThat(declaredCodes).isEqualTo(expectedCodes);
        }

        @Test
        void shouldDeclareEveryKeyEmitted_whenAuthenticatedRequestTargetsMissingBoard()
                throws Exception {
            // arrange
            var document = fetchDocument();
            Cookie cookie = signinCookie();
            var missingBoardId = "does-not-exist";
            var url = ApiPaths.BOARDS + "/" + missingBoardId + ApiPaths.FULL;

            // act
            var response = mockMvc.perform(get(url).cookie(cookie)).andReturn().getResponse();
            var objectMapper = new ObjectMapper();
            var body = objectMapper.readTree(response.getContentAsString());

            // assert
            assertResponseMatchesDeclaredSchema(document, body);
        }

        @Test
        void shouldDeclareEveryKeyEmitted_whenBoardNameFailsFieldValidation() throws Exception {
            // arrange
            var document = fetchDocument();
            Cookie cookie = signinCookie();
            var overlongName = "A".repeat(ValidationConstants.MAX_BOARD_NAME_LENGTH + 1);
            var objectMapper = new ObjectMapper();

            // act
            var response =
                    mockMvc.perform(
                                    post(ApiPaths.BOARDS)
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    objectMapper.writeValueAsString(
                                                            SaveBoardRequestDTO.builder()
                                                                    .name(overlongName)
                                                                    .build())))
                            .andReturn()
                            .getResponse();
            var body = objectMapper.readTree(response.getContentAsString());

            // assert: the only sample carrying the field-error map, so it exercises `errors`
            assertResponseMatchesDeclaredSchema(document, body);
        }

        @Test
        void shouldDeclareEveryKeyEmitted_whenRequestIsUnauthenticated() throws Exception {
            // arrange
            var document = fetchDocument();
            var objectMapper = new ObjectMapper();

            // act: no cookie at all -- ProblemDetailAuthenticationEntryPoint, not
            // GlobalExceptionHandler, produces this response
            var response = mockMvc.perform(get(ApiPaths.BOARDS)).andReturn().getResponse();
            var body = objectMapper.readTree(response.getContentAsString());

            // assert
            assertResponseMatchesDeclaredSchema(document, body);
        }

        /**
         * Asserts both ways that a sampled body agrees with the ProblemDetail schema: every
         * emitted field is declared, and every required property is present.
         */
        private void assertResponseMatchesDeclaredSchema(JsonNode document, JsonNode responseBody) {
            var schema = document.path("components").path("schemas").path("ProblemDetail");
            var declaredProperties = new HashSet<String>();
            schema.path("properties").fieldNames().forEachRemaining(declaredProperties::add);

            var requiredProperties = new HashSet<String>();
            for (JsonNode node : schema.path("required")) {
                requiredProperties.add(node.asText());
            }

            var emittedKeys = new HashSet<String>();
            responseBody.fieldNames().forEachRemaining(emittedKeys::add);

            var undeclaredKeys = new HashSet<>(emittedKeys);
            undeclaredKeys.removeAll(declaredProperties);

            var missingRequiredKeys = new HashSet<>(requiredProperties);
            missingRequiredKeys.removeAll(emittedKeys);

            Assertions.assertThat(undeclaredKeys)
                    .as("emitted keys not declared in the ProblemDetail schema")
                    .isEmpty();
            Assertions.assertThat(missingRequiredKeys)
                    .as("required schema properties missing from the emitted response")
                    .isEmpty();
        }
    }
}
