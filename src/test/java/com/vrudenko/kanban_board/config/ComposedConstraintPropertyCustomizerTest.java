package com.vrudenko.kanban_board.config;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.annotation.BoardName;
import com.vrudenko.kanban_board.dto.annotation.DisplayName;
import com.vrudenko.kanban_board.dto.annotation.OptionalNotBlank;
import com.vrudenko.kanban_board.dto.annotation.Password;
import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Pattern;
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
 * Regression guard for {@link ComposedConstraintPropertyCustomizer}: proves that constraints
 * arriving through a composed custom validation annotation actually reach the generated OpenAPI
 * document, and that the merge never loosens a value swagger-core already published from a direct
 * annotation.
 *
 * <p>Extends {@link AbstractPostgresContainerTest} rather than {@code AbstractAppTest} or {@code
 * AbstractAppMockMvcTest} -- these assertions read a generated document and need no users, boards,
 * or session cookies (docs/CODE_STYLE.md rule 4); {@link OpenApiDocsTest} and {@link
 * ProblemDetailOpenApiCustomizerTest} are the same-shaped precedents. {@code MockMvc} does not
 * apply {@code server.servlet.context-path}, so {@link #fetchDocument()} requests the bare {@code
 * springdoc.api-docs.path} ({@code /docs}), never {@code /api/docs}. Never autowires the {@code
 * OpenAPI} bean directly -- springdoc caches the built document, and a test holding the live
 * instance would share mutable state with every later assertion in this Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComposedConstraintPropertyCustomizerTest extends AbstractPostgresContainerTest {

    @Autowired private MockMvc mockMvc;

    @Value("${springdoc.api-docs.path}")
    private String apiDocsPath;

    JsonNode fetchDocument() throws Exception {
        var response = mockMvc.perform(get(apiDocsPath)).andReturn();
        var body = response.getResponse().getContentAsString();
        var objectMapper = new ObjectMapper();
        return objectMapper.readTree(body);
    }

    /**
     * Reads the {@code @Pattern} meta-annotation's own {@code regexp()} off a composed constraint
     * annotation type -- never a hand-copied literal, so a future regex edit on the annotation
     * cannot silently drift out of sync with what this test expects.
     */
    private String metaPatternOf(Class<? extends Annotation> annotationType) {
        var pattern = annotationType.getAnnotation(Pattern.class);
        Assertions.assertThat(pattern)
                .as("expected a @Pattern meta-annotation on " + annotationType.getSimpleName())
                .isNotNull();
        return pattern.regexp();
    }

    private JsonNode propertyNode(JsonNode document, String schema, String property) {
        return document.path("components")
                .path("schemas")
                .path(schema)
                .path("properties")
                .path(property);
    }

    private Integer readInt(JsonNode propertyNode, String field) {
        var node = propertyNode.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private String readText(JsonNode propertyNode, String field) {
        var node = propertyNode.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    @Nested
    class PublishedConstraints {

        @Test
        void shouldPublishAtLeastEightPatternKeys_whenDocumentIsGenerated() throws Exception {
            // arrange
            var document = fetchDocument();
            var schemas = document.path("components").path("schemas");
            var patternCount = 0;

            // act: the live document has exactly 0 pattern keys before this bean -- a non-zero
            // count is therefore solely attributable to ComposedConstraintPropertyCustomizer
            var schemaNames = schemas.fieldNames();
            while (schemaNames.hasNext()) {
                var properties = schemas.path(schemaNames.next()).path("properties");
                var propertyNames = properties.fieldNames();
                while (propertyNames.hasNext()) {
                    if (properties.path(propertyNames.next()).has("pattern")) {
                        patternCount++;
                    }
                }
            }

            // assert
            Assertions.assertThat(patternCount).isGreaterThanOrEqualTo(8);
        }

        @Test
        void shouldPublishExactPattern_whenFieldCarriesExactlyOneComposedPattern()
                throws Exception {
            // arrange
            var document = fetchDocument();
            record Row(String schema, String property, String expectedPattern) {}
            var rows =
                    List.of(
                            new Row("SaveBoardRequestDTO", "name", metaPatternOf(BoardName.class)),
                            new Row(
                                    "SaveColumnRequestDTO",
                                    "color",
                                    ValidationConstants.COLUMN_COLOR_PATTERN),
                            new Row(
                                    "UpdateTaskRequestDTO",
                                    "title",
                                    metaPatternOf(OptionalNotBlank.class)),
                            new Row(
                                    "UpdateSubtaskRequestDTO",
                                    "title",
                                    metaPatternOf(OptionalNotBlank.class)),
                            new Row("SignupRequestDTO", "password", metaPatternOf(Password.class)),
                            new Row("SigninRequestDTO", "password", metaPatternOf(Password.class)));
            var failures = new ArrayList<String>();

            // act
            for (var row : rows) {
                var actual =
                        readText(propertyNode(document, row.schema(), row.property()), "pattern");
                if (!row.expectedPattern().equals(actual)) {
                    failures.add(
                            row.schema()
                                    + "."
                                    + row.property()
                                    + " -> expected pattern '"
                                    + row.expectedPattern()
                                    + "', got '"
                                    + actual
                                    + "'");
                }
            }

            // assert
            Assertions.assertThat(failures).isEmpty();
        }

        @Test
        void shouldPublishNonEmptyConjunctionPattern_whenFieldCarriesTwoComposedPatterns()
                throws Exception {
            // arrange
            var document = fetchDocument();
            record Row(String schema, String property, String constituentA, String constituentB) {}
            var rows =
                    List.of(
                            new Row(
                                    "UpdateBoardRequestDTO",
                                    "name",
                                    metaPatternOf(BoardName.class),
                                    metaPatternOf(OptionalNotBlank.class)),
                            new Row(
                                    "SignupRequestDTO",
                                    "displayName",
                                    metaPatternOf(DisplayName.class),
                                    metaPatternOf(OptionalNotBlank.class)));
            var failures = new ArrayList<String>();

            // act: the conjunction must be non-blank and must not degrade into either single
            // constituent regex alone -- that is exactly the failure Task 2's "   " equivalence
            // case is built to catch behaviorally; this is the document-level presence check
            for (var row : rows) {
                var actual =
                        readText(propertyNode(document, row.schema(), row.property()), "pattern");
                if (actual == null
                        || actual.isBlank()
                        || actual.equals(row.constituentA())
                        || actual.equals(row.constituentB())) {
                    failures.add(
                            row.schema()
                                    + "."
                                    + row.property()
                                    + " -> expected a two-regex conjunction, got '"
                                    + actual
                                    + "'");
                }
            }

            // assert
            Assertions.assertThat(failures).isEmpty();
        }

        @Test
        void shouldLeavePatternAbsent_whenNoComposedOrDirectPatternExists() throws Exception {
            // arrange
            var document = fetchDocument();
            record Row(String schema, String property) {}
            var rows =
                    List.of(
                            new Row("SaveColumnRequestDTO", "name"),
                            new Row("SaveTaskRequestDTO", "title"),
                            new Row("SaveTaskRequestDTO", "description"),
                            new Row("UpdateTaskRequestDTO", "description"),
                            new Row("SaveSubtaskRequestDTO", "title"),
                            new Row("SignupRequestDTO", "email"),
                            new Row("SigninRequestDTO", "email"));
            var failures = new ArrayList<String>();

            // act
            for (var row : rows) {
                var actual =
                        readText(propertyNode(document, row.schema(), row.property()), "pattern");
                if (actual != null) {
                    failures.add(
                            row.schema()
                                    + "."
                                    + row.property()
                                    + " -> expected no pattern, got '"
                                    + actual
                                    + "'");
                }
            }

            // assert
            Assertions.assertThat(failures).isEmpty();
        }

        @Test
        void shouldPublishMostRestrictiveLength_whenComposedOrDirectAnnotationsPresent()
                throws Exception {
            // arrange
            var document = fetchDocument();
            record Row(String schema, String property, Integer minLength, Integer maxLength) {}
            var rows =
                    List.of(
                            new Row(
                                    "SaveBoardRequestDTO",
                                    "name",
                                    ValidationConstants.MIN_BOARD_NAME_LENGTH,
                                    ValidationConstants.MAX_BOARD_NAME_LENGTH),
                            new Row(
                                    "UpdateBoardRequestDTO",
                                    "name",
                                    ValidationConstants.MIN_BOARD_NAME_LENGTH,
                                    ValidationConstants.MAX_BOARD_NAME_LENGTH),
                            new Row("SaveColumnRequestDTO", "color", null, null),
                            new Row(
                                    "SaveColumnRequestDTO",
                                    "name",
                                    ValidationConstants.MIN_COLUMN_NAME_LENGTH,
                                    ValidationConstants.MAX_COLUMN_NAME_LENGTH),
                            new Row(
                                    "SaveTaskRequestDTO",
                                    "title",
                                    ValidationConstants.MIN_TASK_TITLE_LENGTH,
                                    ValidationConstants.MAX_TASK_TITLE_LENGTH),
                            new Row(
                                    "SaveTaskRequestDTO",
                                    "description",
                                    ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                                    ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH),
                            new Row(
                                    "UpdateTaskRequestDTO",
                                    "title",
                                    ValidationConstants.MIN_TASK_TITLE_LENGTH,
                                    ValidationConstants.MAX_TASK_TITLE_LENGTH),
                            new Row(
                                    "UpdateTaskRequestDTO",
                                    "description",
                                    ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                                    ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH),
                            new Row(
                                    "SaveSubtaskRequestDTO",
                                    "title",
                                    ValidationConstants.MIN_SUBTASK_TITLE_LENGTH,
                                    ValidationConstants.MAX_SUBTASK_TITLE_LENGTH),
                            new Row(
                                    "UpdateSubtaskRequestDTO",
                                    "title",
                                    ValidationConstants.MIN_SUBTASK_TITLE_LENGTH,
                                    ValidationConstants.MAX_SUBTASK_TITLE_LENGTH),
                            new Row(
                                    "SignupRequestDTO",
                                    "displayName",
                                    ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH,
                                    ValidationConstants.MAX_USER_DISPLAY_NAME_LENGTH),
                            new Row("SignupRequestDTO", "email", 1, null),
                            new Row("SigninRequestDTO", "email", 1, null),
                            new Row(
                                    "SignupRequestDTO",
                                    "password",
                                    ValidationConstants.MIN_PASSWORD_LENGTH,
                                    ValidationConstants.MAX_PASSWORD_LENGTH),
                            new Row(
                                    "SigninRequestDTO",
                                    "password",
                                    ValidationConstants.MIN_PASSWORD_LENGTH,
                                    ValidationConstants.MAX_PASSWORD_LENGTH));
            var failures = new ArrayList<String>();

            // act
            for (var row : rows) {
                var node = propertyNode(document, row.schema(), row.property());
                var actualMin = readInt(node, "minLength");
                var actualMax = readInt(node, "maxLength");
                if (!java.util.Objects.equals(row.minLength(), actualMin)
                        || !java.util.Objects.equals(row.maxLength(), actualMax)) {
                    failures.add(
                            row.schema()
                                    + "."
                                    + row.property()
                                    + " -> expected minLength="
                                    + row.minLength()
                                    + " maxLength="
                                    + row.maxLength()
                                    + ", got minLength="
                                    + actualMin
                                    + " maxLength="
                                    + actualMax);
                }
            }

            // assert
            Assertions.assertThat(failures).isEmpty();
        }

        @Test
        void shouldRaiseMinLengthToThree_whenSaveSubtaskTitleComposesNotBlankAndSubtaskTitle()
                throws Exception {
            // arrange: F1 -- the live document today publishes minLength 1 (from the direct
            // @NotBlank) while @SubtaskTitle enforces @Size(min = 3), a documented-looser-than-
            // enforced defect this bean fixes as a side effect
            var document = fetchDocument();

            // act
            var minLength =
                    readInt(propertyNode(document, "SaveSubtaskRequestDTO", "title"), "minLength");

            // assert
            Assertions.assertThat(minLength)
                    .isEqualTo(ValidationConstants.MIN_SUBTASK_TITLE_LENGTH);
            Assertions.assertThat(minLength).isNotEqualTo(1);
        }

        @Test
        void shouldPublishEmailFormat_whenComposedAppEmailPresent() throws Exception {
            // arrange
            var document = fetchDocument();

            // act
            var signupFormat =
                    readText(propertyNode(document, "SignupRequestDTO", "email"), "format");
            var signinFormat =
                    readText(propertyNode(document, "SigninRequestDTO", "email"), "format");

            // assert
            Assertions.assertThat(signupFormat).isEqualTo("email");
            Assertions.assertThat(signinFormat).isEqualTo("email");
        }
    }
}
