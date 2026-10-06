package com.vrudenko.kanban_board.activitylog;

import java.util.List;
import java.util.UUID;

import com.vrudenko.kanban_board.event.avro.AvroBoardCreatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroBoardDeletedEvent;
import com.vrudenko.kanban_board.event.avro.AvroBoardUpdatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroColumnCreatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroColumnDeletedEvent;
import com.vrudenko.kanban_board.event.avro.AvroColumnReorderedEvent;
import com.vrudenko.kanban_board.event.avro.AvroColumnUpdatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroSubtaskCreatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroSubtaskDeletedEvent;
import com.vrudenko.kanban_board.event.avro.AvroSubtaskUpdatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroTaskCreatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroTaskDeletedEvent;
import com.vrudenko.kanban_board.event.avro.AvroTaskMovedEvent;
import com.vrudenko.kanban_board.event.avro.AvroTaskUpdatedEvent;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import org.apache.avro.Schema;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Turns BACKWARD compatibility from a configured setting into a demonstrated behaviour.
 *
 * The first nested group asserts configuration: every one of the 14 production subjects reports
 * BACKWARD, at subject level and not an inherited read of the registry's global default. The second
 * asserts enforcement: a backward-incompatible evolution is rejected and a compatible one accepted,
 * the control case that separates "compatibility is enforced" from "registration is broken for
 * everything".
 */
@SpringBootTest
@Tag("kafka")
class SchemaCompatibilityE2ETest extends AbstractKafkaContainerTest {

    private static final int IDENTITY_MAP_CAPACITY = 100;
    private static final String BACKWARD = "BACKWARD";

    // HTTP 409: Confluent Schema Registry's documented status for "Incompatible Avro schema".
    private static final int INCOMPATIBLE_SCHEMA_HTTP_STATUS = 409;

    private SchemaRegistryClient buildSchemaRegistryClient() {
        return new CachedSchemaRegistryClient(getSchemaRegistryAddress(), IDENTITY_MAP_CAPACITY);
    }

    private List<String> productionSubjects() {
        return List.of(
                AvroTaskCreatedEvent.getClassSchema().getFullName(),
                AvroTaskMovedEvent.getClassSchema().getFullName(),
                AvroTaskDeletedEvent.getClassSchema().getFullName(),
                AvroTaskUpdatedEvent.getClassSchema().getFullName(),
                AvroBoardCreatedEvent.getClassSchema().getFullName(),
                AvroBoardUpdatedEvent.getClassSchema().getFullName(),
                AvroBoardDeletedEvent.getClassSchema().getFullName(),
                AvroColumnCreatedEvent.getClassSchema().getFullName(),
                AvroColumnDeletedEvent.getClassSchema().getFullName(),
                AvroColumnUpdatedEvent.getClassSchema().getFullName(),
                AvroColumnReorderedEvent.getClassSchema().getFullName(),
                AvroSubtaskCreatedEvent.getClassSchema().getFullName(),
                AvroSubtaskUpdatedEvent.getClassSchema().getFullName(),
                AvroSubtaskDeletedEvent.getClassSchema().getFullName());
    }

    @Nested
    class ConfiguredCompatibilityTest {

        @Test
        void shouldReportBackwardExplicitly_forAllProductionSubjects() throws Exception {
            // arrange
            var client = buildSchemaRegistryClient();

            // act + assert -- `false` (do not fall back to the global default) means this call
            // only succeeds if the subject genuinely carries its own explicit override; a subject
            // that merely inherited the registry's global default would throw here instead.
            for (String subject : productionSubjects()) {
                var compatibility = client.getCompatibility(subject, false);
                Assertions.assertThat(compatibility).isEqualTo(BACKWARD);
            }
        }

        @Test
        void
                shouldFailWithoutFallback_whenSubjectHasNoExplicitOverride_provingProductionSubjectsAreNotJustInheritingGlobal()
                        throws Exception {
            // arrange -- AvroSchemaRegistrar never touches this subject, so it carries no
            // subject-level compatibility override at all, unlike the production subjects
            // above. Registering a schema does not itself create a compatibility override.
            var client = buildSchemaRegistryClient();
            var throwawaySubject = "compatibility-probe-no-override-" + UUID.randomUUID();
            client.register(throwawaySubject, AvroTaskCreatedEvent.getClassSchema());

            // act -- `false` again: without an explicit override, the registry cannot answer this
            // call and must fail rather than silently reading through to the global default.
            var exception =
                    Assertions.catchException(
                            () -> client.getCompatibility(throwawaySubject, false));

            // assert -- the contrast with ConfiguredCompatibilityTest's first test is the point:
            // identical call, opposite outcome, because only one subject was ever explicitly
            // configured.
            Assertions.assertThat(exception).isInstanceOf(RestClientException.class);

            // act + assert -- the same subject succeeds once fallback to the global default is
            // allowed, confirming the failure above was specifically about the missing
            // subject-level override, not a broken registry call.
            var fallbackCompatibility = client.getCompatibility(throwawaySubject, true);
            Assertions.assertThat(fallbackCompatibility).isNotNull();
        }
    }

    @Nested
    class EnforcementTest {

        private Schema baselineSchema() {
            String avsc =
                    "{"
                            + "\"type\":\"record\","
                            + "\"name\":\"CompatibilityProbeEvent\","
                            + "\"namespace\":\"com.vrudenko.kanban_board.event.avro.test\","
                            + "\"fields\":["
                            + "  {\"name\":\"eventId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},"
                            + "  {\"name\":\"userId\",\"type\":\"string\"},"
                            + "  {\"name\":\"boardId\",\"type\":\"string\"}"
                            + "]}";
            return new Schema.Parser().parse(avsc);
        }

        private Schema incompatibleEvolution() {
            // Adds a new required field with no default: a reader on this schema encounters old
            // records with no value to fall back on for it -- incompatible under BACKWARD by
            // definition.
            String avsc =
                    "{"
                            + "\"type\":\"record\","
                            + "\"name\":\"CompatibilityProbeEvent\","
                            + "\"namespace\":\"com.vrudenko.kanban_board.event.avro.test\","
                            + "\"fields\":["
                            + "  {\"name\":\"eventId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},"
                            + "  {\"name\":\"userId\",\"type\":\"string\"},"
                            + "  {\"name\":\"boardId\",\"type\":\"string\"},"
                            + "  {\"name\":\"newRequiredField\",\"type\":\"string\"}"
                            + "]}";
            return new Schema.Parser().parse(avsc);
        }

        private Schema compatibleEvolution() {
            // Adds a new field WITH a default: old records resolve to it under a newer reader
            // schema, so it is backward-compatible by definition.
            //
            // The control case: without it, a green "rejection" test cannot distinguish
            // "compatibility is enforced" from "registration is broken for everything".
            String avsc =
                    "{"
                            + "\"type\":\"record\","
                            + "\"name\":\"CompatibilityProbeEvent\","
                            + "\"namespace\":\"com.vrudenko.kanban_board.event.avro.test\","
                            + "\"fields\":["
                            + "  {\"name\":\"eventId\",\"type\":{\"type\":\"string\",\"logicalType\":\"uuid\"}},"
                            + "  {\"name\":\"userId\",\"type\":\"string\"},"
                            + "  {\"name\":\"boardId\",\"type\":\"string\"},"
                            + "  {\"name\":\"newOptionalField\",\"type\":\"string\",\"default\":\"\"}"
                            + "]}";
            return new Schema.Parser().parse(avsc);
        }

        @Test
        void shouldRejectIncompatibleEvolution_andAcceptCompatibleEvolution_underBackward()
                throws Exception {
            // arrange -- a throwaway subject set to BACKWARD (as AvroSchemaRegistrar does:
            // compatibility before first registration) with one baseline version. A subject's first
            // version is always accepted, so enforcement is observable only from the second.
            var client = buildSchemaRegistryClient();
            var throwawaySubject = "compatibility-probe-enforcement-" + UUID.randomUUID();
            client.updateCompatibility(throwawaySubject, BACKWARD);
            client.register(throwawaySubject, baselineSchema());

            // act -- the incompatible evolution
            var rejection =
                    Assertions.catchException(
                            () -> client.register(throwawaySubject, incompatibleEvolution()));

            // assert -- asserted on type and on the registry's conflict status, never on an exact
            // message string, which is implementation text and will drift.
            Assertions.assertThat(rejection).isInstanceOf(RestClientException.class);
            Assertions.assertThat(((RestClientException) rejection).getStatus())
                    .isEqualTo(INCOMPATIBLE_SCHEMA_HTTP_STATUS);

            // act + assert -- the control: a genuinely compatible evolution against the same
            // subject, same compatibility setting, must succeed.
            var schemaId = client.register(throwawaySubject, compatibleEvolution());
            Assertions.assertThat(schemaId).isPositive();
        }
    }
}
