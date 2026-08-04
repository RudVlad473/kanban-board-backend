package com.vrudenko.kanban_board.config;

import com.vrudenko.kanban_board.event.avro.AvroBoardCreatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroColumnCreatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroTaskCreatedEvent;
import com.vrudenko.kanban_board.event.avro.AvroTaskDeletedEvent;
import com.vrudenko.kanban_board.event.avro.AvroTaskMovedEvent;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import java.io.IOException;
import java.util.List;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers all 5 Avro schemas (D-03) against a Confluent-API-compatible Schema Registry, setting
 * BACKWARD compatibility (D-02, SCHEMA-04) explicitly on each subject before that subject's first
 * version is registered.
 *
 * <p>Deliberately carries no Spring stereotype annotation: this class is invoked from the {@code
 * registerSchemas} Gradle task (build/CI) and from {@link
 * com.vrudenko.kanban_board.activitylog.AbstractKafkaContainerTest}'s static initializer (test
 * setup), never by the running application, so component scan must never pick it up. This is the
 * one and only place in the codebase that writes schemas to a registry (SCHEMA-01) — the producer
 * (see {@code application.properties}' {@code auto.register.schemas=false}) can only ever look
 * schemas up, never register them, so a producer with a drifted schema fails loudly instead of
 * silently creating a new version.
 *
 * <p>Schemas are derived from the generated classes' own {@code getClassSchema()}, never by reading
 * the {@code .avsc} files directly, for two reasons: {@code src/main/avro/} is not a resource
 * directory, so those files are not on the runtime classpath at all; and, more importantly,
 * registering the schema the generated code will actually encode with makes it structurally
 * impossible to register a schema that differs from what the producer emits. Subject names are
 * likewise derived from {@code schema.getFullName()} rather than hardcoded strings — under {@code
 * RecordNameStrategy} the subject *is* the record's full name, so deriving it means a schema rename
 * can never silently orphan a subject.
 */
public final class AvroSchemaRegistrar {
    private static final Logger log = LoggerFactory.getLogger(AvroSchemaRegistrar.class);

    private static final String BACKWARD_COMPATIBILITY = "BACKWARD";

    // Confluent's own default for CachedSchemaRegistryClient's identity map capacity when
    // constructed via one of its higher-level convenience constructors.
    private static final int IDENTITY_MAP_CAPACITY = 100;

    private static final String DEFAULT_SCHEMA_REGISTRY_URL = "http://localhost:8081";

    private static final List<Schema> SCHEMAS =
            List.of(
                    AvroTaskCreatedEvent.getClassSchema(),
                    AvroTaskMovedEvent.getClassSchema(),
                    AvroTaskDeletedEvent.getClassSchema(),
                    AvroBoardCreatedEvent.getClassSchema(),
                    AvroColumnCreatedEvent.getClassSchema());

    private AvroSchemaRegistrar() {}

    /**
     * Registers all 5 schemas against {@code schemaRegistryUrl}, explicitly setting BACKWARD
     * compatibility on each subject first. Idempotent: re-running this against an
     * already-configured registry is a no-op, since registering an unchanged schema returns the
     * existing schema id and re-setting an unchanged compatibility level is itself a no-op write —
     * both the {@code registerSchemas} Gradle task and every test class sharing {@link
     * com.vrudenko.kanban_board.activitylog.AbstractKafkaContainerTest}'s harness call this
     * repeatedly against the same registry instance.
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
            // BACKWARD must be set BEFORE the first registration: setting it only after would
            // leave that subject's first version ungoverned by any explicit compatibility check
            // for the brief window in between.
            client.updateCompatibility(subject, BACKWARD_COMPATIBILITY);
            client.register(subject, schema);
        } catch (IOException | RestClientException e) {
            // Some registry implementations reject a compatibility-config write against a
            // subject that has never had a schema registered. Fall back to
            // register-then-set-then-re-read: the acceptance criterion is the end state (schema
            // registered, BACKWARD in force), not the call order.
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
