package com.vrudenko.kanban_board.architecture;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/**
 * Turns two rules this repository currently enforces only by convention and code review into
 * build-failing checks:
 *
 * <ol>
 *   <li>Controllers must not reach past the service layer into repositories.
 *   <li>The domain services must load entities through their own ownership-verified {@code
 *       findById(userId, id)}, never through a direct {@code repository.findById(id)} (see
 *       docs/CODE_STYLE.md rule 2).
 * </ol>
 *
 * <p>Both rules are declared on this single {@code @AnalyzeClasses} class so they share one cached
 * import of the {@code com.vrudenko.kanban_board} class graph — the import, not the rule
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
}
