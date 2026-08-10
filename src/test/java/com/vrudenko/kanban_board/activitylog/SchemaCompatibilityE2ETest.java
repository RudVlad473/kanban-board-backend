package com.vrudenko.kanban_board.activitylog;

import com.vrudenko.kanban_board.event.avro.AvroBoardCreatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroColumnCreatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroTaskCreatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroTaskDeletedEvent;
import com.vrudenko.kanban_board.event.avro.AvroTaskMovedEvent;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Turns D-02's BACKWARD compatibility choice from a configured setting into a demonstrated
 * behaviour. The first nested group asserts configuration (SCHEMA-04: every one of the 5 production
 * subjects reports BACKWARD, and it is genuinely subject-level, not just an inherited read of the
 * registry's global default). The second asserts enforcement: a backward-incompatible schema
 * evolution is rejected, and a backward-compatible one is accepted -- the control case that
 * distinguishes "compatibility is enforced" from "registration is broken for everything".
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
                AvroBoardCreatedEvent.getClassSchema().getFullName(),
                AvroColumnCreatedEvent.getClassSchema().getFullName());
    }

    @Nested
    class ConfiguredCompatibilityTest {

        @Test
        void shouldReportBackwardExplicitly_forAllFiveProductionSubjects() throws Exception {
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
            // subject-level compatibility override at all, unlike the 5 production subjects
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
            // identical API call, opposite outcome, because only one of the two subjects was ever
            // explicitly configured. That is what makes SCHEMA-04's "explicitly configured, not
            // inherited" claim meaningful rather than tautological.
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
            // Adds a new field WITH a default: old records missing it resolve to the default
            // under a newer reader schema -- backward-compatible by definition. The control case:
            // without it, a green "rejection" test cannot distinguish "compatibility is enforced"
            // from "registration is broken for everything".
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
            // arrange -- a throwaway subject, explicitly set to BACKWARD (mirroring
            // AvroSchemaRegistrar's own order: compatibility before the first registration) and
            // seeded with one baseline version. A brand-new subject's first version is always
            // accepted regardless of compatibility setting -- there is nothing yet to compare
            // against -- so enforcement only becomes observable from the second version onward.
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
