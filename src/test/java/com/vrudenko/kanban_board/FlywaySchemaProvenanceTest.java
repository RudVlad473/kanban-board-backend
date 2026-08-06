package com.vrudenko.kanban_board;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Executable form of 04.2's actual success criterion (04.2-01-PLAN.md's {@code must_haves}): that
 * one Spring context, booted against a real PostgreSQL container, proves all four mechanisms this
 * phase depends on coexist -- Testcontainers lifecycle, Flyway V1-V4, Hibernate {@code
 * ddl-auto=validate} against the migrated schema, and Spring Session JDBC's own schema initializer.
 * Every assertion here queries the live catalog directly rather than trusting context startup
 * succeeding silently, so a future regression in any one of the four mechanisms fails a specific,
 * named assertion instead of merely "some test somewhere broke."
 *
 * <p>Deliberately does not extend {@link AbstractAppTest}: this class needs no user/board/column/
 * task fixtures, and {@code AbstractAppTest} is still wired to H2 in this plan -- extending it here
 * would pull in fixture creation against the wrong datasource entirely.
 */
@SpringBootTest
// 04.2-02 Task 1: this class now asserts against the profile's own configuration, with no
// overrides. application-test.properties itself carries spring.flyway.enabled at its default
// (enabled) and spring.jpa.hibernate.ddl-auto=validate -- the temporary @TestPropertySource
// this class carried in 04.2-01 (before the whole suite cut over) has been deleted, since
// keeping it would be a second, driftable schema-configuration path (D-05, docs/CODE_STYLE.md
// rule 8).
class FlywaySchemaProvenanceTest extends AbstractPostgresContainerTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Nested
    class FlywayHistory {
        @Test
        void shouldRecordFourSuccessfulMigrations_whenContextStarts() {
            // arrange
            var sql =
                    "SELECT count(*) FROM flyway_schema_history WHERE success = true AND version"
                            + " IN ('1','2','3','4')";

            // act
            var successfulCount = jdbcTemplate.queryForObject(sql, Integer.class);

            // assert
            Assertions.assertThat(successfulCount).isEqualTo(4);
        }

        @Test
        void shouldRecordZeroFailedMigrations_whenContextStarts() {
            // arrange
            var sql = "SELECT count(*) FROM flyway_schema_history WHERE success = false";

            // act
            var failedCount = jdbcTemplate.queryForObject(sql, Integer.class);

            // assert
            Assertions.assertThat(failedCount).isZero();
        }
    }

    @Nested
    class SchemaShape {
        @Test
        void shouldContainExactlyTheProductionTableSet_whenSchemaIsBuiltByFlyway() {
            // arrange
            var sql =
                    "SELECT table_name FROM information_schema.tables WHERE table_schema ="
                            + " 'public' AND table_type = 'BASE TABLE'";

            // act
            List<String> tableNames = jdbcTemplate.queryForList(sql, String.class);

            // assert
            Assertions.assertThat(tableNames)
                    .containsExactlyInAnyOrder(
                            "activity_log",
                            "boards",
                            "columns",
                            "flyway_schema_history",
                            "spring_session",
                            "spring_session_attributes",
                            "subtasks",
                            "tasks",
                            "users");
        }
    }

    /**
     * Each assertion here targets an artifact that exists only because a Flyway migration named it
     * explicitly -- Hibernate's naming strategy emits an {@code fk}/{@code uk} + hash name (never
     * these hand-chosen names), and emits no column defaults and no non-annotated indexes. The
     * final test is the negative half: zero constraints anywhere in the schema matching Hibernate's
     * generated-name form, proving Hibernate created nothing here at all.
     */
    @Nested
    class FlywayOnlyArtifacts {
        @Test
        void shouldContainBoardsUserForeignKeyNamedByV1Migration_whenSchemaIsBuiltByFlyway() {
            // arrange
            var sql =
                    "SELECT count(*) FROM information_schema.table_constraints WHERE"
                            + " constraint_schema = 'public' AND constraint_name ="
                            + " 'fk_boards_user'";

            // act
            var count = jdbcTemplate.queryForObject(sql, Integer.class);

            // assert
            Assertions.assertThat(count).isEqualTo(1);
        }

        @Test
        void
                shouldContainActivityLogEventIdUniqueConstraintNamedByV3Migration_whenSchemaIsBuiltByFlyway() {
            // arrange
            var sql =
                    "SELECT count(*) FROM information_schema.table_constraints WHERE"
                            + " constraint_schema = 'public' AND constraint_name ="
                            + " 'uk_activity_log_event_id'";

            // act
            var count = jdbcTemplate.queryForObject(sql, Integer.class);

            // assert
            Assertions.assertThat(count).isEqualTo(1);
        }

        @Test
        void
                shouldContainActivityLogBoardCreatedIdIndexNamedByV3Migration_whenSchemaIsBuiltByFlyway() {
            // arrange
            var sql =
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname ="
                            + " 'idx_activity_log_board_created_id'";

            // act
            var count = jdbcTemplate.queryForObject(sql, Integer.class);

            // assert
            Assertions.assertThat(count).isEqualTo(1);
        }

        @Test
        void shouldDefaultTasksVersionColumnToZero_whenSchemaIsBuiltByV2Migration() {
            // arrange
            var sql =
                    "SELECT column_default FROM information_schema.columns WHERE table_schema ="
                            + " 'public' AND table_name = 'tasks' AND column_name = 'version'";

            // act
            var columnDefault = jdbcTemplate.queryForObject(sql, String.class);

            // assert
            Assertions.assertThat(columnDefault).isEqualTo("0");
        }

        @Test
        void shouldContainZeroHibernateGeneratedConstraintNames_whenSchemaIsBuiltByFlyway() {
            // arrange
            var sql =
                    "SELECT count(*) FROM information_schema.table_constraints WHERE"
                            + " constraint_schema = 'public' AND constraint_name ~"
                            + " '^(fk|uk)[0-9a-z]{8,}$'";

            // act
            var count = jdbcTemplate.queryForObject(sql, Integer.class);

            // assert
            Assertions.assertThat(count).isZero();
        }
    }

    @Nested
    class SpringSessionCoexistence {
        @Test
        void shouldCreateBothSessionTables_whenSpringSessionInitializerRunsAlongsideFlyway() {
            // arrange
            var sql =
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema ="
                            + " 'public' AND table_name IN"
                            + " ('spring_session','spring_session_attributes')";

            // act
            var count = jdbcTemplate.queryForObject(sql, Integer.class);

            // assert
            Assertions.assertThat(count).isEqualTo(2);
        }
    }
}
