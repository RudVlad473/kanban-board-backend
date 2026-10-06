package com.vrudenko.kanban_board.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.assertj.core.api.Assertions;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.conditions.ArchConditions.dependOnClassesThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the test code style rules a tool can check: docs/CODE_STYLE.md rules 3, 4 and 5.
 *
 * Rule 3 fixes how assertions are written and exceptions are captured. Rule 4 bans mocking
 * libraries and Spring Boot test slices, so every test runs against the real wiring. Rule 5
 * bans the DisplayName annotation, since the method name is the display name.
 *
 * Unlike MainCodeStyleArchTest, this class imports the test source set, because that is where
 * the violations live, so it shares an AnalyzeClasses with TestPlacementArchTest instead.
 *
 * Every banned type is named by string, never by class literal. A class literal is itself a
 * dependency, so this class would flag its own source.
 *
 * Known holes:
 * - A hand-written fake is invisible to these rules (rubric rule 4).
 * - Test naming, Nested grouping and AAA comments are review-only (rubric rule 5).
 * - The static-import scan reads src/test/java under the working directory, which Gradle sets
 *   to the project directory. From any other directory it fails on its zero-files guard.
 */
@AnalyzeClasses(packages = "com.vrudenko.kanban_board")
public class TestCodeStyleArchTest {

    private static final String ASSERTJ_STATIC_IMPORT =
            "import static org.assertj.core.api.Assertions.";

    @ArchTest
    static final ArchRule tests_must_not_use_mockito_or_mock_beans =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.mockito..",
                            "org.springframework.boot.test.mock.mockito..",
                            "org.springframework.test.context.bean.override.mockito..")
                    .because(
                            "docs/CODE_STYLE.md rule 4: mocking a repository or service bypasses"
                                    + " the ownership chain and JPA behaviour these tests exist to"
                                    + " catch regressions in, so a green mocked test can sit on top"
                                    + " of a broken access-control path. Use the real Spring wiring"
                                    + " and the Testcontainers database.");

    @ArchTest
    static final ArchRule tests_must_not_use_spring_boot_test_slices =
            noClasses()
                    .should()
                    .beMetaAnnotatedWith(
                            "org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration")
                    .because(
                            "docs/CODE_STYLE.md rule 4: a slice annotation such as WebMvcTest or"
                                    + " DataJpaTest switches off auto-configuration and builds a"
                                    + " partial context, so the test no longer runs against the real"
                                    + " wiring. SpringBootTest and AutoConfigureMockMvc do not carry"
                                    + " the marker and stay allowed.");

    @ArchTest
    static final ArchRule tests_must_assert_with_assertj_and_capture_exceptions_first =
            noClasses()
                    .should(
                            dependOnClassesThat(name("org.junit.jupiter.api.Assertions"))
                                    .or(
                                            callMethodWhere(
                                                    target(
                                                                    name("assertThatThrownBy")
                                                                            .or(
                                                                                    name(
                                                                                            "assertThatExceptionOfType")))
                                                            .and(
                                                                    target(
                                                                            owner(
                                                                                    resideInAPackage(
                                                                                            "org.assertj.."))))))
                                    .as(
                                            "depend on JUnit Assertions or call assertThatThrownBy"
                                                    + " or assertThatExceptionOfType"))
                    .because(
                            "docs/CODE_STYLE.md rule 3: assert with AssertJ, and capture an"
                                    + " expected exception with Assertions.catchException before"
                                    + " asserting on it. Capturing the exception first lets one"
                                    + " test keep asserting follow-up state after the failing call,"
                                    + " which a thrown-by assertion that ends the statement cannot.");

    @ArchTest
    static void assertj_assertions_must_not_be_statically_imported(
            @SuppressWarnings("unused") JavaClasses classes) throws IOException {
        List<String> offenders = new ArrayList<>();
        long filesScanned;

        try (Stream<Path> paths = Files.walk(Path.of("src/test/java"))) {
            List<Path> javaFiles = paths.filter(path -> path.toString().endsWith(".java")).toList();
            filesScanned = javaFiles.size();

            for (Path file : javaFiles) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith(ASSERTJ_STATIC_IMPORT)) {
                        offenders.add(file + ":" + (i + 1));
                    }
                }
            }
        }

        Assertions.assertThat(filesScanned)
                .as(
                        "src/test/java must hold at least one .java file under the working"
                                + " directory, or this scan proves nothing")
                .isPositive();
        Assertions.assertThat(offenders)
                .as(
                        "docs/CODE_STYLE.md rule 3: AssertJ is used fully qualified, and"
                                + " bytecode cannot tell a static import from a qualified call,"
                                + " so this rule reads the test sources")
                .isEmpty();
    }

    @ArchTest
    static final ArchRule tests_must_not_use_display_name =
            noClasses()
                    .should()
                    .dependOnClassesThat(name("org.junit.jupiter.api.DisplayName"))
                    .because(
                            "docs/CODE_STYLE.md rule 5: the method name is the display name, so a"
                                    + " separate display string only drifts from what the method"
                                    + " asserts. The project's own validation annotation"
                                    + " dto.annotation.DisplayName is a different type and stays"
                                    + " allowed.");
}
