package com.vrudenko.kanban_board.config;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.annotation.BmpOnly;
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
 * Regression guard for ComposedConstraintPropertyCustomizer: composed-annotation
 * constraints reach the generated OpenAPI document and the merge never loosens a published value.
 *
 * Why this is the way it is: extends AbstractPostgresContainerTest, not
 * AbstractAppTest/AbstractAppMockMvcTest, because the assertions read a generated document
 * and need no fixtures (docs/CODE_STYLE.md rule 4). MockMvc ignores
 * server.servlet.context-path, so fetchDocument() requests the bare
 * springdoc.api-docs.path (/docs), never /api/docs. The OpenAPI bean is
 * never autowired: springdoc caches the built document, so a live instance would share mutable
 * state with every later assertion.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComposedConstraintPropertyCustomizerTest extends AbstractPostgresContainerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private Validator validator;

    @Value("${springdoc.api-docs.path}")
    private String apiDocsPath;

    JsonNode fetchDocument() throws Exception {
        // Check for 200 explicitly: a non-2xx body still parses as JSON, and every
        // absence-asserting test below would then pass vacuously.
        var response = mockMvc.perform(get(apiDocsPath)).andExpect(status().isOk()).andReturn();
        var body = response.getResponse().getContentAsString();
        var objectMapper = new ObjectMapper();
        return objectMapper.readTree(body);
    }

    /**
     * Reads the @Pattern meta-annotation's regexp(), never a hand-copied literal,
     * so a regex edit cannot drift from what this test expects.
     */
    private String metaPatternOf(Class<? extends Annotation> annotationType) {
        var pattern = annotationType.getAnnotation(Pattern.class);
        Assertions.assertThat(pattern)
                .as("expected a @Pattern meta-annotation on " + annotationType.getSimpleName())
                .isNotNull();
        return pattern.regexp();
    }

    /**
     * Derives the expected pattern from the production ECMA-262 translation, for annotations with
     * non-empty flags() (today only OptionalNotBlank).
     *
     * Known holes: it cannot catch a merely wrong translation, because mutating
     * ecmaEquivalentOf mutates every expectation derived here.
     * shouldPublishThisExactLiteralTranslation_whenPatternIsOptionalNotBlank is that guard, with a
     * hand-written literal independent of production.
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
     * Reads the io.swagger.v3.oas.annotations.media.Schema meta-annotation off a composed
     * constraint annotation, so callers read description()/example() rather than
     * hand-copied literals.
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
     * Evaluates a value against one property's published pattern, minLength and
     * maxLength, with pattern as a find() search.
     *
     * Why this is the way it is: JSON Schema pattern is a search, so find() is
     * what a spec-compliant validator implements and matches() would over-constrain. A
     * generated client may full-match instead, a weaker but real behavior covered by
     * shouldAcceptValidValueUnderFullMatch_whenPatternIsAMultiRegexConjunction; neither test
     * subsumes the other.
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
     * The full-match counterpart; length bounds are omitted because a generated client full-matches
     * only pattern, never minLength/maxLength.
     */
    private boolean valueSatisfiesPublishedPatternUnderFullMatch(
            JsonNode propertyNode, String value) {
        var pattern = readText(propertyNode, "pattern");
        return pattern == null || java.util.regex.Pattern.compile(pattern).matcher(value).matches();
    }

    /**
     * Whether the real Validator accepts dto as far as propertyName is
     * concerned; a violation on a different property does not count.
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

        /**
         * The published minLength must accept every value the real Validator
         * accepts.
         *
         * It counts code points where @Size counts UTF-16 units, so an astral-heavy
         * value is where they disagree; this is the assertion the decision record at the
         * Size branch of contribute defers to. The value is one the validator accepts, so
         * it fails if the conversion is dropped or inverted.
         */
        @Test
        void shouldAcceptAstralValueTheValidatorAccepts_whenPublishedMinLengthIsEvaluated()
                throws Exception {
            // arrange: two emoji -- 4 UTF-16 units (so @Size(min = 3) takes it) but 2 code points
            var value = "\uD83D\uDE00\uD83D\uDE00";
            Assertions.assertThat(value.length()).isEqualTo(4);
            Assertions.assertThat(value.codePointCount(0, value.length())).isEqualTo(2);
            var document = fetchDocument();

            // act
            var violations =
                    validator.validate(SaveSubtaskRequestDTO.builder().title(value).build());
            var publishedMinLength =
                    readInt(propertyNode(document, "SaveSubtaskRequestDTO", "title"), "minLength");

            // assert: the validator takes it, and so must the published bound read in code points
            Assertions.assertThat(violations)
                    .as("real Validator on a 4-unit, 2-code-point title")
                    .isEmpty();
            Assertions.assertThat(publishedMinLength)
                    .as("published minLength must not exceed the value's code point count")
                    .isLessThanOrEqualTo(value.codePointCount(0, value.length()));
        }

        /**
         * The independent oracle for the ECMA-262 translation: a hand-written literal that must
         * stay hand-written.
         *
         * Why this is the way it is: every other pattern assertion derives its expectation from
         * production code, which catches drift but not a wrong translation, since the expectation
         * mutates in lockstep. Observed 2026-09-05: reverting the \S branch to emit Java's
         * \S verbatim (the ASCII-vs-Unicode defect the translation exists to fix) left all
         * 15 tests in this class green. This literal is the only assertion that fails when the
         * translation changes meaning.
         */
        @Test
        void shouldPublishThisExactLiteralTranslation_whenPatternIsOptionalNotBlank()
                throws Exception {
            // arrange: @OptionalNotBlank is ".*\S.*" with DOTALL. In ECMA-262 '.' never matches a
            // line terminator whatever the flags, and \S is Unicode-aware where Java's is
            // ASCII-only -- so both must be rewritten as explicit character classes.
            var expected = "[\\s\\S]*[^ \\t\\n\\x0B\\f\\r][\\s\\S]*";
            var document = fetchDocument();

            // act
            var published =
                    readText(propertyNode(document, "UpdateTaskRequestDTO", "title"), "pattern");

            // assert
            Assertions.assertThat(published).isEqualTo(expected);
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

            // act: document-level presence check that the conjunction does not degrade into either
            // single constituent regex; the "   " equivalence case catches that behaviorally
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
         * A multi-regex conjunction must accept a valid value under full-match evaluation, not only
         * under the unanchored search JSON Schema specifies.
         *
         * An all-lookahead conjunction satisfies a searching validator, but a generated client
         * that full-matches or anchors the pattern evaluates a zero-width expression against a
         * non-empty value and rejects it before any request is sent. Pinning full-match forces the
         * conjunction's last term to consume rather than assert.
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
            // minLength values are hand-written, not read from ValidationConstants: the published
            // bound is ceil(@Size min / 2), and deriving it from production's formula would mirror
            // a wrong formula. maxLength is published verbatim, so it stays constant-derived.
            record Row(String schema, String property, Integer minLength, Integer maxLength) {}
            var rows =
                    List.of(
                            new Row(
                                    "SaveBoardRequestDTO",
                                    "name",
                                    1,
                                    ValidationConstants.MAX_BOARD_NAME_LENGTH),
                            new Row(
                                    "UpdateBoardRequestDTO",
                                    "name",
                                    1,
                                    ValidationConstants.MAX_BOARD_NAME_LENGTH),
                            new Row("SaveColumnRequestDTO", "color", null, null),
                            new Row(
                                    "SaveColumnRequestDTO",
                                    "name",
                                    2,
                                    ValidationConstants.MAX_COLUMN_NAME_LENGTH),
                            new Row(
                                    "SaveTaskRequestDTO",
                                    "title",
                                    2,
                                    ValidationConstants.MAX_TASK_TITLE_LENGTH),
                            new Row(
                                    "SaveTaskRequestDTO",
                                    "description",
                                    1,
                                    ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH),
                            new Row(
                                    "UpdateTaskRequestDTO",
                                    "title",
                                    2,
                                    ValidationConstants.MAX_TASK_TITLE_LENGTH),
                            new Row(
                                    "UpdateTaskRequestDTO",
                                    "description",
                                    1,
                                    ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH),
                            new Row(
                                    "SaveSubtaskRequestDTO",
                                    "title",
                                    2,
                                    ValidationConstants.MAX_SUBTASK_TITLE_LENGTH),
                            new Row(
                                    "UpdateSubtaskRequestDTO",
                                    "title",
                                    2,
                                    ValidationConstants.MAX_SUBTASK_TITLE_LENGTH),
                            new Row(
                                    "SignupRequestDTO",
                                    "displayName",
                                    3,
                                    ValidationConstants.MAX_USER_DISPLAY_NAME_LENGTH),
                            new Row("SignupRequestDTO", "email", 1, null),
                            new Row("SigninRequestDTO", "email", 1, null),
                            new Row(
                                    "SignupRequestDTO",
                                    "password",
                                    4,
                                    ValidationConstants.MAX_PASSWORD_LENGTH),
                            new Row(
                                    "SigninRequestDTO",
                                    "password",
                                    4,
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
        void
                shouldRaiseMinLengthAboveTheDirectNotBlank_whenSaveSubtaskTitleAlsoComposesSubtaskTitle()
                        throws Exception {
            // arrange: the live document used to publish minLength 1 (from the direct @NotBlank)
            // while @SubtaskTitle enforces @Size(min = 3). The published bound is 2, not 3, because
            // UTF-16 units convert to code points; it only needs to exceed the @NotBlank's 1.
            var document = fetchDocument();

            // act
            var minLength =
                    readInt(propertyNode(document, "SaveSubtaskRequestDTO", "title"), "minLength");

            // assert
            Assertions.assertThat(minLength).isEqualTo(2);
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
         * The published pattern for OptionalNotBlank must agree with the real
         * Validator on a multi-line value and on a value made only of U+00A0.
         *
         * Why this is the way it is: ComposedConstraintPropertyCustomizer.contribute()
         * once republished regexp() verbatim and dropped flags() = {DOTALL}, so the
         * published . never matched a newline and \S read as ECMA-262's
         * Unicode-aware version, rejecting values Java accepts. The pattern is evaluated with
         * Java's regex engine because no JS engine is on the test classpath (observed 2026-09-05:
         * ScriptEngineManager().getEngineByName("nashorn") returns null). That is
         * sound because ecmaEquivalentOf rewrites every \s/\S into an
         * explicit character class, leaving no construct whose meaning differs between dialects.
         * Cross-checked against Node.js v24.19.0 on 2026-09-05, outside the build:
         * /^(?:[\s\S]*[^ \t\n\x0B\f\r][\s\S]*)$/.test("a\nb") is true, as is the same
         * full-match against three U+00A0 characters. Falsifier: a published pattern that
         * changes meaning between dialects invalidates the Java-engine evaluation.
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

            // assert: the validator's own verdict proves the fixtures; the document/validator
            // agreement is the regression guard
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
         * Pins an observation, not a fix: @Size counts UTF-16 units but published
         * maxLength counts code points.
         *
         * An astral-heavy value can satisfy the latter and violate the former. See the
         * Size branch of contribute() for the decision. If this starts failing, the
         * maxLength strategy changed and that decision needs updating, not silently deleting.
         */
        @Test
        void shouldAcceptAnAstralHeavyValueThatViolatesTheRealSizeMax_perD3Decision()
                throws Exception {
            // arrange
            var document = fetchDocument();
            var seventeenEmoji = "😀".repeat(17);

            // act: a spec-compliant validator counts code points against maxLength, so this does
            // not go through valueSatisfiesPublishedConstraints, which uses value.length()
            var codePointCount = seventeenEmoji.codePointCount(0, seventeenEmoji.length());
            var utf16UnitCount = seventeenEmoji.length();
            var publishedMaxLength =
                    readInt(propertyNode(document, "SaveSubtaskRequestDTO", "title"), "maxLength");
            var documentAcceptsByCodePointCount = codePointCount <= publishedMaxLength;
            var validatorAccepts =
                    realValidatorAccepts(
                            SaveSubtaskRequestDTO.builder().title(seventeenEmoji).build(), "title");

            // assert: 17 code points (<= max=32, published check accepts) but 34 UTF-16 units
            // (> 32, real validator rejects)
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
     * Proves the published document and the real Validator reach the same accept/reject
     * verdict for the fields in cases.
     *
     * Known holes: email on SignupRequestDTO/SigninRequestDTO has no
     * case, because valueSatisfiesPublishedConstraints never reads the published
     * format keyword. The published pattern is evaluated with Java's regex engine, not
     * ECMA-262; they differ for $, which Java also matches before a final line terminator,
     * so a value ending in "\n" could pass under Java and fail under ECMA-262 for a
     * $-anchored pattern. No case below uses a trailing newline, so a future case that adds one
     * must not rely on this evaluation alone.
     */
    @Nested
    class EquivalenceWithRealValidator {

        /**
         * Published and enforced bounds may disagree in this direction only: the published bound is
         * the looser one.
         *
         * The @Size annotation counts UTF-16 units and the published minLength counts code
         * points, so a short ASCII value clears the published bound and the validator still rejects
         * it, costing a 400 it would have produced anyway. The opposite direction fails
         * unconditionally above. Keep this set exact: a divergence not added here is a defect.
         */
        private static final Set<String> TOLERATED_LOOSER_THAN_ENFORCER =
                Set.of("SaveSubtaskRequestDTO.title='ab'");

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
                var key =
                        testCase.schemaName()
                                + "."
                                + testCase.propertyName()
                                + "='"
                                + testCase.value()
                                + "'";
                if (validatorAccepts && !documentAccepts) {
                    failures.add(key + " -> DOCUMENT STRICTER THAN ENFORCER, never tolerable");
                } else if (!validatorAccepts
                        && documentAccepts
                        && !TOLERATED_LOOSER_THAN_ENFORCER.contains(key)) {
                    failures.add(
                            key
                                    + " -> document looser than enforcer and not in the"
                                    + " tolerated set");
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
     * Proves every published example satisfies its own property's published constraints.
     *
     * Walks every schema rather than a checked-in list, so a new example is covered with no edit
     * here.
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

            // assert: non-vacuity first, since zero examples would satisfy the loop trivially
            Assertions.assertThat(exampleCount).isGreaterThanOrEqualTo(3);
            Assertions.assertThat(failures).isEmpty();
        }
    }

    /**
     * Pins that Password's @Schema description discloses no build-tooling
     * rationale.
     *
     * The description once quoted the gitleaks pre-commit rationale verbatim, exposing the
     * repository's secret-scanning setup to API consumers; it now ends at "...one special
     * character." Reading expected off metaSchemaOf(Class) also makes deleting
     * collectSchemaMeta's description branch fail this test, since expected
     * would be non-null while the published description stayed null.
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
     * Guards every BmpOnly declaration, which a human asserts and
     * ComposedConstraintPropertyCustomizer believes.
     *
     * Drives each marked annotation's @Pattern with an astral character and requires
     * rejection, and pins the unmarked constraints as unmarked. This catches a stale declaration;
     * it cannot prove one correct, since a passing sample does not prove BMP confinement.
     */
    @Nested
    class BmpOnlyDeclarations {

        @Test
        void shouldRejectAnAstralValue_whenAnnotationDeclaresBmpOnly() {
            // arrange: one astral character embedded in otherwise-permitted text
            var astral = "Ada \uD83D\uDE00";
            var marked = List.of(BoardName.class, DisplayName.class);
            var failures = new ArrayList<String>();

            // act
            for (var annotationType : marked) {
                if (!annotationType.isAnnotationPresent(BmpOnly.class)) {
                    failures.add(annotationType.getSimpleName() + " -> expected @BmpOnly, absent");
                    continue;
                }
                if (java.util.regex.Pattern.compile(metaPatternOf(annotationType))
                        .matcher(astral)
                        .matches()) {
                    failures.add(
                            annotationType.getSimpleName()
                                    + " -> declares @BmpOnly but its own @Pattern ACCEPTS an"
                                    + " astral value");
                }
            }

            // assert
            Assertions.assertThat(failures).isEmpty();
        }

        @Test
        void shouldNotDeclareBmpOnly_whenPatternPermitsAstralCharacters() {
            // arrange: both admit astral characters outside the structure they require
            var unmarked = List.of(OptionalNotBlank.class, Password.class);
            var failures = new ArrayList<String>();

            // act
            for (var annotationType : unmarked) {
                if (annotationType.isAnnotationPresent(BmpOnly.class)) {
                    failures.add(
                            annotationType.getSimpleName()
                                    + " -> declares @BmpOnly, but its pattern permits astral"
                                    + " characters");
                }
            }

            // assert
            Assertions.assertThat(failures).isEmpty();
        }
    }

    /**
     * Regression guard for Accumulator#reassertOn's tighten-only contract, using a
     * hand-built AnnotatedType/OpenAPI instead of a Spring context.
     *
     * The full pipeline offers no seam to control what sits on the schema between phase 1
     * (ComposedConstraintPropertyCustomizer.customize) and phase 2 (
     * ComposedConstraintPropertyCustomizer.customise).
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
            Assertions.assertThat(propertySchema.getMinLength()).isEqualTo(2);
            Assertions.assertThat(propertySchema.getMaxLength())
                    .isEqualTo(ValidationConstants.MAX_SUBTASK_TITLE_LENGTH);

            // Something else (swagger-core's second internal pass, or a field-level @Schema)
            // loosens the same schema object before phase 2 runs.
            propertySchema.setMinLength(1);
            propertySchema.setMaxLength(999);

            // act: phase 2, the document's last word
            customizer.customise(documentWith("SaveSubtaskRequestDTO", "title", propertySchema));

            // assert: the computed, tighter values win back
            Assertions.assertThat(propertySchema.getMinLength()).isEqualTo(2);
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

            // Something else sets stricter values than computed, e.g. a field-level @Schema.
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
