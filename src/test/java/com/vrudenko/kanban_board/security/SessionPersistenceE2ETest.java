package com.vrudenko.kanban_board.security;

import com.vrudenko.kanban_board.AbstractAppE2ETest;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
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
     * the whole suite run in the shared H2 context. Identifying the new row by set difference
     * (rather than an absolute count or ordering) is what keeps this deterministic regardless of
     * how many sessions earlier tests left behind. JUnit runs sequentially here (no {@code
     * junit-platform.properties} declares parallelism), so exactly one new id is expected. Never
     * compare the returned session cookie value to {@code SESSION_ID} -- {@code
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
            // act: table names are stored upper-case by H2 for unquoted identifiers, matching how
            // Spring Session's schema-h2.sql creates them
            var sessionTableCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME ="
                                    + " 'SPRING_SESSION'",
                            Integer.class);
            var attributesTableCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME ="
                                    + " 'SPRING_SESSION_ATTRIBUTES'",
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
}
