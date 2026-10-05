package com.vrudenko.kanban_board.config;

import java.io.IOException;
import java.util.List;

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

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Register all 14 Avro schemas against a Confluent-API-compatible Schema Registry, setting BACKWARD
 * compatibility explicitly on each subject before its first version.
 *
 * <p>Deliberately carries no Spring stereotype: it is invoked from the {@code registerSchemas}
 * Gradle task (build/CI) and from {@link
 * com.vrudenko.kanban_board.activitylog.AbstractKafkaContainerTest}'s static initializer, never by
 * the running application, so component scan must not pick it up.
 *
 * <p>Decisions:
 *
 * <p>This is the one place that writes schemas to a registry. The producer ({@code
 * auto.register.schemas=false} in {@code application.properties}) can only look schemas up, so a
 * producer with a drifted schema fails loudly instead of silently creating a new version.
 *
 * <p>Schemas come from the generated classes' {@code getClassSchema()}, never the {@code .avsc}
 * files: {@code src/main/avro/} is not a resource directory, so those files are not on the runtime
 * classpath, and registering the schema the generated code actually encodes with makes registering
 * one that differs from what the producer emits structurally impossible. Subjects come from {@code
 * schema.getFullName()}: under {@code RecordNameStrategy} the subject is the record's full name, so
 * a schema rename cannot silently orphan a subject.
 */
public final class AvroSchemaRegistrar {
    private static final Logger log = LoggerFactory.getLogger(AvroSchemaRegistrar.class);

    private static final String BACKWARD_COMPATIBILITY = "BACKWARD";

    // Confluent's own default identity-map capacity for CachedSchemaRegistryClient.
    private static final int IDENTITY_MAP_CAPACITY = 100;

    private static final String DEFAULT_SCHEMA_REGISTRY_URL = "http://localhost:8081";

    private static final List<Schema> SCHEMAS =
            List.of(
                    AvroTaskCreatedEvent.getClassSchema(),
                    AvroTaskMovedEvent.getClassSchema(),
                    AvroTaskDeletedEvent.getClassSchema(),
                    AvroTaskUpdatedEvent.getClassSchema(),
                    AvroBoardCreatedEvent.getClassSchema(),
                    AvroBoardUpdatedEvent.getClassSchema(),
                    AvroBoardDeletedEvent.getClassSchema(),
                    AvroColumnCreatedEvent.getClassSchema(),
                    AvroColumnDeletedEvent.getClassSchema(),
                    AvroColumnUpdatedEvent.getClassSchema(),
                    AvroColumnReorderedEvent.getClassSchema(),
                    AvroSubtaskCreatedEvent.getClassSchema(),
                    AvroSubtaskUpdatedEvent.getClassSchema(),
                    AvroSubtaskDeletedEvent.getClassSchema());

    private AvroSchemaRegistrar() {}

    /**
     * Register every schema against {@code schemaRegistryUrl}, setting BACKWARD compatibility on
     * each subject first.
     *
     * <p>Idempotent: registering an unchanged schema returns its existing id and re-setting an
     * unchanged compatibility level is a no-op write, which matters because the Gradle task and
     * every test class sharing the harness call this repeatedly.
     */
    public static void registerAll(String schemaRegistryUrl) {
        SchemaRegistryClient client =
                new CachedSchemaRegistryClient(schemaRegistryUrl, IDENTITY_MAP_CAPACITY);
        for (Schema schema : SCHEMAS) {
            registerOne(client, schema);
        }
    }

    private static void registerOne(SchemaRegistryClient client, Schema schema) {
        String subject = schema.getFullName();
        try {
            // Set BACKWARD BEFORE the first registration, or that subject's first version is
            // ungoverned by any explicit compatibility check for the window in between.
            client.updateCompatibility(subject, BACKWARD_COMPATIBILITY);
            client.register(subject, schema);
        } catch (IOException | RestClientException e) {
            // Some registry implementations reject a compatibility write against a subject with no
            // schema yet. Fall back to register-then-set: the acceptance criterion is the end
            // state (schema registered, BACKWARD in force), not the call order.
            log.debug(
                    "updateCompatibility-before-register failed for subject {}, falling back to"
                            + " register-then-set",
                    subject,
                    e);
            registerThenSetCompatibility(client, subject, schema);
        }
    }

    private static void registerThenSetCompatibility(
            SchemaRegistryClient client, String subject, Schema schema) {
        try {
            client.register(subject, schema);
            client.updateCompatibility(subject, BACKWARD_COMPATIBILITY);
        } catch (IOException | RestClientException fallbackEx) {
            throw new IllegalStateException(
                    "Failed to register Avro schema for subject " + subject, fallbackEx);
        }
    }

    /**
     * Build/CI entry point (the {@code registerSchemas} Gradle task). Reads the registry URL from
     * the first CLI argument, falling back to the {@code SCHEMA_REGISTRY_URL} environment variable,
     * falling back to {@code http://localhost:8081}.
     */
    public static void main(String[] args) {
        String url =
                args.length > 0 && !args[0].isBlank()
                        ? args[0]
                        : System.getenv()
                                .getOrDefault("SCHEMA_REGISTRY_URL", DEFAULT_SCHEMA_REGISTRY_URL);
        registerAll(url);
        log.info("Registered {} Avro schemas against {}", SCHEMAS.size(), url);
    }
}
