package com.vrudenko.kanban_board.security;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.entity.ThemePreference;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-HTTP authentication and session tests: signin/signup, session persistence, the session
 * ceiling, fixation rotation, and bcrypt-hash persistence.
 *
 * <p>Runs at the in-process {@code @SpringBootTest}/{@link MockMvc} tier, never through {@code
 * .with(user(userId))}, which would bypass {@code AuthenticationController.authenticate} entirely.
 *
 * <p>Why this is the way it is: {@code HttpSessionSecurityContextRepository} was reviewed and kept
 * when Spring Session JDBC was added. Spring Session's {@code SessionRepositoryFilter} registers at
 * order {@code Integer.MIN_VALUE + 50}, ahead of {@code springSecurityFilterChain} at {@code -100},
 * so {@code request.getSession()} is already backed by {@code JdbcIndexedSessionRepository} when
 * the repository writes to it. {@code SignupPasswordHashPersistence} and {@code SignupThenSignin}
 * still earn their place although {@code Signup.Authenticated} asserts a 201:
 *
 * <ol>
 *   <li>That proof rides on signup auto-login, a product decision, not an invariant. If signup
 *       stops authenticating the new user, the persistence guarantee evaporates and no test goes
 *       red. These groups assert the row directly.
 *   <li>No other group signs in as a user created through the HTTP signup endpoint: {@code
 *       Signin.Authenticated} uses {@link #getOwningUser()}, created via {@code
 *       userService.save(...)}.
 *   <li>A missing hash would otherwise surface as "signup returns 401", pointing a reader at
 *       credentials or validation; a failure saying {@code PASSWORD_HASH} was null points at
 *       persistence.
 *   <li>Nothing else states that what is stored is a hash, not the plaintext.
 * </ol>
 *
 * <p>{@code SignupPasswordHashPersistence} reads the row back with raw SQL against {@code USERS},
 * not {@code UserRepository}, so the ORM mapping layer never sits between the assertion and what is
 * stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class AuthenticationTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Value("${server.servlet.session.cookie.name}")
    private String COOKIE_NAME;

    // MockMvc ignores server.servlet.context-path, but LogoutFilter matches CONTEXT_PATH +
    // ApiPaths.LOGOUT, so the Logout group must build the full prefixed URL or never reach it.
    @Value("${server.servlet.context-path}")
    private String CONTEXT_PATH;

    /**
     * Prefix of every hash {@code BeanConfiguration}'s {@code BCryptPasswordEncoder} produces, so
     * the tests depend on what a bcrypt hash looks like, not on the repository's row shape.
     */
    private static final String BCRYPT_HASH_MARKER = "$2a$";

    private static final String SPRING_SECURITY_CONTEXT_ATTRIBUTE = "SPRING_SECURITY_CONTEXT";

    /**
     * Signs in and returns the {@code PRIMARY_ID} of the {@code SPRING_SESSION} row it created.
     *
     * <p>The new row is found by set difference, not an absolute count: {@code SPRING_SESSION} has
     * no foreign key to users, so {@code AbstractAppTest}'s {@code @AfterEach
     * userService.deleteAll()} never clears these rows and they accumulate across the suite. JUnit
     * runs sequentially here, so exactly one new id is expected. Never compare the session cookie
     * to {@code SESSION_ID}: {@code DefaultCookieSerializer} Base64-encodes the cookie, so they are
     * never equal.
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
     * Generates a collision-proof signup email: a fixed prefix plus a random UUID.
     *
     * <p>{@code AuthenticationController.signup} turns any failure, including a unique-constraint
     * violation on {@code users.email}, into a 401, so a {@code dataFactory} email colliding with a
     * fixture user would fail a test as a bogus credentials error. {@code
     * SignupPasswordHashPersistence} keys its row lookup on the email, so collisions matter more
     * here.
     */
    private String collisionProofEmail() {
        return "user-persistence-" + UUID.randomUUID() + "@example.com";
    }

    /**
     * Performs a real HTTP signup and returns the email/password pair used.
     *
     * <p>Callers look the row up by the submitted email and need the plaintext password to
     * re-authenticate; the response body carries neither.
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

            /**
             * The response {@code theme} must equal {@link #getOwningUser()}'s own theme. {@code
             * UserService.findByEmail} is deliberately not {@code @Transactional}, so this also
             * proves mapping a detached {@code UserEntity} does not blow up.
             */
            @Test
            void testWithValidCredential_shouldReturnCallerIdentity_whenUserExists()
                    throws Exception {
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
                JsonNode responseBody =
                        objectMapper.readTree(result.getResponse().getContentAsString());
                Assertions.assertThat(responseBody.get("id").asText())
                        .isEqualTo(getOwningUser().getId());
                Assertions.assertThat(responseBody.get("email").asText())
                        .isEqualTo(getOwningUser().getEmail());
                Assertions.assertThat(responseBody.get("displayName").asText())
                        .isEqualTo(getOwningUser().getDisplayName());
                Assertions.assertThat(responseBody.get("theme").asText())
                        .isEqualTo(getOwningUser().getTheme().name());
            }

            /**
             * An exact-set assertion on the top-level field names, not per-key absence, so any
             * future field silently appearing in this response fails too, not only the bcrypt hash.
             */
            @Test
            void testWithValidCredential_shouldExposeOnlyIdentityFields_whenUserExists()
                    throws Exception {
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
                var rawBody = result.getResponse().getContentAsString();
                JsonNode responseBody = objectMapper.readTree(rawBody);
                var fieldNames = new HashSet<String>();
                responseBody.fieldNames().forEachRemaining(fieldNames::add);
                Assertions.assertThat(fieldNames)
                        .containsExactlyInAnyOrder("id", "email", "displayName", "theme");
                Assertions.assertThat(rawBody).doesNotContain(BCRYPT_HASH_MARKER);
            }

            @Test
            void testWithValidCredential_shouldReturnNonCacheableResponse_whenUserExists()
                    throws Exception {
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
                Assertions.assertThat(result.getResponse().getHeader("Cache-Control"))
                        .contains("no-store");
            }
        }

        @Nested
        class Unauthenticated {
            @Test
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

        // Signin-body field validation fires and is distinguishable from a genuine credential
        // failure; @Valid runs before the method body, but that still needs its own regression
        // test.
        @Nested
        class FieldValidation {
            @Test
            void shouldReturnBadRequestWithValidationFailedCode_whenEmailIsMalformed()
                    throws Exception {
                // arrange: password is a valid, well-shaped password -- email is the only
                // constraint this request violates
                var body =
                        SigninRequestDTO.builder()
                                .email("not-an-email")
                                .password(generateValidPassword())
                                .build();

                // act & assert
                mockMvc.perform(
                                post(ApiPaths.SIGNIN)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.errors.email").exists());
            }
        }

        // The generic BadCredentialsException collapse must stay indistinguishable: an unregistered
        // email and a wrong password must produce the exact same response, not merely status.
        @Nested
        class AntiEnumeration {
            @Test
            void
                    shouldReturnUnauthorizedWithBadCredentialsCode_whenEmailIsWellFormedButUnregistered()
                            throws Exception {
                // arrange
                var body =
                        SigninRequestDTO.builder()
                                .email(collisionProofEmail())
                                .password(generateValidPassword())
                                .build();

                // act & assert
                mockMvc.perform(
                                post(ApiPaths.SIGNIN)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
            }

            @Test
            void
                    shouldReturnByteIdenticalBody_whenComparingUnregisteredEmailAndWrongPasswordSignins()
                            throws Exception {
                // arrange: an unregistered email, and a registered email with a wrong password.
                // Byte-identical bodies, not just matching statuses, prove neither response leaks
                // which case occurred.
                var unregisteredEmailBody =
                        SigninRequestDTO.builder()
                                .email(collisionProofEmail())
                                .password(generateValidPassword())
                                .build();
                var wrongPasswordBody =
                        SigninRequestDTO.builder()
                                .email(getOwningUser().getEmail())
                                .password(getOwningUserPassword().concat("__"))
                                .build();

                // act
                var unregisteredEmailResponse =
                        mockMvc.perform(
                                        post(ApiPaths.SIGNIN)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        objectMapper.writeValueAsString(
                                                                unregisteredEmailBody)))
                                .andReturn()
                                .getResponse();
                var wrongPasswordResponse =
                        mockMvc.perform(
                                        post(ApiPaths.SIGNIN)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        objectMapper.writeValueAsString(
                                                                wrongPasswordBody)))
                                .andReturn()
                                .getResponse();

                // assert
                Assertions.assertThat(unregisteredEmailResponse.getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED.value());
                Assertions.assertThat(wrongPasswordResponse.getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED.value());
                Assertions.assertThat(unregisteredEmailResponse.getContentAsString())
                        .isEqualTo(wrongPasswordResponse.getContentAsString());
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

            /**
             * Mirrors {@code Signin.Authenticated}'s identity-payload test, with the same exact-set
             * guard against leaking the hash.
             */
            @Test
            void testWithValidCredential_shouldReturnCreatedIdentity_whenUserExists()
                    throws Exception {
                // Arrange
                var email = collisionProofEmail();
                var displayName =
                        dataFactory.getRandomWord(ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH);
                var body =
                        SignupRequestDTO.builder()
                                .email(email)
                                .password(generateValidPassword())
                                .displayName(displayName)
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
                var rawBody = result.getResponse().getContentAsString();
                JsonNode responseBody = objectMapper.readTree(rawBody);
                Assertions.assertThat(responseBody.get("email").asText()).isEqualTo(email);
                Assertions.assertThat(responseBody.get("displayName").asText())
                        .isEqualTo(displayName);
                Assertions.assertThat(responseBody.get("id").asText()).isNotBlank();
                Assertions.assertThat(responseBody.get("theme").asText())
                        .isEqualTo(ThemePreference.LIGHT.name());
                var fieldNames = new HashSet<String>();
                responseBody.fieldNames().forEachRemaining(fieldNames::add);
                Assertions.assertThat(fieldNames)
                        .containsExactlyInAnyOrder("id", "email", "displayName", "theme");
            }

            /**
             * The Location header must name the caller-identity resource, not the signup route
             * itself (see {@code AuthenticationController}). Built from {@code CONTEXT_PATH} plus
             * the same {@link ApiPaths} constants the handler uses, so it cannot drift.
             */
            @Test
            void testWithValidCredential_shouldPointLocationAtCallerIdentityUri_whenUserExists()
                    throws Exception {
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
                Assertions.assertThat(result.getResponse().getHeader("Location"))
                        .isEqualTo(CONTEXT_PATH + ApiPaths.USERS + ApiPaths.ME);
            }
        }

        // Signup-body field constraints fire per field rather than being skipped;
        // AbstractAppTest.generateValidPassword() documents the @Password constraint isolated here.
        @Nested
        class FieldValidation {
            @Test
            void shouldReturnBadRequestWithValidationFailedCode_whenEmailIsMalformed()
                    throws Exception {
                // arrange: password/displayName are valid; email is the only violated constraint
                var body =
                        SignupRequestDTO.builder()
                                .email("not-an-email")
                                .password(generateValidPassword())
                                .displayName(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                                .build();

                // act & assert
                mockMvc.perform(
                                post(ApiPaths.SIGNUP)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.errors.email").exists());
            }

            @Test
            void shouldReturnBadRequestWithValidationFailedCode_whenPasswordLacksUppercase()
                    throws Exception {
                // arrange: email/displayName are valid; lower-casing generateValidPassword() strips
                // its one uppercase character, isolating password as the only violated constraint
                var weakPassword = generateValidPassword().toLowerCase(Locale.ROOT);
                var body =
                        SignupRequestDTO.builder()
                                .email(collisionProofEmail())
                                .password(weakPassword)
                                .displayName(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                                .build();

                // act & assert
                mockMvc.perform(
                                post(ApiPaths.SIGNUP)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.errors.password").exists());
            }

            @Test
            void shouldReturnBadRequestWithValidationFailedCode_whenDisplayNameIsWhitespaceOnly()
                    throws Exception {
                // arrange: email/password are valid; a 3-space displayName is the only violation
                var body =
                        SignupRequestDTO.builder()
                                .email(collisionProofEmail())
                                .password(generateValidPassword())
                                .displayName("   ")
                                .build();

                // act & assert
                mockMvc.perform(
                                post(ApiPaths.SIGNUP)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(body)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.errors.displayName").exists());
            }
        }

        /**
         * Signup reveals whether an email is registered, via an explicit 409 rather than signin's
         * generic 401.
         *
         * <p>Decisions: knowingly accepted email enumeration on signup for this project's
         * personal/portfolio scope, weighed against the cost of an email-verification flow.
         * Recorded so a later reviewer reads it as a decision, not an oversight.
         */
        @Nested
        class DuplicateEmail {
            @Test
            void shouldReturnConflictWithDuplicateResourceCode_whenEmailAlreadyRegistered()
                    throws Exception {
                // arrange: a fresh, valid signup succeeds (201) and registers the email
                var email = collisionProofEmail();
                var firstBody =
                        SignupRequestDTO.builder()
                                .email(email)
                                .password(generateValidPassword())
                                .displayName(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                                .build();
                mockMvc.perform(
                                post(ApiPaths.SIGNUP)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(firstBody)))
                        .andExpect(status().isCreated());

                var duplicateBody =
                        SignupRequestDTO.builder()
                                .email(email)
                                .password(generateValidPassword())
                                .displayName(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                                .build();

                // act: an otherwise-valid signup reusing that email is rejected as a 409, not
                // swallowed into signin's generic 401
                var response =
                        mockMvc.perform(
                                        post(ApiPaths.SIGNUP)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        objectMapper.writeValueAsString(
                                                                duplicateBody)))
                                .andReturn()
                                .getResponse();

                // assert
                Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
                var body = objectMapper.readTree(response.getContentAsString());
                Assertions.assertThat(body.get("code").asText()).isEqualTo("DUPLICATE_RESOURCE");
                Assertions.assertThat(body.get("detail").asText()).contains(email);
            }

            /**
             * Field validation runs, and wins, before the duplicate-email check.
             *
             * <p>A malformed email cannot also equal a registered one, since every registered email
             * passed {@code @AppEmail}, so this violates {@code @Password} while reusing a genuine
             * duplicate email, which poses the same ordering question: does the 409 or the 400 win
             * when a request qualifies for both?
             */
            @Test
            void shouldReturnBadRequestNotConflict_whenSignupIsBothInvalidAndDuplicate()
                    throws Exception {
                // arrange: register a real user first, so its email is a genuine duplicate target
                var email = collisionProofEmail();
                var firstBody =
                        SignupRequestDTO.builder()
                                .email(email)
                                .password(generateValidPassword())
                                .displayName(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                                .build();
                mockMvc.perform(
                                post(ApiPaths.SIGNUP)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(firstBody)))
                        .andExpect(status().isCreated());

                // act: reuse that now-duplicate email, paired with a password too short to
                // satisfy @Password -- both the duplicate-email guard and field validation would
                // independently reject this request
                var invalidAndDuplicateBody =
                        SignupRequestDTO.builder()
                                .email(email)
                                .password("short")
                                .displayName(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                                .build();

                // assert
                mockMvc.perform(
                                post(ApiPaths.SIGNUP)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        invalidAndDuplicateBody)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            }
        }
    }

    @Nested
    class SchemaCreation {
        @Test
        void shouldCreateSpringSessionTables_whenApplicationStarts() {
            // arrange
            // act: PostgreSQL folds unquoted identifiers to lower case, and Spring Session's schema
            // creates both tables unquoted, so the catalog holds lower-case names; unquoted SELECTs
            // elsewhere in this class fold the same way.
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
         * A third concurrent signin for one principal is rejected with {@code 401 Invalid username
         * or password}.
         *
         * <p>Why this is the way it is: {@code SecurityConfiguration} declares {@code
         * maximumSessions(2).maxSessionsPreventsLogin(true)}, but enforcement comes from the {@code
         * sessionAuthenticationStrategy} bean, a {@code CompositeSessionAuthenticationStrategy} of
         * {@code ConcurrentSessionControlAuthenticationStrategy} (backed by a {@code
         * SpringSessionBackedSessionRegistry}, so the count is a live read of {@code
         * SPRING_SESSION.PRINCIPAL_NAME}) and {@code ChangeSessionIdAuthenticationStrategy},
         * invoked from {@code AuthenticationController.authenticate}'s {@code mapTry} lambda before
         * the {@code SecurityContext} is saved; {@code signup} shares it. The rejection is
         * indistinguishable from a wrong password on purpose: the Vavr {@code Try} and then the
         * blanket {@code catch} in {@code authenticate} collapse the {@code
         * SessionAuthenticationException} into a {@code BadCredentialsException}, and a distinct
         * response would be an oracle for "these credentials are valid, the account just has
         * sessions open". The usability cost is accepted. Do not add a dedicated exception handler
         * for {@code SessionAuthenticationException}.
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
         * The session id is rotated across the pre-auth to post-auth transition.
         *
         * <p>{@code ChangeSessionIdAuthenticationStrategy} rotates only when {@code
         * request.getSession(false)} is non-null, so a signin with no cookie would pass vacuously;
         * presenting the first signin's cookie on the second request makes the rotation observable.
         * Cookie values are Base64-encoded by {@code DefaultCookieSerializer}, so they are compared
         * to each other, never to a {@code SESSION_ID} column.
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
    class Logout {

        /**
         * Regression guard: a real {@code POST /api/logout} clears the session cookie instead of
         * failing with a 500.
         *
         * <p>Why this is the way it is: {@code SecurityConstants.SESSION_NAME} was a {@code public
         * static} field with {@code @Value} on a plain class Spring never injects, so it stayed
         * {@code null} and {@code logout.deleteCookies(...)} registered a {@code
         * CookieClearingLogoutHandler} with a null cookie name. Every logout then threw {@code
         * IllegalArgumentException("Cookie name must not be null or empty")} from {@code
         * LogoutFilter}, before {@code DispatcherServlet}. {@code ThemePersistenceTest}'s own
         * logout call missed it because it posted to the bare {@code ApiPaths.LOGOUT} with no
         * context-path prefix, which never matches {@code LogoutFilter}'s {@code CONTEXT_PATH +
         * ApiPaths.LOGOUT} matcher under {@code MockMvc}. This test builds the prefixed URL. The
         * cookie name is now read through a {@code @Value}-injected instance field on {@code
         * SecurityConfiguration}.
         */
        @Test
        void shouldClearSessionCookieAndReturnOk_whenLogoutSucceeds() throws Exception {
            // arrange: a real signed-in session, so the response actually has a cookie to clear
            var cookie = signinCookie();

            // act
            var response =
                    mockMvc.perform(post(CONTEXT_PATH + ApiPaths.LOGOUT).cookie(cookie))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            var clearedCookie = response.getCookie(COOKIE_NAME);
            Assertions.assertThat(clearedCookie).isNotNull();
            Assertions.assertThat(clearedCookie.getMaxAge()).isZero();
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

            // act: raw SQL against the real table, not UserRepository; PostgreSQL folds unquoted
            // identifiers to lower case, and the rows.get(0).get("PASSWORD_HASH") lookup still
            // works because Spring's ColumnMapRowMapper backs each row with a case-insensitive map.
            var rows =
                    jdbcTemplate.queryForList(
                            "SELECT PASSWORD_HASH FROM USERS WHERE EMAIL = ?", email);

            // assert: exactly one row, checked before reading the value so a zero-row match fails
            // legibly instead of as an index error. An absolute count is deterministic: the email
            // is unique per run and AbstractAppTest's @AfterEach deleteAll() removes user rows.
            Assertions.assertThat(rows).hasSize(1);

            var persistedHash = (String) rows.get(0).get("PASSWORD_HASH");

            // Never assert a specific hash value: BCrypt salts every encode. Assert the marker
            // prefix plus inequality/non-containment against the plaintext instead.
            //
            // This asserts what the column holds, not what the schema forbids; a NOT NULL
            // assertion belongs alongside the entity.
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

            // act: a fresh mockMvc.perform carries no cookie jar, so the signup session cookie is
            // not replayed and this signin genuinely re-authenticates
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
