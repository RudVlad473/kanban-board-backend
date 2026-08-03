package com.vrudenko.kanban_board.security;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.AbstractAppE2ETest;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves that a signup performed over HTTP writes a real bcrypt password hash into the {@code
 * users} table, and that the credentials used at signup authenticate against that persisted row on
 * a subsequent, independent signin.
 *
 * <p><b>This is not proving something entirely uncovered.</b> {@code
 * AuthenticationControllerTest.Signup...testWithValidCredential_shouldPopulateCookie_whenUserExists}
 * already asserts a 201 from {@code POST /signup}, and because {@code
 * AuthenticationController.signup} auto-authenticates the new user before returning -- {@code
 * userService.save(...)} commits, then {@code authenticate(createdUser.getId(),
 * signupDTO.getPassword(), ...)} reloads the row and runs {@code passwordEncoder.matches(...)} --
 * that 201 already implies a hash was written and matched. A missing hash there would already turn
 * that test red. So do not delete this class as "redundant with the existing signup test" without
 * weighing the four gaps below, which are the actual reasons it still earns its place:
 *
 * <ol>
 *   <li>The existing proof rides entirely on signup auto-login, an ordinary product decision, not
 *       an invariant. The day signup stops authenticating the new user before returning, that
 *       entire persistence guarantee evaporates and not one test goes red. This class asserts the
 *       row directly, so it survives that refactor.
 *   <li>No other test signs in as a user created through the HTTP signup endpoint -- {@code
 *       AuthenticationControllerTest.Signin} authenticates {@code getOwningUser()}, which {@code
 *       AbstractAppTest} creates via {@code userService.save(...)} directly, a different entry path
 *       that never touches the controller.
 *   <li>If the hash went missing today, the only visible symptom would be "signup returns 401",
 *       which reads as a credentials or validation bug and points a reader at the wrong classes. A
 *       failure that says {@code PASSWORD_HASH} was null points straight at persistence.
 *   <li>Nothing else states that what is stored is a hash rather than the plaintext -- that
 *       property is currently only inferrable by reading {@code UserMapper}, {@code
 *       BeanConfiguration} and {@code UserAuthenticationProvider} together.
 * </ol>
 *
 * <p>This class reads the row back with raw SQL against {@code USERS} rather than through {@code
 * UserRepository}, so the ORM mapping layer -- the layer that would be at fault if a hash went
 * missing -- never sits between the assertion and what is actually stored (see the plan's trade-off
 * matrix: reading back through JPA was rejected for exactly this reason).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class UserPersistenceE2ETest extends AbstractAppE2ETest {

    /**
     * Prefix common to every hash {@code BeanConfiguration}'s {@code BCryptPasswordEncoder}
     * produces. Derived as a literal here rather than read off a real user's hash, so this test
     * does not depend on the repository's row shape -- only on what a bcrypt hash always looks
     * like. Same reasoning as the identically-named constant in {@code SessionPersistenceE2ETest}.
     */
    private static final String BCRYPT_HASH_MARKER = "$2a$";

    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Generates a collision-proof signup email. {@code users.email} is {@code unique = true}, and
     * {@code AuthenticationController.signup} converts any internal failure -- including a
     * unique-constraint violation -- into a 401 like every other failure, so a {@code dataFactory}
     * email colliding with one of {@code AbstractAppTest}'s two per-test fixture users would fail
     * this test as a bogus credentials error rather than the persistence problem it is meant to
     * catch. A fixed literal prefix plus a random UUID removes that failure class entirely; this
     * test keys its row lookup on the email, unlike its neighbours, so the collision-proofing
     * matters more here than a shared {@code dataFactory} convention would. The generated shape
     * also satisfies {@code @AppEmail}'s underlying {@code @Email} constraint, even though the
     * signup path does not currently cascade validation into the request body.
     */
    private String collisionProofEmail() {
        return "user-persistence-" + UUID.randomUUID() + "@example.com";
    }

    /**
     * Performs a real HTTP signup and returns the email/password pair used, for later lookup. The
     * signup response carries no body -- {@code ResponseEntity.created(...)} sets only a Location
     * header -- so the created user must always be located by the email that was submitted, never
     * read off the response.
     */
    private String[] signupOverHttp() {
        var email = collisionProofEmail();
        var password = dataFactory.getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH);
        var displayName =
                dataFactory.getRandomWord(ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH);

        given().contentType(ContentType.JSON)
                .body(
                        SignupRequestDTO.builder()
                                .email(email)
                                .password(password)
                                .displayName(displayName)
                                .build())
                .when()
                .post(ApiPaths.SIGNUP)
                .then()
                .statusCode(HttpStatus.CREATED.value());

        return new String[] {email, password};
    }

    @Nested
    class SignupPasswordHashPersistence {
        @Test
        void shouldPersistNonNullBcryptHashDifferentFromPlaintext_whenSignupSucceedsOverHttp() {
            // arrange
            var credentials = signupOverHttp();
            var email = credentials[0];
            var plaintextPassword = credentials[1];

            // act: raw SQL against the actual table, not through UserRepository -- H2
            // upper-cases unquoted identifiers, and Hibernate creates this table unquoted under
            // ddl-auto=create-drop, so the table is USERS and the columns are PASSWORD_HASH /
            // EMAIL
            var rows =
                    jdbcTemplate.queryForList(
                            "SELECT PASSWORD_HASH FROM USERS WHERE EMAIL = ?", email);

            // assert: exactly one row -- asserted before reading the value, so a zero-row match
            // fails as a legible count assertion instead of an index-out-of-bounds, and the test
            // cannot pass vacuously. Absolute count, not a before/after delta: unlike
            // SPRING_SESSION rows (no FK to users), AbstractAppTest's @AfterEach
            // userService.deleteAll() really does remove user rows, and the email is unique per
            // run, so "exactly one row matches this email" is deterministic without deltas.
            Assertions.assertThat(rows).hasSize(1);

            var persistedHash = (String) rows.get(0).get("PASSWORD_HASH");

            // Never assert a specific hash value: BCrypt salts every encode, so the same
            // password yields a different hash each run. Assert the marker prefix plus
            // inequality/non-containment against the plaintext instead. As of quick task
            // 260803-m3i, UserEntity declares @Column(nullable = false) on PASSWORD_HASH, so
            // Hibernate's H2 schema does now enforce a DB-level NOT NULL constraint -- but no
            // assertion in this class was changed by that task, and this class deliberately still
            // asserts what the column actually holds rather than what the schema forbids; a
            // schema-constraint assertion belongs alongside the entity, not here.
            Assertions.assertThat(persistedHash).isNotNull();
            Assertions.assertThat(persistedHash).startsWith(BCRYPT_HASH_MARKER);
            Assertions.assertThat(persistedHash).isNotEqualTo(plaintextPassword);
            Assertions.assertThat(persistedHash).doesNotContain(plaintextPassword);
        }
    }

    @Nested
    class SignupThenSignin {
        @Test
        void shouldAuthenticate_whenSigninUsesCredentialsFromAnEarlierHttpSignup() {
            // arrange
            var credentials = signupOverHttp();
            var email = credentials[0];
            var plaintextPassword = credentials[1];

            // act: a fresh given() chain carries no cookie jar, so the signup session cookie is
            // not replayed and this signin genuinely re-authenticates against the persisted row
            var cookie =
                    given().contentType(ContentType.JSON)
                            .body(
                                    SigninRequestDTO.builder()
                                            .email(email)
                                            .password(plaintextPassword)
                                            .build())
                            .when()
                            .post(ApiPaths.SIGNIN)
                            .then()
                            .statusCode(HttpStatus.OK.value())
                            .extract()
                            .cookie(COOKIE_NAME);

            // assert
            Assertions.assertThat(cookie).isNotNull();
        }
    }
}
