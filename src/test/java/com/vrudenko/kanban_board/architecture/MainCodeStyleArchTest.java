package com.vrudenko.kanban_board.architecture;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Enforces the main-source code style rules a tool can check: docs/CODE_STYLE.md rules 6, 7
 * and 12.
 *
 * Rule 6 fixes the shape of an Update request DTO and keeps the JSON include annotation off
 * Save and response DTOs. Rule 7 bans Optional.orElseThrow in favour of an isEmpty guard.
 * Rule 12 bans the not-blank constraint on an Update DTO field, which would silently make an
 * optional field required.
 *
 * All four share one AnalyzeClasses with the same packages and import option as
 * LayeringArchTest, so they reuse its cached import of the class graph.
 *
 * Known holes:
 * - The optional-field count reads direct annotations only. A composed annotation that carries
 *   a not-null constraint through meta-annotation is counted as optional, so the rule then
 *   demands a cross-check and fails loudly.
 * - Rule 12 covers Update request DTO fields only. SignupRequestDTO.displayName and the
 *   service-side null guard are review-only (rubric rule 12).
 * - Rule 7 covers java.util.Optional, not OptionalInt or OptionalLong.
 */
@AnalyzeClasses(
        packages = "com.vrudenko.kanban_board",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class MainCodeStyleArchTest {

    private static final String PARTIAL_UPDATE_CROSS_CHECK = "atLeastOneFieldPopulated";

    /** The one Update DTO that is a whole-value PUT, so it carries no version. */
    private static final String VERSIONLESS_UPDATE_DTO = "UpdateThemeRequestDTO";

    /** The one Update DTO field whose blank check is mandatory by design. */
    private static final String MANDATORY_UPDATE_FIELD =
            "com.vrudenko.kanban_board.dto.column_dto.UpdateColumnRequestDTO.name";

    private static final DescribedPredicate<JavaClass> UPDATE_REQUEST_DTOS =
            DescribedPredicate.describe(
                    "are Update request DTOs in the dto package",
                    javaClass ->
                            javaClass.getPackageName().startsWith("com.vrudenko.kanban_board.dto")
                                    && javaClass.getSimpleName().startsWith("Update")
                                    && javaClass.getSimpleName().endsWith("RequestDTO"));

    private static final DescribedPredicate<JavaClass> SAVE_OR_RESPONSE_DTOS =
            DescribedPredicate.describe(
                    "are Save request or response DTOs in the dto package",
                    javaClass ->
                            javaClass.getPackageName().startsWith("com.vrudenko.kanban_board.dto")
                                    && ((javaClass.getSimpleName().startsWith("Save")
                                                    && javaClass
                                                            .getSimpleName()
                                                            .endsWith("RequestDTO"))
                                            || javaClass.getSimpleName().endsWith("ResponseDTO")));

    /**
     * An optional field is a non-static field other than version that carries none of the
     * not-null, not-blank or not-empty constraints directly.
     */
    private static boolean isOptionalField(JavaField field) {
        return !field.getModifiers().contains(JavaModifier.STATIC)
                && !field.getName().equals("version")
                && !field.isAnnotatedWith(NotNull.class)
                && !field.isAnnotatedWith(NotBlank.class)
                && !field.isAnnotatedWith(NotEmpty.class);
    }

    private static final ArchCondition<JavaClass> CARRY_THE_PARTIAL_UPDATE_SHAPE =
            new ArchCondition<>(
                    "carry JsonInclude NON_NULL, a not-null Long version, and an"
                            + " atLeastOneFieldPopulated check when two or more fields are"
                            + " optional") {
                @Override
                public void check(JavaClass dto, ConditionEvents events) {
                    var include = dto.tryGetAnnotationOfType(JsonInclude.class);
                    if (include.isEmpty()
                            || include.get().value() != JsonInclude.Include.NON_NULL) {
                        violation(dto, events, "is missing class-level @JsonInclude(NON_NULL)");
                    }

                    var version = dto.tryGetField("version");
                    if (version.isEmpty()
                            || version.get().getModifiers().contains(JavaModifier.STATIC)
                            || !version.get().getRawType().isEquivalentTo(Long.class)
                            || !version.get().isAnnotatedWith(NotNull.class)) {
                        violation(dto, events, "is missing a @NotNull Long version field");
                    }

                    var optionalFields =
                            dto.getFields().stream().filter(f -> isOptionalField(f)).count();
                    var crossChecks =
                            dto.getMethods().stream()
                                    .filter(m -> m.isAnnotatedWith(AssertTrue.class))
                                    .toList();

                    if (optionalFields >= 2
                            && crossChecks.stream().noneMatch(m -> isCrossCheck(m))) {
                        violation(
                                dto,
                                events,
                                "has "
                                        + optionalFields
                                        + " optional fields but no @AssertTrue method named "
                                        + PARTIAL_UPDATE_CROSS_CHECK
                                        + "()");
                    }

                    for (JavaMethod crossCheck : crossChecks) {
                        if (!crossCheck.getName().equals(PARTIAL_UPDATE_CROSS_CHECK)) {
                            violation(
                                    dto,
                                    events,
                                    "has an @AssertTrue method named "
                                            + crossCheck.getName()
                                            + " instead of "
                                            + PARTIAL_UPDATE_CROSS_CHECK);
                        }
                    }
                }

                private boolean isCrossCheck(JavaMethod method) {
                    return method.getName().equals(PARTIAL_UPDATE_CROSS_CHECK)
                            && method.getRawParameterTypes().isEmpty();
                }

                private void violation(JavaClass dto, ConditionEvents events, String problem) {
                    events.add(
                            SimpleConditionEvent.violated(
                                    dto, String.format("Class <%s> %s", dto.getName(), problem)));
                }
            };

    @ArchTest
    static final ArchRule optionals_must_be_unwrapped_with_an_isEmpty_guard_not_orElseThrow =
            noClasses()
                    .should(
                            callMethodWhere(
                                    target(name("orElseThrow"))
                                            .and(target(owner(equivalentTo(Optional.class))))))
                    .because(
                            "docs/CODE_STYLE.md rule 7: unwrap a repository Optional with an"
                                    + " isEmpty() guard that throws the matching App...Exception,"
                                    + " then a plain get(). This is a deliberate consistency choice:"
                                    + " orElseThrow is shorter and more idiomatic, but every site"
                                    + " uses the guard, and the guard is a statement, so a second"
                                    + " check slots in right beside it as a peer.");

    @ArchTest
    static final ArchRule update_request_dtos_must_carry_the_partial_update_shape =
            classes()
                    .that(UPDATE_REQUEST_DTOS)
                    .and()
                    .doNotHaveSimpleName(VERSIONLESS_UPDATE_DTO)
                    .should(CARRY_THE_PARTIAL_UPDATE_SHAPE)
                    .because(
                            "docs/CODE_STYLE.md rule 6: omitting the version field silently"
                                    + " disables optimistic locking for that entity, and a"
                                    + " cross-check named differently per DTO is unfindable. A DTO"
                                    + " with two or more optional fields needs the"
                                    + " atLeastOneFieldPopulated cross-check, since an empty body"
                                    + " would otherwise pass validation. The one exemption is "
                                    + VERSIONLESS_UPDATE_DTO
                                    + ": a whole-value PUT, and UserEntity has no @Version (see"
                                    + " its Javadoc Decisions).");

    @ArchTest
    static final ArchRule save_and_response_dtos_must_not_carry_json_include =
            noClasses()
                    .that(SAVE_OR_RESPONSE_DTOS)
                    .should()
                    .beAnnotatedWith(JsonInclude.class)
                    .because(
                            "docs/CODE_STYLE.md rule 6: the class-level JsonInclude marks a"
                                    + " partial-update body, so a Save or response DTO carrying it"
                                    + " would blur that signal.");

    @ArchTest
    static final ArchRule update_request_dto_fields_must_not_carry_not_blank =
            noFields()
                    .that()
                    .areDeclaredInClassesThat(UPDATE_REQUEST_DTOS)
                    .and()
                    .doNotHaveFullName(MANDATORY_UPDATE_FIELD)
                    .should()
                    .beAnnotatedWith(NotBlank.class)
                    .because(
                            "docs/CODE_STYLE.md rule 12: @NotBlank also rejects null, so on an"
                                    + " optional Update field it silently makes the field"
                                    + " required, an easy mistake with no compiler signal. Use"
                                    + " @OptionalNotBlank instead. The one exemption is "
                                    + MANDATORY_UPDATE_FIELD
                                    + ": name is that DTO's only mutable property, so it is"
                                    + " mandatory by design (see its Javadoc).");
}
