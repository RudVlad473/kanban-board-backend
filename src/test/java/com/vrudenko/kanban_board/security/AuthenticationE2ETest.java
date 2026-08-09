package com.vrudenko.kanban_board.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Consolidates the three real-HTTP authentication/session test classes that used to live here
 * separately (D-02 Candidate 1, RESEARCH.md): {@code AuthenticationControllerTest} (signin/signup
 * happy/unhappy paths), {@code SessionPersistenceE2ETest} (Spring Session JDBC persistence, the
 * concurrent-session ceiling, session-fixation rotation) and {@code UserPersistenceE2ETest}
 * (bcrypt-hash persistence via signup). All three covered {@link AuthenticationController}'s {@code
 * authenticate} helper from six angles; merging them keeps that coverage in one navigable file
 * instead of three.
 *
 * <p>Runs at the in-process {@code @SpringBootTest}/{@link MockMvc} tier (D-03 rows 21-22), never
 * {@code RANDOM_PORT}. The {@code Signin}/{@code Signup} groups drive real {@code POST}s directly;
 * the session-persistence and user-persistence groups drive them through {@link
 * AbstractAppMockMvcTest#signinCookie()} and inline {@code mockMvc.perform} POSTs for the
 * signin/signup calls the shared helper cannot express (a rejection, or a cookie relayed from a
 * prior response) -- never through {@code .with(user(userId))}, which would bypass {@code
 * AuthenticationController.authenticate} entirely (RESEARCH.md Pitfall 2).
 *
 * <p><b>Review conclusion for {@code SecurityConfiguration:38}</b> (carried from {@code
 * SessionPersistenceE2ETest}): {@code HttpSessionSecurityContextRepository} was reviewed and
 * deliberately kept as-is by the fix that added Spring Session JDBC. Spring Session's {@code
 * SessionRepositoryFilter} registers at order {@code Integer.MIN_VALUE + 50}, ahead of {@code
 * springSecurityFilterChain} at {@code -100}, so {@code request.getSession()} is already backed by
 * {@code JdbcIndexedSessionRepository} by the time the repository writes to it -- no code change to
 * {@code SecurityConfiguration} was needed to make the security context land in the database.
 *
 * <p><b>Why {@code SignupPasswordHashPersistence}/{@code SignupThenSignin} still earn their
 * place</b> (carried from {@code UserPersistenceE2ETest}) despite the {@code Signup.Authenticated}
 * group above already asserting a 201 from {@code POST /signup} -- and, because {@code
 * AuthenticationController.signup} auto-authenticates the new user before returning, already
 * implying a hash was written and matched:
 *
 * <ol>
 *   <li>The {@code Signup.Authenticated} proof rides entirely on signup auto-login, an ordinary
 *       product decision, not an invariant. The day signup stops authenticating the new user before
 *       returning, that entire persistence guarantee evaporates and not one test goes red. These
 *       two groups assert the row directly, so they survive that refactor.
 *   <li>No other group in this file signs in as a user created through the HTTP signup endpoint --
 *       {@code Signin.Authenticated} authenticates {@link #getOwningUser()}, which {@code
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
 * <p>{@code SignupPasswordHashPersistence} reads the row back with raw SQL against {@code USERS}
 * rather than through {@code UserRepository}, so the ORM mapping layer -- the layer that would be
 * at fault if a hash went missing -- never sits between the assertion and what is actually stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class AuthenticationE2ETest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Value("${server.servlet.session.cookie.name}")
    private String COOKIE_NAME;

    /**
     * Prefix common to every hash {@code BeanConfiguration}'s {@code BCryptPasswordEncoder}
     * produces. Derived as a constant here rather than fetched/read off a real user's hash, so
     * these tests do not depend on the repository's row shape -- only on what a bcrypt hash always
     * looks like.
     */
    private static final String BCRYPT_HASH_MARKER = "$2a$";

    private static final String SPRING_SECURITY_CONTEXT_ATTRIBUTE = "SPRING_SECURITY_CONTEXT";

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
    private String signinAndCaptureNewSessionPrimaryId() throws Exception {
        var idsBefore =
                new HashSet<>(
                        jdbcTemplate.queryForList(
                                "SELECT PRIMARY_ID FROM SPRING_SESSION", String.class));

        var cookie = signinCookie();
        Assertions.assertThat(cookie).isNotNull();

        var idsAfter =
                jdbcTemplate.queryForList("SELECT PRIMARY_ID FROM SPRING_SESSION", String.class);
        var newIds = idsAfter.stream().filter(id -> !idsBefore.contains(id)).toList();

        Assertions.assertThat(newIds).hasSize(1);
        return newIds.get(0);
    }

    /**
     * Generates a collision-proof signup email. {@code users.email} is {@code unique = true}, and
     * {@code AuthenticationController.signup} converts any internal failure -- including a
     * unique-constraint violation -- into a 401 like every other failure, so a {@code dataFactory}
     * email colliding with one of {@code AbstractAppTest}'s two per-test fixture users would fail
     * this test as a bogus credentials error rather than the persistence problem it is meant to
     * catch. A fixed literal prefix plus a random UUID removes that failure class entirely; the
     * {@code SignupPasswordHashPersistence} group keys its row lookup on the email, unlike its
     * neighbours, so the collision-proofing matters more here than a shared {@code dataFactory}
     * convention would. The generated shape also satisfies {@code @AppEmail}'s underlying
     * {@code @Email} constraint, even though the signup path does not currently cascade validation
     * into the request body.
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
    private String[] signupOverHttp() throws Exception {
        var email = collisionProofEmail();
        var password = generateValidPassword();
        var displayName =
                dataFactory.getRandomWord(ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH);

        mockMvc.perform(
                        post(ApiPaths.SIGNUP)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                SignupRequestDTO.builder()
                                                        .email(email)
                                                        .password(password)
                                                        .displayName(displayName)
                                                        .build())))
                .andExpect(status().isCreated());

        return new String[] {email, password};
    }

    @Nested
    class Signin {
        @Nested
        class Authenticated {
            @Test
            void testWithValidCredential_shouldPopulateCookie_whenUserExists() throws Exception {
                // Arrange
                var body =
                        SigninRequestDTO.builder()
                                .email(getOwningUser().getEmail())
                                .password(getOwningUserPassword())
                                .build();

                // Act
                var result =
                        mockMvc.perform(
                                        post(ApiPaths.SIGNIN)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(body)))
                                .andReturn();

                // Assert
                Assertions.assertThat(result.getResponse().getStatus())
                        .isEqualTo(HttpStatus.OK.value());
                Assertions.assertThat(result.getResponse().getCookie(COOKIE_NAME)).isNotNull();
            }
        }

        @Nested
        class Unauthenticated {
            @Test
            // we don't want to populate cookies for each request, only for successful ones
            void testWithInvalidCredential_shouldNotPopulateCookie_whenUserDoesntExist()
                    throws Exception {
                // Arrange
                var body =
                        SigninRequestDTO.builder()
                                .email(getOwningUser().getEmail())
                                .password(getOwningUserPassword().concat("__"))
                                .build();

                // Act
                var result =
                        mockMvc.perform(
                                        post(ApiPaths.SIGNIN)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(body)))
                                .andReturn();

                // Assert
                Assertions.assertThat(result.getResponse().getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED.value());
                Assertions.assertThat(result.getResponse().getCookie(COOKIE_NAME)).isNull();
            }
        }
    }

    @Nested
    class Signup {
        @Nested
        class Authenticated {
            @Test
            void testWithValidCredential_shouldPopulateCookie_whenUserExists() throws Exception {
                // Arrange
                var body =
                        SignupRequestDTO.builder()
                                .email(collisionProofEmail())
                                .password(generateValidPassword())
                                .displayName(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                                .build();

                // Act
                var result =
                        mockMvc.perform(
                                        post(ApiPaths.SIGNUP)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(body)))
                                .andReturn();

                // Assert
                Assertions.assertThat(result.getResponse().getStatus())
                        .isEqualTo(HttpStatus.CREATED.value());
                Assertions.assertThat(result.getResponse().getCookie(COOKIE_NAME)).isNotNull();
            }
        }
    }

    @Nested
    class SchemaCreation {
        @Test
        void shouldCreateSpringSessionTables_whenApplicationStarts() {
            // arrange
            // act: PostgreSQL folds unquoted identifiers to lower case, the opposite of H2.
            // Spring Session's schema-postgresql.sql creates both tables unquoted, so the
            // catalog holds lower-case names -- and the plain SELECT ... FROM statements
            // elsewhere in this class still work unquoted, because the query's identifiers are
            // folded the same way the DDL's were.
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
        void shouldAddOneSessionRow_whenSigninSucceeds() throws Exception {
            // arrange
            var countBefore =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);

            // act
            var cookie = signinCookie();

            // assert
            Assertions.assertThat(cookie).isNotNull();
            var countAfter =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
            Assertions.assertThat(countAfter - countBefore).isEqualTo(1);
        }

        @Test
        void shouldPersistSecurityContextAttribute_whenSigninSucceeds() throws Exception {
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
        void shouldNotPersistBcryptHash_whenSigninSucceeds() throws Exception {
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
        void shouldRejectThirdSignin_whenConcurrentSessionCeilingIsReached() throws Exception {
            // arrange
            var countBefore =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);

            // act: two signins succeed; signinCookie() cannot express a rejection (it asserts
            // 200 already), so the third is issued inline to capture the status
            var firstCookie = signinCookie();
            var secondCookie = signinCookie();
            var thirdBody =
                    SigninRequestDTO.builder()
                            .email(getOwningUser().getEmail())
                            .password(getOwningUserPassword())
                            .build();
            var thirdResult =
                    mockMvc.perform(
                                    post(ApiPaths.SIGNIN)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(thirdBody)))
                            .andReturn();

            // assert: the first two signins succeed with two distinct session cookies
            Assertions.assertThat(firstCookie).isNotNull();
            Assertions.assertThat(secondCookie).isNotNull();
            Assertions.assertThat(firstCookie.getValue()).isNotEqualTo(secondCookie.getValue());

            // assert: the third is rejected over HTTP and yields no session cookie
            Assertions.assertThat(thirdResult.getResponse().getStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(thirdResult.getResponse().getCookie(COOKIE_NAME)).isNull();

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
        void shouldRotateSessionId_whenSigninPresentsAnExistingSession() throws Exception {
            // arrange
            var countBefore =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
            var firstCookie = signinCookie();

            // act: re-signin, presenting the live cookie the first signin returned
            var body =
                    SigninRequestDTO.builder()
                            .email(getOwningUser().getEmail())
                            .password(getOwningUserPassword())
                            .build();
            var secondResult =
                    mockMvc.perform(
                                    post(ApiPaths.SIGNIN)
                                            .cookie(firstCookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(body)))
                            .andReturn();

            // assert: a fresh, different cookie value comes back -- the id was rotated, not
            // reused
            var secondCookie = secondResult.getResponse().getCookie(COOKIE_NAME);
            Assertions.assertThat(secondCookie).isNotNull();
            Assertions.assertThat(secondCookie.getValue()).isNotEqualTo(firstCookie.getValue());

            // assert: rotation deletes the old row and re-saves under the fresh id, so the two
            // signins together leave exactly one new SPRING_SESSION row, not two
            var countAfter =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
            Assertions.assertThat(countAfter - countBefore).isEqualTo(1);
        }
    }

    @Nested
    class SignupPasswordHashPersistence {
        @Test
        void shouldPersistNonNullBcryptHashDifferentFromPlaintext_whenSignupSucceedsOverHttp()
                throws Exception {
            // arrange
            var credentials = signupOverHttp();
            var email = credentials[0];
            var plaintextPassword = credentials[1];

            // act: raw SQL against the actual table, not through UserRepository -- PostgreSQL
            // folds these unquoted identifiers to lower case, and the table/columns queried
            // below are the ones V1__init.sql creates (users / password_hash / email). The
            // rows.get(0).get("PASSWORD_HASH") lookup below still works regardless of the actual
            // catalog casing because Spring's ColumnMapRowMapper backs each row with a
            // case-insensitive map.
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
            // 260803-m3i, UserEntity declares @Column(nullable = false) on PASSWORD_HASH, and
            // V4__add_password_hash_not_null.sql enforces that same constraint at the DB level --
            // but no assertion in this class was changed by that task, and this class
            // deliberately still asserts what the column actually holds rather than what the
            // schema forbids; a schema-constraint assertion belongs alongside the entity, not
            // here.
            Assertions.assertThat(persistedHash).isNotNull();
            Assertions.assertThat(persistedHash).startsWith(BCRYPT_HASH_MARKER);
            Assertions.assertThat(persistedHash).isNotEqualTo(plaintextPassword);
            Assertions.assertThat(persistedHash).doesNotContain(plaintextPassword);
        }
    }

    @Nested
    class SignupThenSignin {
        @Test
        void shouldAuthenticate_whenSigninUsesCredentialsFromAnEarlierHttpSignup()
                throws Exception {
            // arrange
            var credentials = signupOverHttp();
            var email = credentials[0];
            var plaintextPassword = credentials[1];

            // act: a fresh mockMvc.perform call carries no cookie jar, so the signup session
            // cookie is not replayed and this signin genuinely re-authenticates against the
            // persisted row
            var result =
                    mockMvc.perform(
                                    post(ApiPaths.SIGNIN)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    objectMapper.writeValueAsString(
                                                            SigninRequestDTO.builder()
                                                                    .email(email)
                                                                    .password(plaintextPassword)
                                                                    .build())))
                            .andExpect(status().isOk())
                            .andReturn();

            // assert
            var cookie = result.getResponse().getCookie(COOKIE_NAME);
            Assertions.assertThat(cookie).isNotNull();
        }
    }
}
