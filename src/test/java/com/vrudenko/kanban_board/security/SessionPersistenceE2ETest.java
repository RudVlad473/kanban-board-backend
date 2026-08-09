package com.vrudenko.kanban_board.security;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppE2ETest;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the Spring Session JDBC store is real, not merely configured. Before {@code
 * spring-session-jdbc} was added to the classpath, {@code spring.session.store-type=jdbc} was set
 * but inert -- sessions were Tomcat's plain in-memory {@code HttpSession} and nothing in the
 * codebase or the test suite ever touched these tables. A green suite without this class would look
 * identical whether the store worked or not, which is exactly how the defect survived; these
 * assertions are what tells the two apart.
 *
 * <p>Review conclusion for {@code SecurityConfiguration:38}: {@code
 * HttpSessionSecurityContextRepository} was reviewed and deliberately kept as-is by this fix.
 * Spring Session's {@code SessionRepositoryFilter} registers at order {@code Integer.MIN_VALUE +
 * 50}, ahead of {@code springSecurityFilterChain} at {@code -100}, so {@code request.getSession()}
 * is already backed by {@code JdbcIndexedSessionRepository} by the time the repository writes to it
 * -- no code change to {@code SecurityConfiguration} was needed to make the security context land
 * in the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SessionPersistenceE2ETest extends AbstractAppE2ETest {

    /**
     * Prefix common to every hash {@code BeanConfiguration}'s {@code BCryptPasswordEncoder}
     * produces. Derived as a constant here rather than fetched from the test user's real hash, so
     * this test does not depend on the repository's row shape -- only on what a bcrypt hash always
     * looks like.
     */
    private static final String BCRYPT_HASH_MARKER = "$2a$";

    private static final String SPRING_SECURITY_CONTEXT_ATTRIBUTE = "SPRING_SECURITY_CONTEXT";

    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Signs in and returns the {@code PRIMARY_ID} of the {@code SPRING_SESSION} row the signin
     * created. {@code SPRING_SESSION} carries no foreign key to users, so {@code AbstractAppTest}'s
     * {@code @AfterEach userService.deleteAll()} never clears these rows and they accumulate across
     * the whole suite run in the shared containerised Postgres database. Identifying the new row by
     * set difference (rather than an absolute count or ordering) is what keeps this deterministic
     * regardless of how many sessions earlier tests left behind. JUnit runs sequentially here (no
     * {@code junit-platform.properties} declares parallelism), so exactly one new id is expected.
     * Never compare the returned session cookie value to {@code SESSION_ID} -- {@code
     * DefaultCookieSerializer} Base64-encodes the cookie by default, so the two are not equal and
     * would fail an equality assertion for a reason unrelated to this fix.
     */
    private String signinAndCaptureNewSessionPrimaryId() {
        var idsBefore =
                new HashSet<>(
                        jdbcTemplate.queryForList(
                                "SELECT PRIMARY_ID FROM SPRING_SESSION", String.class));

        Pair<String, String> cookie = signin();
        Assertions.assertThat(cookie.getSecond()).isNotNull();

        var idsAfter =
                jdbcTemplate.queryForList("SELECT PRIMARY_ID FROM SPRING_SESSION", String.class);
        var newIds = idsAfter.stream().filter(id -> !idsBefore.contains(id)).toList();

        Assertions.assertThat(newIds).hasSize(1);
        return newIds.get(0);
    }

    @Nested
    class SchemaCreation {
        @Test
        void shouldCreateSpringSessionTables_whenApplicationStarts() {
            // arrange
            // act: PostgreSQL folds unquoted identifiers to lower case, the opposite of H2. Spring
            // Session's schema-postgresql.sql creates both tables unquoted, so the catalog holds
            // lower-case names -- and the plain SELECT ... FROM statements elsewhere in this class
            // still work unquoted, because the query's identifiers are folded the same way the
            // DDL's
            // were.
            var sessionTableCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema ="
                                    + " 'public' AND table_name = 'spring_session'",
                            Integer.class);
            var attributesTableCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema ="
                                    + " 'public' AND table_name = 'spring_session_attributes'",
                            Integer.class);

            // assert: registered in the schema metadata...
            Assertions.assertThat(sessionTableCount).isEqualTo(1);
            Assertions.assertThat(attributesTableCount).isEqualTo(1);

            // ...and actually queryable, not merely present in metadata
            Assertions.assertThatCode(
                            () ->
                                    jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class))
                    .doesNotThrowAnyException();
            Assertions.assertThatCode(
                            () ->
                                    jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES",
                                            Integer.class))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class SigninPersistence {
        @Test
        void shouldAddOneSessionRow_whenSigninSucceeds() {
            // arrange
            var countBefore =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);

            // act
            Pair<String, String> cookie = signin();

            // assert
            Assertions.assertThat(cookie.getSecond()).isNotNull();
            var countAfter =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
            Assertions.assertThat(countAfter - countBefore).isEqualTo(1);
        }

        @Test
        void shouldPersistSecurityContextAttribute_whenSigninSucceeds() {
            // arrange
            // act
            var newSessionPrimaryId = signinAndCaptureNewSessionPrimaryId();

            // assert
            var attributeNames =
                    jdbcTemplate.queryForList(
                            "SELECT ATTRIBUTE_NAME FROM SPRING_SESSION_ATTRIBUTES WHERE"
                                    + " SESSION_PRIMARY_ID = ?",
                            String.class,
                            newSessionPrimaryId);

            Assertions.assertThat(attributeNames)
                    .containsExactly(SPRING_SECURITY_CONTEXT_ATTRIBUTE);
        }

        @Test
        void shouldNotPersistBcryptHash_whenSigninSucceeds() {
            // arrange
            // act
            var newSessionPrimaryId = signinAndCaptureNewSessionPrimaryId();

            // assert: Java serialization writes String fields as modified UTF-8, so an ASCII
            // bcrypt hash would appear verbatim in these bytes if UserAuthenticationProvider ever
            // persisted the full UserEntity (with its passwordHash) as the principal instead of
            // the minimal User it deliberately builds
            var attributeBytes =
                    jdbcTemplate.queryForObject(
                            "SELECT ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES WHERE"
                                    + " SESSION_PRIMARY_ID = ? AND ATTRIBUTE_NAME = ?",
                            byte[].class,
                            newSessionPrimaryId,
                            SPRING_SECURITY_CONTEXT_ATTRIBUTE);

            var decoded = new String(attributeBytes, StandardCharsets.ISO_8859_1);
            Assertions.assertThat(decoded).doesNotContain(BCRYPT_HASH_MARKER);
        }
    }

    @Nested
    class ConcurrentSessionCeiling {

        /**
         * Specifies enforced behaviour, not a tripwire. {@code SecurityConfiguration:61-67}
         * declares {@code maximumSessions(2).maxSessionsPreventsLogin(true)}; enforcement comes
         * from the {@code sessionAuthenticationStrategy} bean -- a {@code
         * CompositeSessionAuthenticationStrategy} composing {@code
         * ConcurrentSessionControlAuthenticationStrategy} (backed by a {@code
         * SpringSessionBackedSessionRegistry}, so the count is a live read of {@code
         * SPRING_SESSION.PRINCIPAL_NAME}) and {@code ChangeSessionIdAuthenticationStrategy} --
         * invoked explicitly from {@code AuthenticationController.authenticate}'s {@code mapTry}
         * lambda, before the {@code SecurityContext} is saved. The same call site is shared by
         * {@code signup}, so the ceiling and id rotation apply there too.
         *
         * <p>The rejection surfaces as {@code 401 Invalid username or password} --
         * indistinguishable from a wrong password. This is deliberate, not an oversight: the {@code
         * SessionAuthenticationException} thrown by the strategy is collapsed by the Vavr {@code
         * Try} in {@code authenticate}, then by that method's blanket {@code catch}, into a {@code
         * BadCredentialsException}. Distinguishing the two responses would hand an attacker an
         * oracle for "these credentials are valid, this account just has sessions open"; the
         * usability cost -- a real user hitting the ceiling learns nothing about why -- is accepted
         * and recorded here, not silently swallowed. Do not "fix" this by adding a dedicated
         * exception handler for {@code SessionAuthenticationException}.
         */
        @Test
        void shouldRejectThirdSignin_whenConcurrentSessionCeilingIsReached() {
            // arrange
            var countBefore =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);

            // act: two signins succeed; signin() cannot express a rejection (it extracts only a
            // cookie and asserts no status), so the third is issued inline to capture the status
            var firstCookie = signin();
            var secondCookie = signin();
            var thirdResponse =
                    given().contentType(ContentType.JSON)
                            .body(
                                    SigninRequestDTO.builder()
                                            .email(getOwningUser().getEmail())
                                            .password(getOwningUserPassword())
                                            .build())
                            .when()
                            .post(ApiPaths.SIGNIN)
                            .then()
                            .extract();

            // assert: the first two signins succeed with two distinct session cookies
            Assertions.assertThat(firstCookie.getSecond()).isNotNull();
            Assertions.assertThat(secondCookie.getSecond()).isNotNull();
            Assertions.assertThat(firstCookie.getSecond()).isNotEqualTo(secondCookie.getSecond());

            // assert: the third is rejected over HTTP and yields no session cookie
            Assertions.assertThat(thirdResponse.statusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(thirdResponse.cookie(COOKIE_NAME)).isNull();

            // assert: exactly two SPRING_SESSION rows were created across all three attempts --
            // the rejected third signin creates none
            var countAfter =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
            Assertions.assertThat(countAfter - countBefore).isEqualTo(2);
        }
    }

    @Nested
    class SessionFixation {

        /**
         * Proves the session id is rotated across the pre-auth -> post-auth privilege transition
         * (closes {@code T-shl-01}). {@code ChangeSessionIdAuthenticationStrategy} -- the second
         * delegate in {@code sessionAuthenticationStrategy} -- only rotates an id when {@code
         * request.getSession(false)} is non-null; a signin with no cookie attached has nothing to
         * rotate and would pass vacuously without proving anything. Presenting the first signin's
         * cookie on the second request is what makes the pre-existing session real and the rotation
         * observable.
         *
         * <p>Cookie values are Base64-encoded by {@code DefaultCookieSerializer}, so they are
         * compared to each other here, never to a {@code SPRING_SESSION.SESSION_ID} column value.
         */
        @Test
        void shouldRotateSessionId_whenSigninPresentsAnExistingSession() {
            // arrange
            var countBefore =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
            var firstCookie = signin();

            // act: re-signin, presenting the live cookie the first signin returned
            var secondResponse =
                    given().contentType(ContentType.JSON)
                            .cookie(firstCookie.getFirst(), firstCookie.getSecond())
                            .body(
                                    SigninRequestDTO.builder()
                                            .email(getOwningUser().getEmail())
                                            .password(getOwningUserPassword())
                                            .build())
                            .when()
                            .post(ApiPaths.SIGNIN)
                            .then()
                            .extract();

            // assert: a fresh, different cookie value comes back -- the id was rotated, not reused
            var secondCookieValue = secondResponse.cookie(COOKIE_NAME);
            Assertions.assertThat(secondCookieValue).isNotNull();
            Assertions.assertThat(secondCookieValue).isNotEqualTo(firstCookie.getSecond());

            // assert: rotation deletes the old row and re-saves under the fresh id, so the two
            // signins together leave exactly one new SPRING_SESSION row, not two
            var countAfter =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
            Assertions.assertThat(countAfter - countBefore).isEqualTo(1);
        }
    }
}
