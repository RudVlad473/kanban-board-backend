package com.vrudenko.kanban_board.config;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.annotation.BoardName;
import com.vrudenko.kanban_board.dto.annotation.DisplayName;
import com.vrudenko.kanban_board.dto.annotation.OptionalNotBlank;
import com.vrudenko.kanban_board.dto.annotation.Password;
import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @Autowired private Validator validator;

    @Value("${springdoc.api-docs.path}")
    private String apiDocsPath;

    JsonNode fetchDocument() throws Exception {
        // Asserts 200 explicitly (secondary finding, quick task 260904-ss1 round 4, 2026-09-05):
        // without this, a 500 (or any non-2xx) response body still parses as JSON (an empty
        // object, or a ProblemDetail error body) and every absence-asserting test in this class
        // (e.g. shouldLeavePatternAbsent_...) would pass VACUOUSLY against it instead of failing.
        var response = mockMvc.perform(get(apiDocsPath)).andExpect(status().isOk()).andReturn();
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

    /**
     * D1 (quick task 260904-ss1 round 4, 2026-09-05). The published pattern for an annotation whose
     * {@code flags()} is non-empty (today, only {@link OptionalNotBlank}'s {@code DOTALL}) is no
     * longer {@code regexp()} verbatim -- {@code ComposedConstraintPropertyCustomizer} translates
     * it to an ECMA-262-safe equivalent first. Deriving the EXPECTED value from that same
     * production translation method (rather than a hand-copied literal) keeps this assertion honest
     * against a future edit to either the annotation's regex or the translation logic itself,
     * mirroring {@link #metaPatternOf(Class)}'s "never hand-copy" property for the one annotation
     * that now needs more than its raw {@code regexp()}.
     */
    private String ecmaTranslatedPatternOf(Class<? extends Annotation> annotationType) {
        var pattern = annotationType.getAnnotation(Pattern.class);
        Assertions.assertThat(pattern)
                .as("expected a @Pattern meta-annotation on " + annotationType.getSimpleName())
                .isNotNull();
        return ComposedConstraintPropertyCustomizer.ecmaEquivalentOf(
                        pattern.regexp(), pattern.flags())
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "expected a translatable pattern on "
                                                + annotationType.getSimpleName()));
    }

    /**
     * Reads the {@code io.swagger.v3.oas.annotations.media.Schema} meta-annotation itself off a
     * composed constraint annotation type, mirroring {@link #metaPatternOf(Class)} -- callers read
     * {@code .description()}/{@code .example()} off the returned annotation rather than a
     * hand-copied literal, so a future edit to the annotation cannot silently drift out of sync
     * with what a test expects (secondary finding, quick task 260904-ss1 round 4, 2026-09-05:
     * {@code collectSchemaMeta}'s {@code description} branch was mutation-blind before this helper
     * existed -- deleting it kept every test in this class green).
     */
    private io.swagger.v3.oas.annotations.media.Schema metaSchemaOf(
            Class<? extends Annotation> annotationType) {
        var schema = annotationType.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        Assertions.assertThat(schema)
                .as("expected an @Schema meta-annotation on " + annotationType.getSimpleName())
                .isNotNull();
        return schema;
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

    /**
     * Evaluates a candidate value against ONE property's own published {@code pattern}/{@code
     * minLength}/{@code maxLength}, shared by {@link EquivalenceWithRealValidator} (checking a
     * hand-picked sample) and {@link ExampleInvariant} (checking every published {@code example}
     * against its own property). {@code pattern} is evaluated with {@code find()}, not {@code
     * matches()} -- JSON Schema {@code pattern} is a SEARCH, so {@code find()} is the semantics a
     * spec-compliant validator implements, and testing {@code matches()} here would over-constrain
     * these equivalence checks.
     *
     * <p>That is deliberately NOT the whole story, and {@code
     * shouldAcceptValidValueUnderFullMatch_whenPatternIsAMultiRegexConjunction} is the counterpart
     * that covers the rest: a generated client may full-match the published pattern instead, which
     * is a weaker guarantee than the spec but a real consumer behavior. Search semantics belong
     * here (they are what correctness means); full-match belongs there (it is what portability
     * means). Neither test subsumes the other.
     */
    private boolean valueSatisfiesPublishedConstraints(JsonNode propertyNode, String value) {
        var minLength = readInt(propertyNode, "minLength");
        var maxLength = readInt(propertyNode, "maxLength");
        if (minLength != null && value.length() < minLength) {
            return false;
        }
        if (maxLength != null && value.length() > maxLength) {
            return false;
        }
        var pattern = readText(propertyNode, "pattern");
        return pattern == null || java.util.regex.Pattern.compile(pattern).matcher(value).find();
    }

    /**
     * The full-match counterpart {@link #valueSatisfiesPublishedConstraints} deliberately does not
     * cover -- length bounds are irrelevant here on purpose (a generated client's own full-match
     * check is only ever against {@code pattern}, never against JSON Schema's separate {@code
     * minLength}/{@code maxLength} keywords).
     */
    private boolean valueSatisfiesPublishedPatternUnderFullMatch(
            JsonNode propertyNode, String value) {
        var pattern = readText(propertyNode, "pattern");
        return pattern == null || java.util.regex.Pattern.compile(pattern).matcher(value).matches();
    }

    /**
     * Shared by {@link EquivalenceWithRealValidator} and the D1 regression test below -- whether
     * the real {@link Validator} accepts {@code dto} as far as {@code propertyName} is concerned (a
     * violation on a DIFFERENT property of the same DTO does not count against it).
     */
    private boolean realValidatorAccepts(Object dto, String propertyName) {
        Set<ConstraintViolation<Object>> violations = validator.validate(dto);
        return violations.stream()
                .noneMatch(
                        violation -> violation.getPropertyPath().toString().equals(propertyName));
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
                                    ecmaTranslatedPatternOf(OptionalNotBlank.class)),
                            new Row(
                                    "UpdateSubtaskRequestDTO",
                                    "title",
                                    ecmaTranslatedPatternOf(OptionalNotBlank.class)),
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

        /**
         * A multi-regex conjunction must accept a valid value under FULL-MATCH evaluation, not only
         * under the unanchored search JSON Schema specifies.
         *
         * <p>Spec-compliant validators search, so an all-lookahead conjunction satisfies them and
         * every other test here passes with one. Generated clients are the consumers that break: a
         * generator emitting {@code Pattern.matches} / {@code re.fullmatch}, or wrapping the
         * published pattern in its own anchors, evaluates a zero-width expression against a
         * non-empty value and rejects it -- client-side, before any request is sent. Pinning the
         * full-match reading is what forces the conjunction's last term to consume rather than
         * assert.
         */
        @Test
        void shouldAcceptValidValueUnderFullMatch_whenPatternIsAMultiRegexConjunction()
                throws Exception {
            // arrange
            var document = fetchDocument();
            record Row(String schema, String property, String valid, String invalid) {}
            var rows =
                    List.of(
                            new Row("UpdateBoardRequestDTO", "name", "Platform Launch", "   "),
                            new Row("SignupRequestDTO", "displayName", "Ada Lovelace", "   "));
            var failures = new ArrayList<String>();

            // act
            for (var row : rows) {
                var published =
                        readText(propertyNode(document, row.schema(), row.property()), "pattern");
                var compiled = java.util.regex.Pattern.compile(published);
                if (!compiled.matcher(row.valid()).matches()) {
                    failures.add(
                            row.schema()
                                    + "."
                                    + row.property()
                                    + " -> published pattern '"
                                    + published
                                    + "' REJECTS the valid value '"
                                    + row.valid()
                                    + "' under full-match evaluation");
                }
                if (compiled.matcher(row.invalid()).matches()) {
                    failures.add(
                            row.schema()
                                    + "."
                                    + row.property()
                                    + " -> published pattern '"
                                    + published
                                    + "' ACCEPTS the invalid value '"
                                    + row.invalid()
                                    + "' under full-match evaluation");
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

        /**
         * D1 (quick task 260904-ss1 round 4, 2026-09-05). Before the fix, {@code
         * ComposedConstraintPropertyCustomizer.contribute()} republished {@link OptionalNotBlank}'s
         * {@code regexp()} verbatim and silently dropped {@code flags() = {DOTALL}} -- so the
         * published pattern's {@code .} never matched a newline, and its {@code \S} was read as
         * ECMA-262's Unicode-aware version rather than Java's ASCII-only one. Both proven live to
         * make the published pattern REJECT a value the real {@link Validator} ACCEPTS: a
         * multi-line title, and a value made solely of {@code U+00A0} non-breaking spaces (Java's
         * {@code \s} is ASCII-only, so {@code \S} matches {@code U+00A0}; ECMA's {@code \s} is
         * Unicode-aware and includes it, so ECMA's {@code \S} does not).
         *
         * <p>Evaluated here with Java's regex engine against the FIXED, translated pattern, not a
         * live ECMA-262 engine -- no JS engine is wired into this build or its test classpath (no
         * GraalJS/Nashorn dependency; confirmed absent by attempting {@code
         * ScriptEngineManager().getEngineByName("nashorn")}, which returns {@code null} on this
         * JDK). That substitution is sound here specifically BECAUSE of how the fix works: {@code
         * ComposedConstraintPropertyCustomizer#ecmaEquivalentOf} rewrites every {@code \s}/{@code
         * \S} shorthand into an explicit ASCII character class before publishing, so the published
         * string this test reads back contains NO construct whose meaning differs between the two
         * dialects -- the one thing that could make Java's engine disagree with ECMA's has been
         * engineered out of the string itself. Independently confirmed against a REAL ECMA-262
         * engine during development (Node.js v24.19.0, 2026-09-05, not part of this build/CI):
         * {@code /^(?:[\s\S]*[^ \t\n\x0B\f\r][\s\S]*)$/.test("a\nb")} → {@code true}, the same
         * full-match test against three {@code U+00A0} characters → {@code true} -- both agreeing
         * with the assertions below and with a scratch {@code Pattern.compile(".*\\S.*",
         * Pattern.DOTALL).matcher(value).matches()} run against the real annotation's own
         * regex/flags.
         */
        @Test
        void shouldMatchRealValidatorOnMultilineAndNbspOnlyValues_whenPatternIsOptionalNotBlank()
                throws Exception {
            // arrange
            var document = fetchDocument();
            var multiline = "a\nb";
            var nbspOnly = "\u00A0\u00A0\u00A0";
            var node = propertyNode(document, "UpdateTaskRequestDTO", "title");

            // act
            var documentAcceptsMultiline =
                    valueSatisfiesPublishedPatternUnderFullMatch(node, multiline);
            var documentAcceptsNbsp = valueSatisfiesPublishedPatternUnderFullMatch(node, nbspOnly);
            var validatorAcceptsMultiline =
                    realValidatorAccepts(
                            UpdateTaskRequestDTO.builder().title(multiline).build(), "title");
            var validatorAcceptsNbsp =
                    realValidatorAccepts(
                            UpdateTaskRequestDTO.builder().title(nbspOnly).build(), "title");

            // assert: both the real validator's own verdict (proving the fixture values are the
            // ones the defect report described) and the document/validator agreement (the actual
            // regression guard)
            Assertions.assertThat(validatorAcceptsMultiline)
                    .as("real validator: multiline")
                    .isTrue();
            Assertions.assertThat(validatorAcceptsNbsp).as("real validator: NBSP-only").isTrue();
            Assertions.assertThat(documentAcceptsMultiline)
                    .as("published pattern vs. real validator: multiline")
                    .isEqualTo(validatorAcceptsMultiline);
            Assertions.assertThat(documentAcceptsNbsp)
                    .as("published pattern vs. real validator: NBSP-only")
                    .isEqualTo(validatorAcceptsNbsp);
        }

        /**
         * D3 decision record (quick task 260904-ss1 round 4, 2026-09-05) -- NOT a fix, a pinned
         * observation. {@code @Size} counts UTF-16 code units; published {@code maxLength} counts
         * Unicode code points. A value built from astral characters can satisfy the published
         * {@code maxLength} while violating the real {@code @Size(max = ...)} it was computed from
         * (see {@code contribute()}'s {@code Size} branch for the full analysis and the decision
         * this pins). This test exists so a future change to that decision is deliberate: if it
         * starts failing, the maxLength-computation strategy changed and this comment block needs
         * updating to match, not silently deleting.
         */
        @Test
        void shouldAcceptAnAstralHeavyValueThatViolatesTheRealSizeMax_perD3Decision()
                throws Exception {
            // arrange
            var document = fetchDocument();
            var seventeenEmoji = "😀".repeat(17);

            // act: a spec-compliant JSON Schema validator counts CODE POINTS against maxLength,
            // never Java's UTF-16-unit String.length() -- deliberately NOT routed through
            // valueSatisfiesPublishedConstraints, which uses value.length() and would therefore
            // conflate the very two units this decision record is about
            var codePointCount = seventeenEmoji.codePointCount(0, seventeenEmoji.length());
            var utf16UnitCount = seventeenEmoji.length();
            var publishedMaxLength =
                    readInt(propertyNode(document, "SaveSubtaskRequestDTO", "title"), "maxLength");
            var documentAcceptsByCodePointCount = codePointCount <= publishedMaxLength;
            var validatorAccepts =
                    realValidatorAccepts(
                            SaveSubtaskRequestDTO.builder().title(seventeenEmoji).build(), "title");

            // assert: the divergence this decision record pins -- 17 code points (<= max=32,
            // published check ACCEPTS) but 34 UTF-16 units (> max=32, real validator REJECTS)
            Assertions.assertThat(codePointCount).isEqualTo(17);
            Assertions.assertThat(utf16UnitCount).isEqualTo(34);
            Assertions.assertThat(publishedMaxLength)
                    .isEqualTo(ValidationConstants.MAX_SUBTASK_TITLE_LENGTH);
            Assertions.assertThat(documentAcceptsByCodePointCount)
                    .as("published maxLength, evaluated by code-point count")
                    .isTrue();
            Assertions.assertThat(validatorAccepts).as("real @Size (UTF-16 units)").isFalse();
        }
    }

    /**
     * Proves the published document and the real {@link Validator} reach the SAME accept/reject
     * verdict for the fields listed in {@code cases} below -- the actual correctness contract;
     * {@link PublishedConstraints} only proves values are present, not that they mean the same
     * thing the enforcer means. Not literally "every field" this bean touches: {@code email} on
     * {@code SignupRequestDTO}/{@code SigninRequestDTO} carries no case here, and could not
     * meaningfully carry one -- this class's own {@link #valueSatisfiesPublishedConstraints} never
     * reads the published {@code format} keyword (it checks only {@code pattern}/{@code
     * minLength}/{@code maxLength}), so an equivalence case against {@code email} would compare the
     * real {@code @Email} validator against a document-side check that structurally cannot see the
     * one thing that field publishes (corrected, secondary finding, quick task 260904-ss1 round 4,
     * 2026-09-05 -- this Javadoc previously claimed "every field", which was never true).
     *
     * <p>The published {@code pattern} is evaluated here with Java's regex engine, not ECMA-262.
     * That is exact for every {@code pattern} construct currently in use in the {@code cases} below
     * (character classes, anchors, lookaheads are common to both dialects) with ONE documented
     * exception: Java's {@code $} also matches immediately before a final line terminator (a
     * trailing {@code \n}), while ECMA-262's {@code $} (without the {@code m} flag) matches only
     * true end-of-input -- so a value ending in {@code "\n"} could read as accepted under Java's
     * engine and rejected under a real ECMA-262 one for a pattern anchored with {@code $}. No case
     * below exercises a trailing-newline value, so this divergence is latent here, not exercised; a
     * future case that adds one must not rely on this test's Java-engine evaluation alone
     * (corrected, secondary finding, 2026-09-05 -- this Javadoc previously claimed Java's
     * evaluation was "exact for every construct currently in use" with no exception, which was not
     * true).
     */
    @Nested
    class EquivalenceWithRealValidator {

        private record Case(String schemaName, String propertyName, String value, Object dto) {}

        @Test
        void shouldMatchRealValidatorVerdict_whenPublishedConstraintsAreEvaluated()
                throws Exception {
            // arrange
            var document = fetchDocument();
            var overlongDescription =
                    "a".repeat(ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH + 1);
            var cases =
                    List.of(
                            new Case(
                                    "SaveColumnRequestDTO",
                                    "color",
                                    "#1AB2C3",
                                    SaveColumnRequestDTO.builder().color("#1AB2C3").build()),
                            new Case(
                                    "SaveColumnRequestDTO",
                                    "color",
                                    "1AB2C3",
                                    SaveColumnRequestDTO.builder().color("1AB2C3").build()),
                            new Case(
                                    "UpdateBoardRequestDTO",
                                    "name",
                                    "Platform Launch",
                                    UpdateBoardRequestDTO.builder()
                                            .name("Platform Launch")
                                            .build()),
                            new Case(
                                    "UpdateBoardRequestDTO",
                                    "name",
                                    "   ",
                                    UpdateBoardRequestDTO.builder().name("   ").build()),
                            new Case(
                                    "SignupRequestDTO",
                                    "displayName",
                                    "Ada Lovelace",
                                    SignupRequestDTO.builder().displayName("Ada Lovelace").build()),
                            new Case(
                                    "SignupRequestDTO",
                                    "displayName",
                                    "   ",
                                    SignupRequestDTO.builder().displayName("   ").build()),
                            new Case(
                                    "SignupRequestDTO",
                                    "password",
                                    "Sup3r$ecret",
                                    SignupRequestDTO.builder().password("Sup3r$ecret").build()),
                            new Case(
                                    "SignupRequestDTO",
                                    "password",
                                    "Sup$ercret",
                                    SignupRequestDTO.builder().password("Sup$ercret").build()),
                            new Case(
                                    "SaveSubtaskRequestDTO",
                                    "title",
                                    "abc",
                                    SaveSubtaskRequestDTO.builder().title("abc").build()),
                            new Case(
                                    "SaveSubtaskRequestDTO",
                                    "title",
                                    "ab",
                                    SaveSubtaskRequestDTO.builder().title("ab").build()),
                            new Case(
                                    "UpdateTaskRequestDTO",
                                    "title",
                                    "Refactor",
                                    UpdateTaskRequestDTO.builder().title("Refactor").build()),
                            new Case(
                                    "UpdateTaskRequestDTO",
                                    "title",
                                    "   ",
                                    UpdateTaskRequestDTO.builder().title("   ").build()),
                            new Case(
                                    "SaveTaskRequestDTO",
                                    "description",
                                    "A short description",
                                    SaveTaskRequestDTO.builder()
                                            .description("A short description")
                                            .build()),
                            new Case(
                                    "SaveTaskRequestDTO",
                                    "description",
                                    overlongDescription,
                                    SaveTaskRequestDTO.builder()
                                            .description(overlongDescription)
                                            .build()));
            var failures = new ArrayList<String>();

            // act
            for (var testCase : cases) {
                var validatorAccepts = validatorAccepts(testCase.dto(), testCase.propertyName());
                var documentAccepts =
                        documentAccepts(
                                document,
                                testCase.schemaName(),
                                testCase.propertyName(),
                                testCase.value());
                if (validatorAccepts != documentAccepts) {
                    failures.add(
                            testCase.schemaName()
                                    + "."
                                    + testCase.propertyName()
                                    + "='"
                                    + testCase.value()
                                    + "' -> validator accepts="
                                    + validatorAccepts
                                    + ", document accepts="
                                    + documentAccepts);
                }
            }

            // assert
            Assertions.assertThat(failures).isEmpty();
        }

        private boolean validatorAccepts(Object dto, String propertyName) {
            return realValidatorAccepts(dto, propertyName);
        }

        private boolean documentAccepts(
                JsonNode document, String schemaName, String propertyName, String value) {
            return valueSatisfiesPublishedConstraints(
                    propertyNode(document, schemaName, propertyName), value);
        }
    }

    /**
     * Proves every published {@code example}, anywhere in the document, satisfies that same
     * property's own published {@code pattern}/{@code minLength}/{@code maxLength} -- an example
     * that fails its own constraint is worse than none, since a client copying it straight into a
     * request gets a 400. Walks EVERY schema/property rather than a checked-in list, so a future
     * annotation gaining an example is covered with no edit to this test.
     */
    @Nested
    class ExampleInvariant {

        @Test
        void shouldSatisfyOwnConstraints_whenExamplePublishedAnywhereInDocument() throws Exception {
            // arrange
            var document = fetchDocument();
            var schemas = document.path("components").path("schemas");
            var failures = new ArrayList<String>();
            var exampleCount = 0;

            // act
            var schemaNames = schemas.fieldNames();
            while (schemaNames.hasNext()) {
                var schemaName = schemaNames.next();
                var properties = schemas.path(schemaName).path("properties");
                var propertyNames = properties.fieldNames();
                while (propertyNames.hasNext()) {
                    var propertyName = propertyNames.next();
                    var node = properties.path(propertyName);
                    if (!node.has("example")) {
                        continue;
                    }
                    exampleCount++;
                    var example = node.path("example").asText();
                    if (!valueSatisfiesPublishedConstraints(node, example)) {
                        failures.add(
                                schemaName
                                        + "."
                                        + propertyName
                                        + " -> example '"
                                        + example
                                        + "' does not satisfy its own published constraints");
                    }
                }
            }

            // assert: the non-vacuity guard first -- a document with zero examples would otherwise
            // satisfy the loop trivially and read as green
            Assertions.assertThat(exampleCount).isGreaterThanOrEqualTo(3);
            Assertions.assertThat(failures).isEmpty();
        }
    }

    /**
     * D4 (quick task 260904-ss1 round 4, 2026-09-05). {@link Password}'s {@code @Schema}
     * description used to include an internal build-tooling rationale verbatim ("a password-shaped
     * literal in source is exactly what this repository's gitleaks pre-commit scan looks for"),
     * disclosing this repo's secret-scanning setup to every API consumer. Truncated at "...one
     * special character." with the rationale moved to a plain {@code //} comment above the
     * annotation, where only this codebase's own contributors read it.
     *
     * <p>Reading {@code expected} off {@link #metaSchemaOf(Class)} rather than a hand-copied
     * literal also closes the mutation-blind gap the secondary findings flagged: {@code
     * collectSchemaMeta}'s {@code description} branch used to be deletable with every test in this
     * class still green, because nothing compared the published value against the annotation's own
     * -- this test would now fail if that branch were removed, since {@code expected} would be
     * non-null while the published description reverted to whatever the schema already had (null,
     * for these two properties).
     */
    @Nested
    class PublishedDescriptions {

        @Test
        void shouldNotDiscloseGitleaksScanningSetup_inPasswordDescription() throws Exception {
            // arrange
            var document = fetchDocument();
            var expected = metaSchemaOf(Password.class).description();

            // act
            var signupDescription =
                    readText(propertyNode(document, "SignupRequestDTO", "password"), "description");
            var signinDescription =
                    readText(propertyNode(document, "SigninRequestDTO", "password"), "description");

            // assert
            Assertions.assertThat(signupDescription).isEqualTo(expected);
            Assertions.assertThat(signinDescription).isEqualTo(expected);
            Assertions.assertThat(signupDescription).doesNotContainIgnoringCase("gitleaks");
            Assertions.assertThat(signinDescription).doesNotContainIgnoringCase("gitleaks");
        }
    }

    /**
     * D2 (quick task 260904-ss1 round 4, 2026-09-05). Regression guard for {@link
     * ComposedConstraintPropertyCustomizer.Accumulator#reassertOn}'s tighten-only contract,
     * exercised directly through this bean's two public interface methods with a hand-built {@link
     * AnnotatedType}/{@link OpenAPI} -- no {@code @SpringBootTest} context needed, since the whole
     * point is precise control over what sits on the schema BETWEEN phase 1 ({@link
     * ComposedConstraintPropertyCustomizer#customize}) and phase 2 ({@link
     * ComposedConstraintPropertyCustomizer#customise}), which the full document-generation pipeline
     * does not offer a seam for.
     */
    @Nested
    class ReassertOnTightenOnly {

        private AnnotatedType annotatedTypeFor(
                Class<?> dtoClass, String fieldName, Schema<?> parentSchema)
                throws NoSuchFieldException {
            var field = dtoClass.getDeclaredField(fieldName);
            return new AnnotatedType()
                    .parent(parentSchema)
                    .propertyName(fieldName)
                    .ctxAnnotations(field.getAnnotations());
        }

        private OpenAPI documentWith(
                String schemaName, String propertyName, Schema<?> propertySchema) {
            var docSchema = new Schema<>();
            var properties = new LinkedHashMap<String, Schema>();
            properties.put(propertyName, propertySchema);
            docSchema.setProperties(properties);
            var components = new Components();
            components.addSchemas(schemaName, docSchema);
            var openApi = new OpenAPI();
            openApi.setComponents(components);
            return openApi;
        }

        @Test
        void shouldRestoreTheComputedValue_whenSomethingElseLoosenedItBetweenPhases()
                throws Exception {
            // arrange
            var customizer = new ComposedConstraintPropertyCustomizer();
            var parentSchema = new Schema<>();
            parentSchema.setName("SaveSubtaskRequestDTO");
            var propertySchema = new Schema<String>();
            propertySchema.setType("string");
            var annotatedType =
                    annotatedTypeFor(SaveSubtaskRequestDTO.class, "title", parentSchema);

            // act: phase 1 computes minLength/maxLength from @NotBlank + @SubtaskTitle
            customizer.customize(propertySchema, annotatedType);
            Assertions.assertThat(propertySchema.getMinLength())
                    .isEqualTo(ValidationConstants.MIN_SUBTASK_TITLE_LENGTH);
            Assertions.assertThat(propertySchema.getMaxLength())
                    .isEqualTo(ValidationConstants.MAX_SUBTASK_TITLE_LENGTH);

            // something else -- swagger-core's own second internal pass per Observation 2 on the
            // class Javadoc, or, latently, a field-level @Schema -- loosens the SAME schema
            // object before phase 2 runs
            propertySchema.setMinLength(1);
            propertySchema.setMaxLength(999);

            // act: phase 2, the document's last word
            customizer.customise(documentWith("SaveSubtaskRequestDTO", "title", propertySchema));

            // assert: the computed, tighter values win back
            Assertions.assertThat(propertySchema.getMinLength())
                    .isEqualTo(ValidationConstants.MIN_SUBTASK_TITLE_LENGTH);
            Assertions.assertThat(propertySchema.getMaxLength())
                    .isEqualTo(ValidationConstants.MAX_SUBTASK_TITLE_LENGTH);
        }

        @Test
        void shouldNotLoosenAnAlreadyStricterValue_whenReasserting() throws Exception {
            // arrange
            var customizer = new ComposedConstraintPropertyCustomizer();
            var parentSchema = new Schema<>();
            parentSchema.setName("SaveSubtaskRequestDTO");
            var propertySchema = new Schema<String>();
            propertySchema.setType("string");
            var annotatedType =
                    annotatedTypeFor(SaveSubtaskRequestDTO.class, "title", parentSchema);
            customizer.customize(propertySchema, annotatedType);

            // something else set a STRICTER minLength/maxLength/pattern than this bean computed --
            // e.g. a field-level @Schema(minLength = 10, maxLength = 20, pattern = "^Sprint .*$")
            propertySchema.setMinLength(10);
            propertySchema.setMaxLength(20);
            propertySchema.setPattern("^Sprint .*$");

            // act
            customizer.customise(documentWith("SaveSubtaskRequestDTO", "title", propertySchema));

            // assert: reassertOn must not loosen back to its OWN, looser computed values
            Assertions.assertThat(propertySchema.getMinLength()).isEqualTo(10);
            Assertions.assertThat(propertySchema.getMaxLength()).isEqualTo(20);
            Assertions.assertThat(propertySchema.getPattern()).isEqualTo("^Sprint .*$");
        }
    }
}
