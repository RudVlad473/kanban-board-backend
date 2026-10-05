package com.vrudenko.kanban_board.config;

import java.util.List;

import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Executable form of the schema-provenance criterion: Testcontainers, Flyway V1-V4, Hibernate
 * {@code ddl-auto=validate} and Spring Session JDBC's initializer coexist in one context.
 *
 * <p>Queries the live catalog directly, so a regression in any one mechanism fails a named
 * assertion. Does not extend {@code AbstractAppTest}: it needs no fixtures.
 */
@SpringBootTest
// No @TestPropertySource: application-test.properties already enables Flyway and sets
// ddl-auto=validate, and an override would be a second, driftable schema-configuration path
// (docs/CODE_STYLE.md rule 8).
class FlywaySchemaProvenanceTest extends AbstractPostgresContainerTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Nested
    class FlywayHistory {
        @Test
        void shouldRecordSixSuccessfulMigrations_whenContextStarts() {
            // arrange
            var sql =
                    "SELECT count(*) FROM flyway_schema_history WHERE success = true AND version"
                            + " IN ('1','2','3','4','5','6')";

            // act
            var successfulCount = jdbcTemplate.queryForObject(sql, Integer.class);

            // assert
            Assertions.assertThat(successfulCount).isEqualTo(6);
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
     * Artifacts that exist only because a Flyway migration named them explicitly: Hibernate emits
     * {@code fk}/{@code uk} plus a hash name, no column defaults and no non-annotated indexes.
     *
     * <p>The final test is the negative half: no constraint in the schema matches Hibernate's
     * generated-name form.
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
        void shouldStoreActivityLogEventIdAsCharacterType_notUuid_whenSchemaIsBuiltByV6Migration() {
            // arrange
            var sql =
                    "SELECT data_type FROM information_schema.columns WHERE table_schema ="
                            + " 'public' AND table_name = 'activity_log' AND column_name ="
                            + " 'event_id'";

            // act
            var dataType = jdbcTemplate.queryForObject(sql, String.class);

            // assert
            Assertions.assertThat(dataType).isEqualToIgnoringCase("character varying");
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

        @Test
        void
                shouldContainBoardsUserIdNameUniqueConstraintNamedByV5Migration_whenSchemaIsBuiltByFlyway() {
            // arrange
            var sql =
                    "SELECT count(*) FROM information_schema.table_constraints WHERE"
                            + " constraint_schema = 'public' AND constraint_name ="
                            + " 'uk_boards_user_id_name'";

            // act
            var count = jdbcTemplate.queryForObject(sql, Integer.class);

            // assert
            Assertions.assertThat(count).isEqualTo(1);
        }

        @Test
        void shouldDefaultUsersThemeColumnToLight_whenSchemaIsBuiltByV5Migration() {
            // arrange
            var sql =
                    "SELECT column_default FROM information_schema.columns WHERE table_schema ="
                            + " 'public' AND table_name = 'users' AND column_name = 'theme'";

            // act
            var columnDefault = jdbcTemplate.queryForObject(sql, String.class);

            // assert
            Assertions.assertThat(columnDefault).startsWith("'LIGHT'");
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
