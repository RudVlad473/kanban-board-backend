package com.vrudenko.kanban_board.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Regression guard: the generated OpenAPI document declares every path-template variable and never
 * publishes the session-derived user id as a client parameter.
 *
 * Why this is the way it is: spike 003 pointed Schemathesis at the live document and it could
 * not generate a single request for 11 of the 24 operations (the nested task, subtask and reorder
 * routes), because springdoc only declares a path parameter for a handler argument
 * carrying @PathVariable, so an ancestor id that appears only in the class-level route template was
 * never declared. The same run showed 22 operations advertising a required userId query
 * parameter that the server never reads. This test sweeps every operation in the document instead
 * of listing operations, so a future nested controller is covered without anyone remembering to add
 * it. It extends AbstractPostgresContainerTest, not a fixture base, because it needs no
 * fixtures (docs/CODE_STYLE.md rule 4), and the document is read as in
 * ProblemDetailOpenApiCustomizerTest: the OpenAPI bean is never autowired, because
 * springdoc caches it and a live instance would share mutable state with later assertions.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiParameterCompletenessTest extends AbstractPostgresContainerTest {

    private static final List<String> HTTP_METHOD_FIELD_NAMES =
            List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private static final Pattern PATH_TEMPLATE_VARIABLE = Pattern.compile("\\{([^/{}]+)}");

    private static final String SESSION_DERIVED_PARAMETER_NAME = "userId";

    // Floor with slack under the 24 operations observed when written; the `nonprod`-profiled reset
    // controller is absent under the `test` profile.
    private static final int MINIMUM_OPERATION_COUNT = 20;

    @Autowired private MockMvc mockMvc;

    @Value("${springdoc.api-docs.path}")
    private String apiDocsPath;

    JsonNode fetchDocument() throws Exception {
        var response = mockMvc.perform(get(apiDocsPath)).andReturn();
        var body = response.getResponse().getContentAsString();
        var objectMapper = new ObjectMapper();
        return objectMapper.readTree(body);
    }

    /** Parameters declared on the path item plus those declared on the operation itself. */
    private static List<JsonNode> declaredParameters(JsonNode pathItem, JsonNode operation) {
        var parameters = new ArrayList<JsonNode>();
        pathItem.path("parameters").forEach(parameters::add);
        operation.path("parameters").forEach(parameters::add);
        return parameters;
    }

    private static Set<String> templateVariables(String pathName) {
        var names = new TreeSet<String>();
        var matcher = PATH_TEMPLATE_VARIABLE.matcher(pathName);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Nested
    class PathTemplateParameters {

        @Test
        void
                shouldDeclareEveryTemplateVariableAsRequiredPathParameter_whenEveryOperationIsInspected()
                        throws Exception {
            // arrange
            JsonNode document = fetchDocument();
            JsonNode paths = document.path("paths");
            var failures = new ArrayList<String>();
            var operationCount = 0;

            // act
            var pathNames = paths.fieldNames();
            while (pathNames.hasNext()) {
                var pathName = pathNames.next();
                JsonNode pathItem = paths.path(pathName);
                var methodNames = pathItem.fieldNames();
                while (methodNames.hasNext()) {
                    var methodName = methodNames.next();
                    if (!HTTP_METHOD_FIELD_NAMES.contains(methodName)) {
                        continue;
                    }
                    operationCount++;
                    JsonNode operation = pathItem.path(methodName);
                    var declaredPathParameters = new HashSet<String>();
                    for (JsonNode parameter : declaredParameters(pathItem, operation)) {
                        var isRequiredPathParameter =
                                "path".equals(parameter.path("in").asText())
                                        && parameter.path("required").asBoolean(false);
                        if (isRequiredPathParameter) {
                            declaredPathParameters.add(parameter.path("name").asText());
                        }
                    }
                    var missing = new TreeSet<>(templateVariables(pathName));
                    missing.removeAll(declaredPathParameters);
                    if (!missing.isEmpty()) {
                        failures.add(
                                methodName.toUpperCase(Locale.ROOT)
                                        + " "
                                        + pathName
                                        + " -> missing required path parameters "
                                        + missing);
                    }
                }
            }

            // assert: report every violation in one run, not just the first
            Assertions.assertThat(operationCount)
                    .as("operations walked, so an empty `paths` object cannot pass")
                    .isGreaterThanOrEqualTo(MINIMUM_OPERATION_COUNT);
            Assertions.assertThat(failures)
                    .as("%d operations leave a path-template variable undeclared", failures.size())
                    .isEmpty();
        }

        @Test
        void shouldDeclareAllFourAncestorIds_whenSubtaskUpdateIsInspected() throws Exception {
            // arrange
            JsonNode document = fetchDocument();
            var path = "/boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks/{subtaskId}";

            // act: a literal oracle, independent of this class's own template regex
            JsonNode pathItem = document.path("paths").path(path);
            JsonNode operation = pathItem.path("put");
            var declaredPathNames = new TreeSet<String>();
            for (JsonNode parameter : declaredParameters(pathItem, operation)) {
                if ("path".equals(parameter.path("in").asText())) {
                    declaredPathNames.add(parameter.path("name").asText());
                }
            }

            // assert
            Assertions.assertThat(operation.isMissingNode())
                    .as("PUT %s is documented", path)
                    .isFalse();
            Assertions.assertThat(declaredPathNames)
                    .containsExactlyInAnyOrder("boardId", "columnId", "taskId", "subtaskId");
        }
    }

    @Nested
    class SessionDerivedUserId {

        @Test
        void shouldPublishNoUserIdQueryParameter_whenEveryOperationIsInspected() throws Exception {
            // arrange
            JsonNode document = fetchDocument();
            JsonNode paths = document.path("paths");
            var failures = new ArrayList<String>();
            var operationCount = 0;

            // act
            var pathNames = paths.fieldNames();
            while (pathNames.hasNext()) {
                var pathName = pathNames.next();
                JsonNode pathItem = paths.path(pathName);
                var methodNames = pathItem.fieldNames();
                while (methodNames.hasNext()) {
                    var methodName = methodNames.next();
                    if (!HTTP_METHOD_FIELD_NAMES.contains(methodName)) {
                        continue;
                    }
                    operationCount++;
                    JsonNode operation = pathItem.path(methodName);
                    for (JsonNode parameter : declaredParameters(pathItem, operation)) {
                        var leaksUserId =
                                SESSION_DERIVED_PARAMETER_NAME.equals(
                                                parameter.path("name").asText())
                                        && "query".equals(parameter.path("in").asText());
                        if (leaksUserId) {
                            failures.add(
                                    methodName.toUpperCase(Locale.ROOT)
                                            + " "
                                            + pathName
                                            + " -> publishes a userId query parameter");
                            break;
                        }
                    }
                }
            }

            // assert: report every violation in one run, not just the first
            Assertions.assertThat(operationCount)
                    .as("operations walked, so an empty `paths` object cannot pass")
                    .isGreaterThanOrEqualTo(MINIMUM_OPERATION_COUNT);
            Assertions.assertThat(failures)
                    .as("%d operations publish the session-derived userId", failures.size())
                    .isEmpty();
        }
    }
}
