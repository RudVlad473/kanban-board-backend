package com.vrudenko.kanban_board.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Turns four rules this repository currently enforces only by convention and code review into
 * build-failing checks:
 *
 * <ol>
 *   <li>Controllers must not reach past the service layer into repositories.
 *   <li>The domain services must load entities through their own ownership-verified {@code
 *       findById(userId, id)}, never through a direct {@code repository.findById(id)} (see
 *       docs/CODE_STYLE.md rule 2).
 *   <li>Every {@code @RestController} must carry class-level {@code
 *       org.springframework.validation.annotation.Validated} (see docs/CODE_STYLE.md rule 11).
 *   <li>Every mutating handler ({@code @PostMapping}/{@code @PutMapping}/{@code @PatchMapping}) on
 *       a {@code @RestController} must bind any {@code *RequestDTO} parameter from the request body
 *       with both {@code @RequestBody} and {@code @Valid} — quick task 260811-me4 found {@code
 *       TaskController.addSubtaskByTaskId} missing {@code @RequestBody}, silently binding the DTO
 *       from query/form params instead of the JSON body a real client sends.
 * </ol>
 *
 * <p>All four rules are declared on this single {@code @AnalyzeClasses} class so they share one
 * cached import of the {@code com.vrudenko.kanban_board} class graph — the import, not the rule
 * evaluation, is the expensive part of running an ArchUnit test. {@link
 * ImportOption.DoNotIncludeTests} keeps the test source set itself out of the imported graph.
 *
 * <p><strong>This is a floor, not a ceiling.</strong> Rule 2 catches a direct {@code
 * repository.findById} call from a domain service. It does not catch the other half of
 * docs/CODE_STYLE.md rule 2 — deriving a downstream repository call's id from the verified entity
 * rather than from the raw path-variable parameter — nor other unverified loaders such as a
 * hand-written {@code repository.findByX} query. A green build here closes the single most common
 * hole; code review still carries the rest.
 */
@AnalyzeClasses(
        packages = "com.vrudenko.kanban_board",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class LayeringArchTest {

    /**
     * Direct call target predicate: a method named {@code findById} whose owner resides in this
     * project's repository package. Scoped to {@code com.vrudenko.kanban_board.repository..} rather
     * than the loose {@code ..repository..} glob, which would also match Spring's own {@code
     * org.springframework.data.repository} types and produce false positives.
     */
    private static final DescribedPredicate<JavaCall<?>> CALLS_PROJECT_REPOSITORY_FIND_BY_ID =
            target(name("findById"))
                    .and(target(owner(resideInAPackage("com.vrudenko.kanban_board.repository.."))));

    @ArchTest
    static final ArchRule controllers_must_not_reach_into_repositories =
            noClasses()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .or()
                    .resideInAPackage("..controller..")
                    .should()
                    .accessClassesThat()
                    .resideInAPackage("com.vrudenko.kanban_board.repository..")
                    .because(
                            "controllers must reach the database only through the service layer,"
                                    + " never directly through a repository (docs/CODE_STYLE.md);"
                                    + " the annotation-based half of the selector is what brings"
                                    + " AuthenticationController into scope even though it lives in"
                                    + " the security package rather than controller, and the"
                                    + " fully-qualified com.vrudenko.kanban_board.repository.. package"
                                    + " predicate (rather than the loose ..repository.. glob) is what"
                                    + " keeps Spring's own SecurityContextRepository and"
                                    + " org.springframework.data.repository out of scope");

    @ArchTest
    static final ArchRule domain_services_must_load_through_ownership_verified_findById =
            noClasses()
                    .that()
                    .resideInAPackage("com.vrudenko.kanban_board.service..")
                    .and()
                    .haveSimpleNameEndingWith("Service")
                    .and()
                    .doNotHaveSimpleName("OwnershipVerifierService")
                    .and()
                    .doNotHaveSimpleName("UserService")
                    .should(callMethodWhere(CALLS_PROJECT_REPOSITORY_FIND_BY_ID))
                    .because(
                            "the four domain services must resolve every entity through their own"
                                    + " findById(userId, id), which delegates to"
                                    + " ownershipVerifierService.verifyOwnershipOf... — never through a"
                                    + " direct repository.findById(id) call, which compiles cleanly but"
                                    + " silently skips the ownership check (docs/CODE_STYLE.md rule 2)."
                                    + " OwnershipVerifierService (the root of the ownership chain) and"
                                    + " UserService (the identity root, with no owner above it) are the"
                                    + " only two sanctioned exceptions. Passing this rule does not mean"
                                    + " ownership is fully enforced: it does not catch re-deriving a"
                                    + " downstream call's id from the raw path-variable parameter"
                                    + " instead of the verified entity, nor other unverified loaders"
                                    + " such as a hand-written repository.findByX query.");

    @ArchTest
    static final ArchRule rest_controllers_must_carry_class_level_validated =
            classes()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .should()
                    .beAnnotatedWith(Validated.class)
                    .because(
                            "class-level @Validated does not merely enable extra validation — it"
                                    + " decides which exception Spring throws for a @Valid @RequestBody"
                                    + " field-constraint failure, and therefore which error envelope the"
                                    + " client receives (quick task 260811-p9c). A @RestController"
                                    + " carrying @Validated throws MethodArgumentNotValidException,"
                                    + " which GlobalExceptionHandler converts to VALIDATION_FAILED with a"
                                    + " per-field errors map; one missing @Validated throws"
                                    + " HandlerMethodValidationException instead, converted to"
                                    + " CONSTRAINT_VIOLATION with no errors map — silently reopening the"
                                    + " envelope split 260811-p9c closed. The areAnnotatedWith(RestController.class)"
                                    + " selector (rather than a package glob) is what brings"
                                    + " AuthenticationController into scope even though it lives in the"
                                    + " security package rather than controller, mirroring the first rule"
                                    + " above.");

    /**
     * A method carrying {@code @PostMapping}, {@code @PutMapping} or {@code @PatchMapping} whose
     * declaring class is a {@code @RestController} — the {@code
     * areAnnotatedWith(RestController.class)} owner check (rather than a package glob) is what
     * brings {@code AuthenticationController} into scope even though it lives in the {@code
     * security} package rather than {@code controller}, mirroring the two rules above.
     */
    private static final DescribedPredicate<JavaMethod> ARE_MUTATING_REST_HANDLERS =
            DescribedPredicate.describe(
                    "are annotated with @PostMapping, @PutMapping or @PatchMapping and declared in"
                            + " a @RestController",
                    method ->
                            (method.isAnnotatedWith(PostMapping.class)
                                            || method.isAnnotatedWith(PutMapping.class)
                                            || method.isAnnotatedWith(PatchMapping.class))
                                    && method.getOwner().isAnnotatedWith(RestController.class));

    /**
     * Inspects annotations per-parameter (via {@link JavaMethod#getParameters()}, available in the
     * pinned ArchUnit 1.4.2, build.gradle line 182) rather than per-method — the same handler can
     * carry other parameters ({@code @PathVariable}, {@code @CurrentUserId}, {@code
     * HttpServletRequest}) that this rule must not flag. Only a parameter whose raw type simple
     * name ends with {@code RequestDTO} is required to carry both {@code @RequestBody} and
     * {@code @Valid}; a handler with no such parameter (for example a {@code @DeleteMapping} taking
     * only path variables) is unaffected.
     */
    private static final ArchCondition<JavaMethod> BIND_REQUEST_DTO_PARAMETERS_FROM_THE_BODY =
            new ArchCondition<>(
                    "bind every *RequestDTO parameter from the request body with @RequestBody and"
                            + " @Valid") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    for (JavaParameter parameter : method.getParameters()) {
                        if (!parameter.getRawType().getSimpleName().endsWith("RequestDTO")) {
                            continue;
                        }

                        var hasRequestBody = parameter.isAnnotatedWith(RequestBody.class);
                        var hasValid = parameter.isAnnotatedWith(Valid.class);

                        if (!hasRequestBody || !hasValid) {
                            events.add(
                                    SimpleConditionEvent.violated(
                                            method,
                                            String.format(
                                                    "Method <%s> has parameter of type %s missing"
                                                            + " %s",
                                                    method.getFullName(),
                                                    parameter.getRawType().getSimpleName(),
                                                    missingAnnotationsDescription(
                                                            hasRequestBody, hasValid))));
                        }
                    }
                }

                private String missingAnnotationsDescription(
                        boolean hasRequestBody, boolean hasValid) {
                    if (!hasRequestBody && !hasValid) {
                        return "@RequestBody and @Valid";
                    }
                    return hasRequestBody ? "@Valid" : "@RequestBody";
                }
            };

    @ArchTest
    static final ArchRule mutating_handlers_must_bind_request_dto_parameters_from_the_body =
            methods()
                    .that(ARE_MUTATING_REST_HANDLERS)
                    .should(BIND_REQUEST_DTO_PARAMETERS_FROM_THE_BODY)
                    .because(
                            "a @PostMapping/@PutMapping/@PatchMapping handler's *RequestDTO"
                                    + " parameter missing @RequestBody is silently bound as a model"
                                    + " attribute from query/form params instead of the JSON body a"
                                    + " real client sends (quick task 260811-me4,"
                                    + " TaskController.addSubtaskByTaskId); missing @Valid means"
                                    + " the DTO's field constraints never run at all. Both silently"
                                    + " compile and can pass a controller test that happens to drive"
                                    + " the endpoint the same broken way the production bug did.");
}
