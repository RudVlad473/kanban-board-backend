package com.vrudenko.kanban_board.config;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.vrudenko.kanban_board.dto.annotation.BmpOnly;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.stereotype.Component;

/**
 * Publishes, on the generated OpenAPI document, the {@code pattern}/{@code minLength}/{@code
 * maxLength}/{@code format} constraints that arrive through this codebase's composed custom
 * validation annotations ({@code @ColumnColor}, {@code @BoardName}, {@code @DisplayName},
 * {@code @Password}, {@code @TaskTitle}, {@code @SubtaskTitle}, {@code @Description},
 * {@code @AppEmail}, {@code @OptionalNotBlank}) -- none of which swagger-core resolves on its own.
 *
 * <p><b>Observation 1 -- why this bean exists at all.</b> {@code
 * ModelResolver.applyBeanValidatorAnnotations} (swagger-core-jakarta 2.2.30, pulled in by
 * springdoc-openapi-starter-webmvc-ui 2.8.8 under Spring Boot 3.5.16) builds a {@code Map<String,
 * Annotation>} from a field's <em>directly declared</em> annotations only ({@code
 * ModelResolver.java:1696-1699}) and never opens a composed annotation's own meta-annotations. A
 * field annotated {@code @ColumnColor} therefore carries no {@code pattern} in the generated
 * document at all -- verified live on 2026-09-04: the production document
 * (https://kanban-board-rud-vlad-473.duckdns.org/api/docs) contains zero {@code pattern} keys and
 * zero {@code example} keys anywhere in {@code components.schemas}.
 *
 * <p><b>Observation 2 -- why this bean is registered as BOTH a {@link PropertyCustomizer} and a
 * {@link GlobalOpenApiCustomizer}, not the {@code PropertyCustomizer} alone.</b> For a field
 * carrying a <em>direct</em> {@code @NotBlank}/{@code @Size} alongside a composed annotation (e.g.
 * {@code SaveSubtaskRequestDTO.title}, direct {@code @NotBlank} plus composed
 * {@code @SubtaskTitle}), {@code ModelResolver.resolveProperties} calls {@code
 * applyBeanValidatorAnnotations} a <em>second</em> time, directly, after {@link #customize(Schema,
 * AnnotatedType)} has already run and returned ({@code ModelResolver.java:899-905} in the observed
 * sources: {@code ctxProperty} is the same object reference as the already-customized {@code
 * property} on the non-{@code allOf} resolution path this application uses). That second call
 * unconditionally re-runs {@code property.setMinLength(1)} for a direct {@code @NotBlank} with no
 * "already higher, leave it" guard -- confirmed empirically 2026-09-04 by instrumenting both this
 * bean and a live {@code bootRun} instance: {@link #customize(Schema, AnnotatedType)} correctly
 * computed and set {@code minLength=3} for {@code SaveSubtaskRequestDTO.title}, yet {@code GET
 * /api/docs} served {@code minLength=1} for that exact property. A {@code PropertyCustomizer} has
 * no extension point that runs after this second internal call; only a {@link
 * GlobalOpenApiCustomizer}, which springdoc runs as the last, whole-document post-processing phase,
 * is guaranteed to run after it. So {@link #customize(Schema, AnnotatedType)} both applies its
 * computed values immediately (correct for every field this second internal call does not touch)
 * AND records them into {@link #computedBySchema}, keyed by the schema name {@code
 * AnnotatedType.getParent().getName()} already assigns (no reflective class-name guessing, unlike a
 * {@code GlobalOpenApiCustomizer}-only design would need); {@link #customise(OpenAPI)} then
 * re-applies those same recorded values as the document's last word, overwriting whatever
 * swagger-core's own second pass did in between -- but only ever in the tightening direction, since
 * a recorded value is a phase-1 snapshot and something other than swagger-core may have set a
 * stricter value on the same schema since -- see {@link Accumulator#reassertOn(Schema)}. What would
 * make this false: a swagger-core release that removes the second {@code
 * applyBeanValidatorAnnotations} call, or guards it against lowering an already-raised bound --
 * either way this bean's reassertion becomes a harmless no-op.
 *
 * <p>Sibling to {@link ProblemDetailOpenApiCustomizer} -- that bean documents a shape swagger-core
 * cannot see at all because it lives outside any DTO ({@code @ControllerAdvice}); this one
 * documents a shape swagger-core CAN see but declines to fully read (and, per Observation 2,
 * partially reverts once it does).
 *
 * <p>{@link ComposedConstraintPropertyCustomizerTest} is the regression guard, proving both that
 * the values are actually published and that they agree with the real {@code Validator}.
 */
@Component
public class ComposedConstraintPropertyCustomizer
        implements PropertyCustomizer, GlobalOpenApiCustomizer {

    // schema name -> property name -> the values this bean computed for that property. Written
    // during the PropertyCustomizer phase (which alone has the field's annotations), read during
    // the GlobalOpenApiCustomizer phase (which springdoc guarantees runs last -- see Observation 2
    // above). A ConcurrentHashMap because document build can race on first request.
    private final Map<String, Map<String, Accumulator>> computedBySchema =
            new ConcurrentHashMap<>();

    @Override
    public Schema customize(Schema property, AnnotatedType type) {
        if (property == null || type.getCtxAnnotations() == null || !isStringSchema(property)) {
            return property;
        }

        var accumulator = seedFrom(property);
        for (var annotation : type.getCtxAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Constraint.class)) {
                walk(annotation, new LinkedHashSet<>(), accumulator);
            }
        }

        accumulator.applyTo(property);
        record(type, accumulator);
        return property;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        openApi.getComponents()
                .getSchemas()
                .forEach(
                        (schemaName, schema) -> {
                            var recorded = computedBySchema.get(schemaName);
                            if (recorded == null || schema.getProperties() == null) {
                                return;
                            }
                            recorded.forEach(
                                    (propertyName, accumulator) -> {
                                        var propertySchema =
                                                (Schema<?>)
                                                        schema.getProperties().get(propertyName);
                                        if (propertySchema != null) {
                                            accumulator.reassertOn(propertySchema);
                                        }
                                    });
                        });
    }

    private void record(AnnotatedType type, Accumulator accumulator) {
        if (type.getParent() == null
                || type.getParent().getName() == null
                || type.getPropertyName() == null) {
            return;
        }
        computedBySchema
                .computeIfAbsent(type.getParent().getName(), key -> new ConcurrentHashMap<>())
                .put(type.getPropertyName(), accumulator);
    }

    // openapi: 3.1.0 serializes a one-element `types` set as the singular "type" key, but the
    // in-memory model can carry getType() == null with getTypes() == {"string"} -- mirrors
    // org/springdoc/core/utils/SchemaUtils.java:222-225, the in-dependency precedent for covering
    // both spellings.
    private boolean isStringSchema(Schema<?> property) {
        var type = property.getType();
        if (type == null && property.getTypes() != null && property.getTypes().size() == 1) {
            type = property.getTypes().iterator().next();
        }
        return "string".equals(type);
    }

    private Accumulator seedFrom(Schema<?> property) {
        var accumulator = new Accumulator();
        if (property.getPattern() != null) {
            accumulator.patterns.add(property.getPattern());
        }
        accumulator.minLengthUnits = property.getMinLength();
        accumulator.maxLength = property.getMaxLength();
        return accumulator;
    }

    // The @Constraint gate is what keeps the walk out of java.lang.annotation.* (@Documented is
    // itself @Documented, so an ungated walk does not terminate). `pathVisited` is scoped to the
    // CURRENT recursion path, not the whole call: it is added on the way down and removed on the
    // way back up. That distinction is load-bearing -- the same annotation TYPE legitimately
    // appears twice with DIFFERENT attribute values on a field carrying two composed annotations
    // that each bring their own @Pattern (e.g. UpdateBoardRequestDTO.name: @BoardName's own
    // @Pattern and @OptionalNotBlank's own @Pattern are both jakarta.validation.constraints.Pattern
    // but carry different regexp() values). A set that is never cleared would treat the class alone
    // as "already handled" and silently drop the second occurrence's contribution.
    private void walk(
            Annotation annotation,
            Set<Class<? extends Annotation>> pathVisited,
            Accumulator accumulator) {
        var annotationType = annotation.annotationType();
        if (!pathVisited.add(annotationType)) {
            return;
        }
        contribute(annotation, accumulator);
        collectSchemaMeta(annotationType, accumulator);
        for (var meta : annotationType.getAnnotations()) {
            if (meta.annotationType().isAnnotationPresent(Constraint.class)) {
                walk(meta, pathVisited, accumulator);
            }
        }
        pathVisited.remove(annotationType);
    }

    private void contribute(Annotation annotation, Accumulator accumulator) {
        if (annotation.annotationType().isAnnotationPresent(BmpOnly.class)) {
            accumulator.bmpConfined = true;
        }
        if (annotation instanceof Pattern pattern) {
            // Publish nothing when no ECMA-262 equivalent can be proven -- see
            // ecmaEquivalentOf's Javadoc for why a dropped pattern beats a wrong one.
            ecmaEquivalentOf(pattern.regexp(), pattern.flags())
                    .ifPresent(accumulator.patterns::add);
        } else if (annotation instanceof Size size) {
            // Publish a length bound in code points, not UTF-16 code units.
            //
            // @Size counts UTF-16 code units; JSON Schema minLength/maxLength count code points,
            // and every code point is one or two units. The two bounds therefore diverge in
            // opposite directions, and only one of them can hurt a client:
            //   minLength -- publishing the @Size value verbatim REJECTS values the server
            //     ACCEPTS: a two-emoji title is 4 units, so @Size(min = 3) takes it, but it is
            //     only 2 code points, so a spec-compliant generated client refuses to send a legal
            //     request. Fixed by publishing ceil(n / 2), the largest bound no server-accepted
            //     value can fail; the proof is codePointSafeMinLength's contract and the
            //     equivalence test below, not this comment. A constraint that enumerates its
            //     permitted characters can never produce the divergence, and says so by carrying
            //     @BmpOnly, in which case the exact bound is published instead.
            //   maxLength -- publishing verbatim ACCEPTS values the server REJECTS, costing a 400
            //     the validator was always going to produce. Published unchanged on purpose:
            //     halving it would shrink every ASCII-only field's documented ceiling to close a
            //     divergence already in the tolerated direction. Revisit the day a generated
            //     client treats a published maxLength as a hard contract -- property-based fuzzing
            //     up to the documented boundary, say -- rather than as documentation.
            //
            // Corrected 2026-09-05: this record previously called publishing minLength verbatim
            // "provably safe", having reasoned correctly about the tolerated direction and never
            // checked the other one.
            if (size.min() != 0) {
                raiseMinLength(accumulator, size.min());
            }
            if (size.max() != Integer.MAX_VALUE) {
                lowerMaxLength(accumulator, size.max());
            }
        } else if (annotation instanceof NotBlank || annotation instanceof NotEmpty) {
            raiseMinLength(accumulator, 1);
        } else if (annotation instanceof Email) {
            if (accumulator.format == null) {
                accumulator.format = "email";
            }
        }
        // Every other Jakarta constraint contributes nothing to a string schema's
        // pattern/length/format and is silently ignored. @Email.regexp() is deliberately never
        // read: no site in this codebase moves it away from its ".*" default.
    }

    /**
     * Translates a Java {@code jakarta.validation.constraints.Pattern} into the ECMA-262 dialect
     * JSON Schema {@code pattern} is defined against, returning empty when no translation can be
     * PROVEN equivalent -- publishing nothing is always safer than publishing a WRONG pattern (D1,
     * quick task 260904-ss1 round 4, 2026-09-05): before this method existed, {@link
     * #contribute(Annotation, Accumulator)} republished {@code regexp()} verbatim and silently
     * dropped {@code flags()} entirely, so {@link
     * com.vrudenko.kanban_board.dto.annotation.OptionalNotBlank}'s {@code DOTALL} was lost and its
     * {@code \S} was read as ECMA's Unicode-aware version instead of Java's ASCII-only one -- both
     * proven live (node v24.19.0, 2026-09-05) to make the published pattern REJECT a value the real
     * {@code jakarta.validation.Validator} ACCEPTS: a multi-line title (published pattern
     * full-match against {@code "a\nb"}: false; real validator: accepts) and a value made solely of
     * {@code U+00A0} non-breaking-space characters (published: false; real validator: accepts) --
     * the document-stricter-than-enforcer direction this bean exists to avoid.
     *
     * <p>Handles exactly the two divergences proven above, both via direct character-class
     * rewriting rather than trying to reason about them dialect-by-dialect:
     *
     * <ul>
     *   <li>{@code DOTALL} -- an unescaped, not-inside-a-class {@code .} becomes {@code [\s\S]},
     *       since ECMA's {@code .} never matches a line terminator regardless of any flag this
     *       codebase could reach for.
     *   <li>{@code \s}/{@code \S} -- always rewritten to an explicit ASCII class ({@code [ \t\n
     *       \x0B\f\r]} / its negation {@code [^ \t\n\x0B\f\r]}), since Java's shorthand is
     *       ASCII-only but ECMA's is Unicode-aware (it matches U+00A0); rewriting removes the
     *       divergence outright instead of trying to track which dialect is in play.
     * </ul>
     *
     * <p>Any OTHER {@code flags()} value ({@code CASE_INSENSITIVE} chief among them -- no portable
     * ECMA equivalent), an unescaped capturing group, or an unrecognised {@code (?...} construct
     * makes this method return empty instead of guessing: inline flags like {@code (?i)} are a hard
     * {@code SyntaxError} in ECMA-262, and a capturing group would silently shift every later
     * group's number once this regex is concatenated into the multi-pattern conjunction {@link
     * Accumulator#applyPattern(Schema)} builds (latent today -- no regex in this codebase uses a
     * capturing group -- but a silent miscount if one ever does).
     */
    // Package-private (not private) so ComposedConstraintPropertyCustomizerTest can derive an
    // expected translated pattern straight from an annotation's own regexp()/flags() -- the same
    // "never hand-copy a literal" property metaPatternOf already gives every OTHER published
    // pattern in that test.
    static Optional<String> ecmaEquivalentOf(String javaRegexp, Pattern.Flag[] flags) {
        for (var flag : flags) {
            if (flag != Pattern.Flag.DOTALL) {
                return Optional.empty();
            }
        }
        if (hasUnsupportedGroupConstruct(javaRegexp)) {
            return Optional.empty();
        }
        // Loop above already guaranteed every flag present (if any) is DOTALL.
        var dotAll = flags.length > 0;
        return translateDotAndWhitespaceShorthand(javaRegexp, dotAll);
    }

    // Rejects an unescaped, not-inside-a-character-class '(' unless it opens a construct this
    // method recognises as safe to concatenate: '(?:', '(?=', '(?!', '(?<=', '(?<!'. Everything
    // else -- a bare capturing group, or an unrecognised "(?" form such as an inline flag group
    // ("(?i)") or a named group ("(?<name>") -- is rejected rather than guessed at.
    private static boolean hasUnsupportedGroupConstruct(String regexp) {
        var escaped = false;
        var inCharClass = false;
        for (var i = 0; i < regexp.length(); i++) {
            var c = regexp.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '[') {
                inCharClass = true;
                continue;
            }
            if (c == ']') {
                inCharClass = false;
                continue;
            }
            if (c == '(' && !inCharClass) {
                if (i + 1 >= regexp.length() || regexp.charAt(i + 1) != '?') {
                    return true;
                }
                var rest = regexp.substring(i + 2);
                var recognised =
                        rest.startsWith(":")
                                || rest.startsWith("=")
                                || rest.startsWith("!")
                                || rest.startsWith("<=")
                                || rest.startsWith("<!");
                if (!recognised) {
                    return true;
                }
            }
        }
        return false;
    }

    // Single left-to-right pass, tracking escape state and character-class membership so '.',
    // '\s' and '\S' are only rewritten where they carry their regex meaning, never inside an
    // escape sequence or as a literal a preceding backslash already consumed.
    private static Optional<String> translateDotAndWhitespaceShorthand(
            String javaRegexp, boolean dotAll) {
        var out = new StringBuilder();
        var escaped = false;
        var inCharClass = false;
        for (var i = 0; i < javaRegexp.length(); i++) {
            var c = javaRegexp.charAt(i);
            if (escaped) {
                escaped = false;
                if (c == 's') {
                    out.append(inCharClass ? " \\t\\n\\x0B\\f\\r" : "[ \\t\\n\\x0B\\f\\r]");
                } else if (c == 'S') {
                    if (inCharClass) {
                        // \S nested inside an already-open character class has no simple negated
                        // substitute; none of this codebase's patterns do this today, and
                        // guessing wrong here is worse than publishing nothing.
                        return Optional.empty();
                    }
                    out.append("[^ \\t\\n\\x0B\\f\\r]");
                } else {
                    out.append('\\').append(c);
                }
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '[') {
                inCharClass = true;
                out.append(c);
                continue;
            }
            if (c == ']') {
                inCharClass = false;
                out.append(c);
                continue;
            }
            if (c == '.' && !inCharClass && dotAll) {
                out.append("[\\s\\S]");
                continue;
            }
            out.append(c);
        }
        if (escaped) {
            // Trailing lone backslash -- malformed input; bail rather than guess.
            return Optional.empty();
        }
        return Optional.of(out.toString());
    }

    // Reads only example()/description() off a meta io.swagger.v3.oas.annotations.media.Schema --
    // that annotation carries ~40 other attributes, and ignoring all of them is deliberate scope,
    // not an oversight. First occurrence wins; a later composed annotation on the same field never
    // overwrites an example/description an earlier one already contributed.
    private void collectSchemaMeta(
            Class<? extends Annotation> annotationType, Accumulator accumulator) {
        var schemaMeta =
                annotationType.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        if (schemaMeta == null) {
            return;
        }
        if (accumulator.example == null && !schemaMeta.example().isEmpty()) {
            accumulator.example = schemaMeta.example();
        }
        if (accumulator.description == null && !schemaMeta.description().isEmpty()) {
            accumulator.description = schemaMeta.description();
        }
    }

    /**
     * Converts a {@code @Size(min)} expressed in UTF-16 code units into the largest JSON Schema
     * {@code minLength}, counted in code points, that no server-accepted value can fail.
     *
     * <p>{@code units(v) >= n} implies {@code codePoints(v) >= units(v) / 2 >= n / 2}, so {@code
     * ceil(n / 2)} is safe and any larger bound is not: for odd {@code n} an all-astral value of
     * exactly {@code n + 1} units has {@code (n + 1) / 2} code points, which is {@code ceil(n / 2)}
     * exactly.
     */
    private static int codePointSafeMinLength(int unitsMin) {
        return (unitsMin + 1) / 2;
    }

    private void raiseMinLength(Accumulator accumulator, int candidateUnits) {
        accumulator.minLengthUnits =
                accumulator.minLengthUnits == null
                        ? candidateUnits
                        : Math.max(accumulator.minLengthUnits, candidateUnits);
    }

    private void lowerMaxLength(Accumulator accumulator, int candidate) {
        accumulator.maxLength =
                accumulator.maxLength == null
                        ? candidate
                        : Math.min(accumulator.maxLength, candidate);
    }

    private static final class Accumulator {
        private final Set<String> patterns = new LinkedHashSet<>();

        // In UTF-16 code units, the unit @Size and @NotBlank are declared in. Converted to code
        // points by codePointSafeMinLength at publish time, never before -- raising the bound has
        // to happen in one unit, and the published document speaks the other.
        private Integer minLengthUnits;

        // Set when any constraint on this property declares @BmpOnly. A BMP-confined value has one
        // code unit per code point, so the unit bound needs no conversion to be safe.
        private boolean bmpConfined;
        private Integer maxLength;
        private String format;
        private String example;
        private String description;

        /**
         * Phase 1 ({@link PropertyCustomizer}). Unconditional for {@code minLength}/{@code
         * maxLength}/{@code pattern} is safe HERE ONLY because {@link
         * ComposedConstraintPropertyCustomizer#seedFrom(Schema)} populated this accumulator FROM
         * this exact {@code property} before any composed-annotation value was folded in -- there
         * is nothing already on {@code property} that a value computed here could loosen. That
         * precondition does NOT hold the second time this schema is touched (Observation 2 on the
         * enclosing class); {@link #reassertOn(Schema)} is the tighten-only counterpart used there.
         */
        void applyTo(Schema<?> property) {
            property.setMinLength(publishedMinLength());
            property.setMaxLength(maxLength);
            applyMeta(property);
            applyPattern(property);
        }

        /**
         * Phase 2 ({@link GlobalOpenApiCustomizer}, the document's last word). Tightens only, with
         * one named exception: raises {@code minLength} past whatever the schema already carries,
         * lowers {@code maxLength} only below it, sets {@code pattern} only when the schema carries
         * none -- and additionally lowers a {@code minLength} it can identify as swagger-core's own
         * unconverted code-unit bound (see {@link #isUnconvertedUnitBound(Integer)}).
         *
         * <p>D2 (quick task 260904-ss1 round 4, 2026-09-05): before this method existed, {@link
         * #customise(OpenAPI)} called {@link #applyTo(Schema)} here too, which is unconditional --
         * so this phase, which replays a snapshot recorded during phase 1, could OVERWRITE a value
         * something else set on the SAME schema object in between with an OLDER, looser one.
         * Confirmed by this quick task's round-4 review via triple-boot (2026-09-04): a field-level
         * {@code @Schema(minLength = 10, maxLength = 20, pattern = "^Sprint .*$")} on {@code
         * SaveBoardRequestDTO.name} was published intact with this bean disabled, and REPLACED by
         * the looser {@code 1 / 64 / ^[a-zA-Z0-9 ]*$} with it enabled -- neutering only this phase
         * (leaving {@link #applyTo(Schema)} as phase 1's sole writer) restored the field-level
         * values. Latent today (no DTO field in this codebase carries its own {@code @Schema}
         * constraint), but the very fix this quick task made ({@code @Schema} on {@link
         * com.vrudenko.kanban_board.dto.annotation.Password}, {@link
         * com.vrudenko.kanban_board.dto.annotation.BoardName}, etc.) introduces {@code @Schema} to
         * this codebase's vocabulary, so the class-level Javadoc's old claim that phase 2 "only
         * ever restates values it already computed" was never something a caller of THIS class
         * could rely on -- it was true only of phase 2's own recorded values, not of what else
         * might be on the schema by the time phase 2 runs.
         */
        void reassertOn(Schema<?> property) {
            var published = publishedMinLength();
            if (published != null) {
                var current = property.getMinLength();
                if (current == null || published > current || isUnconvertedUnitBound(current)) {
                    property.setMinLength(published);
                }
            }
            if (maxLength != null
                    && (property.getMaxLength() == null || maxLength < property.getMaxLength())) {
                property.setMaxLength(maxLength);
            }
            applyMeta(property);
            if (property.getPattern() == null) {
                applyPattern(property);
            }
        }

        private Integer publishedMinLength() {
            if (minLengthUnits == null) {
                return null;
            }
            return bmpConfined ? minLengthUnits : codePointSafeMinLength(minLengthUnits);
        }

        /**
         * Report whether {@code current} is the raw UTF-16 unit bound rather than a deliberate
         * choice by someone else, and so may be lowered to the converted value.
         *
         * <p>Phase 2 otherwise only ever tightens, because a value on the schema by then may have
         * been set by something with more authority than this bean -- a field-level {@code @Schema}
         * -- and replaying a phase-1 snapshot over it would loosen it. swagger-core's own second
         * pass is the exception: it re-derives {@code minLength} straight from {@code @Size}, in
         * code units, and that number is wrong for the document rather than merely stricter. It is
         * recognisable precisely because it equals the unit bound this accumulator already holds.
         */
        private boolean isUnconvertedUnitBound(Integer current) {
            return minLengthUnits != null && minLengthUnits.equals(current);
        }

        private void applyMeta(Schema<?> property) {
            if (format != null && property.getFormat() == null) {
                property.setFormat(format);
            }
            if (example != null && property.getExample() == null) {
                property.setExample(example);
            }
            if (description != null && property.getDescription() == null) {
                property.setDescription(description);
            }
        }

        private void applyPattern(Schema<?> property) {
            if (patterns.isEmpty()) {
                return;
            }
            if (patterns.size() == 1) {
                property.setPattern(patterns.iterator().next());
                return;
            }
            // size >= 2: every regex but the LAST becomes a zero-width lookahead; the last one
            // consumes. Each "^(?:Ri)$" is the faithful translation of Java's whole-string
            // Matcher.matches() into ECMA-262.
            //
            // The trailing consuming term is load-bearing for CONSUMERS, not for correctness under
            // the spec. JSON Schema evaluates `pattern` as an unanchored search, under which an
            // all-lookahead conjunction is already correct. But a generated client that instead
            // full-matches it (Java Pattern.matches, Python re.fullmatch, or a generator that
            // wraps the pattern in ^...$) sees a zero-width expression, which matches ONLY the
            // empty string -- so every valid value would be rejected client-side before a request
            // was ever sent. Measured 2026-09-04 on UpdateBoardRequestDTO.name's own two regexes:
            // all-lookahead form gave search=true/fullmatch=FALSE for "Platform Launch", this form
            // gives true/true, and both forms still reject "   " and "" under either reading.
            var ordered = List.copyOf(patterns);
            var lastIndex = ordered.size() - 1;
            var conjunction =
                    ordered.subList(0, lastIndex).stream()
                            .map(regex -> "(?=^(?:" + regex + ")$)")
                            .collect(Collectors.joining());
            property.setPattern(conjunction + "^(?:" + ordered.get(lastIndex) + ")$");
        }
    }
}
