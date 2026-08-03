---
phase: 03-activity-log-consumer-reliability-read-api
reviewed: 2026-08-02T00:00:00Z
depth: standard
files_reviewed: 20
files_reviewed_list:
  - src/main/java/com/vrudenko/kanban_board/entity/ActivityAction.java
  - src/main/java/com/vrudenko/kanban_board/entity/ActivityLogEntity.java
  - src/main/java/com/vrudenko/kanban_board/repository/ActivityLogRepository.java
  - src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogRecorder.java
  - src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java
  - src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java
  - docs/plans/backend-modernization/03-activity-log-ddl.sql
  - src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java
  - src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java
  - src/main/java/com/vrudenko/kanban_board/constant/KafkaTopics.java
  - src/main/java/com/vrudenko/kanban_board/constant/ValidationConstants.java
  - src/main/resources/application.properties
  - src/main/resources/application-test.properties
  - src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogIdempotencyE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogDeadLetterE2ETest.java
  - src/main/java/com/vrudenko/kanban_board/dto/activity_dto/ActivityLogResponseDTO.java
  - src/main/java/com/vrudenko/kanban_board/mapper/ActivityLogMapper.java
  - src/main/java/com/vrudenko/kanban_board/service/ActivityLogService.java
  - src/main/java/com/vrudenko/kanban_board/controller/ActivityController.java
  - src/test/java/com/vrudenko/kanban_board/e2e/activity/ActivityReadE2ETest.java
  - src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java
findings:
  critical: 1
  warning: 3
  info: 2
  total: 6
status: issues_found
---

# Phase 3: Code Review Report

**Reviewed:** 2026-08-02T00:00:00Z
**Depth:** standard
**Files Reviewed:** 20
**Status:** issues_found

## Summary

Reviewed the activity-log consumer, reliability (dead-letter/retry), and read-API source for Phase 3. The bulk of this phase is unusually well-documented — the Kafka `@Primary`/`@Qualifier` bean-resolution bugs already fixed in `KafkaConsumerConfig`, the `LinkedHashMap`-vs-`HashMap` serializer-routing bug, and the idempotency/dead-letter test suite are all sound and the reasoning in the Javadoc checks out against the actual code.

The one finding that matters is in `ActivityLogRecorder.record()`: it treats *every* `DataIntegrityViolationException` as the intended "redelivery raced the exists-check" case, not just a unique-constraint violation on `event_id`. Traced end to end, an `ActivityEvent` implementation (e.g. `TaskCreatedEvent`) is a plain record with no compact-constructor null checks, so a structurally-valid-but-semantically-null field (e.g. `"boardId": null`) deserializes successfully, flows into `ActivityLogEntity.setBoardId(null)`, and fails at insert with a `NOT NULL` constraint violation — a `DataIntegrityViolationException` — which this method's catch block silently absorbs as if it were a harmless duplicate. That event is gone forever: never persisted, never retried, never dead-lettered, with no log line anywhere. This directly contradicts the class's own stated invariant ("Anything that escapes this method is what `DefaultErrorHandler` retries and eventually dead-letters") and is a genuine data-loss path, not a documented tradeoff.

Also flagged: an unmanaged `ProducerFactory` constructed inline for the dead-letter template (resource-lifecycle gap), a class-level `@Validated` omission that makes `ActivityController`'s `@NotBlank` on `boardId` a no-op, and two minor code-quality items (magic numbers, duplicated group-id constant).

## Critical Issues

### CR-01: `ActivityLogRecorder.record()` swallows any constraint violation, not just the intended duplicate-eventId race

**File:** `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogRecorder.java:39-44`

**Issue:** The catch block is typed to `DataIntegrityViolationException` broadly:

```java
try {
    activityLogRepository.saveAndFlush(entry);
} catch (DataIntegrityViolationException e) {
    // Backstop: the exists-check above raced with a concurrent redelivery and lost.
    // Still a duplicate, still completed normally per D-05 -- never escapes this method.
}
```

`DataIntegrityViolationException` is Spring's translation for the entire SQL "integrity constraint violation" class (23xxx), which includes `NOT NULL` violations, string-length violations, and any other constraint on this table — not only the `uk_activity_log_event_id` unique constraint the comment describes.

This is reachable, not theoretical: `ActivityEvent` implementations (see `TaskCreatedEvent.java`) are plain Java records with no compact-constructor validation, so a JSON payload with a structurally valid but semantically null field, e.g. `{"eventId":"...", "boardId": null, ...}` combined with a valid type header, deserializes without error via `ErrorHandlingDeserializer`/`JsonDeserializer` (this path only rejects *unparseable* JSON, not null fields — see `ActivityLogDeadLetterE2ETest`, which only exercises malformed-JSON poison, never a null-field one). `ActivityLogConsumer.onActivityEvent` then does `entity.setBoardId(event.boardId())` unconditionally, and `ActivityLogEntity.boardId` is `@Column(nullable = false)`. The resulting insert throws a `NOT NULL`-violation `DataIntegrityViolationException`, which this catch block absorbs identically to the intended unique-constraint race — the event is dropped with zero trace: not persisted, not retried, not dead-lettered, no log line.

This directly contradicts the class's own Javadoc invariant: "Anything that escapes this method is what `DefaultErrorHandler` retries and eventually dead-letters... a duplicate escaping here would exhaust three retries and pollute the dead-letter topic" — the inverse failure mode (a genuine poison message being silently absorbed as a duplicate) is worse than the one this method was built to prevent, and no test in this phase's suite exercises it (`ActivityLogDeadLetterE2ETest` only covers unparseable-JSON poison; `ActivityLogIdempotencyE2ETest` only covers true `event_id` duplicates).

**Fix:** Narrow the catch to the specific constraint being raced, e.g. by checking the root cause's constraint name/SQL state before treating it as a harmless duplicate, and rethrow otherwise:

```java
try {
    activityLogRepository.saveAndFlush(entry);
} catch (DataIntegrityViolationException e) {
    if (!isEventIdUniqueViolation(e)) {
        throw e; // genuine data problem -- let it retry/dead-letter, do not mask it
    }
    // Backstop: the exists-check above raced with a concurrent redelivery and lost.
}

private boolean isEventIdUniqueViolation(DataIntegrityViolationException e) {
    var message = String.valueOf(e.getMostSpecificCause().getMessage());
    return message.contains("uk_activity_log_event_id");
}
```

(Or, more robustly, re-check `activityLogRepository.existsByEventId(entry.getEventId())` inside the catch block before deciding to swallow — if it's still `false` after the failed insert, the violation was not the expected duplicate and must be rethrown.)

## Warnings

### WR-01: Dead-letter `KafkaTemplate`'s backing `ProducerFactory` is constructed manually and never becomes a managed bean

**File:** `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java:105-118`

**Issue:**

```java
@Bean
public KafkaTemplate<String, Object> deadLetterKafkaTemplate(
        ProducerFactory<Object, Object> kafkaProducerFactory) {
    ...
    var producerFactory =
            new DefaultKafkaProducerFactory<String, Object>(
                    kafkaProducerFactory.getConfigurationProperties(),
                    new StringSerializer(),
                    new DelegatingByTypeSerializer(delegates, true));
    return new KafkaTemplate<>(producerFactory);
}
```

`producerFactory` is a plain local variable, not itself a `@Bean`. `DefaultKafkaProducerFactory` implements `DisposableBean`/`SmartLifecycle` and normally has its underlying producer(s) closed by the container on context shutdown when it is a managed bean. Because this one is only reachable through the `KafkaTemplate` that wraps it, the Spring container never calls `destroy()`/`stop()` on it directly, so the extra Kafka producer (and its network connections/buffers) this bean opens is not cleanly released on context refresh/shutdown (relevant for context-caching test reruns and for graceful shutdown in production).

**Fix:** Register the producer factory as its own `@Bean` (so its lifecycle is managed) and inject it into the template bean, e.g.:

```java
@Bean
public ProducerFactory<String, Object> deadLetterProducerFactory(
        ProducerFactory<Object, Object> kafkaProducerFactory) {
    var delegates = new LinkedHashMap<Class<?>, Serializer<?>>();
    delegates.put(byte[].class, new ByteArraySerializer());
    delegates.put(Object.class, new JsonSerializer<>());
    return new DefaultKafkaProducerFactory<>(
            kafkaProducerFactory.getConfigurationProperties(),
            new StringSerializer(),
            new DelegatingByTypeSerializer(delegates, true));
}

@Bean
public KafkaTemplate<String, Object> deadLetterKafkaTemplate(
        @Qualifier("deadLetterProducerFactory") ProducerFactory<String, Object> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
}
```

### WR-02: `ActivityController` is missing class-level `@Validated`, so `@NotBlank` on `boardId` is never enforced

**File:** `src/main/java/com/vrudenko/kanban_board/controller/ActivityController.java:18-32`

**Issue:**

```java
@RestController
@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.ACTIVITY)
@PreAuthorize("isAuthenticated()")
public class ActivityController {
    ...
    public ResponseEntity<Page<ActivityLogResponseDTO>> findAllByBoardId(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String boardId,
            Pageable pageable) {
```

Method-parameter constraints such as `@NotBlank` on a `@PathVariable`/`@RequestParam` are only enforced by Spring's `MethodValidationPostProcessor` when the controller class itself carries `org.springframework.validation.annotation.Validated`. `ActivityController` does not have it (compare `BoardController`, which does). As written, `@NotBlank` here is inert: a whitespace-only `boardId` (e.g. `GET /boards/%20/activity`, a URL-encoded space, which does match the `{boardId}` path pattern) is not rejected by validation at all — it falls straight through to `ActivityLogService.findAllByBoardId`, relying entirely on `ownershipVerifierService.verifyOwnershipOfBoard` to reject it downstream (as a not-found/access-denied, not a 400). The annotation gives a false impression that input is being validated at the controller boundary.

**Fix:** Add `@Validated` at the class level, matching `BoardController`'s pattern:

```java
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.ACTIVITY)
@PreAuthorize("isAuthenticated()")
@Validated
public class ActivityController {
```

### WR-03: Returning a raw `Page<T>` from the controller relies on an unguaranteed serialization format

**File:** `src/main/java/com/vrudenko/kanban_board/controller/ActivityController.java:27`

**Issue:** `ResponseEntity<Page<ActivityLogResponseDTO>>` serializes `org.springframework.data.domain.PageImpl` directly. Spring Data itself documents this as not guaranteed to be stable JSON — the framework logs a startup warning ("Serializing PageImpl instances as-is is not supported...") and recommends wrapping in `PagedModel`/`org.springframework.data.web.PagedModel` for a stable, versioned contract. Since this is (per the properties comment) "the first paginated endpoint in this codebase," the shape used here becomes the de facto convention future paginated endpoints will copy.

**Fix:** Consider wrapping the response in `PagedModel<ActivityLogResponseDTO>` (Spring Data provides a constructor taking a `Page<T>`) to get a documented, stable JSON contract, or explicitly accept and document the `PageImpl` shape as the intentional convention if that tradeoff was already made deliberately.

## Info

### IN-01: Magic numbers in retry/backoff configuration

**File:** `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java:149`

**Issue:** `new FixedBackOff(1000L, 3L)` encodes "3 retries at ~1s" as bare literals. `docs/CODE_STYLE.md` rule 1 favors naming closed/fixed values rather than scattering literals, and the Javadoc directly above this line explains the "3" is a literal reading of requirement D-04 — a good candidate for a named constant so the requirement and the code stay visibly linked.

**Fix:**
```java
private static final long RETRY_BACKOFF_INTERVAL_MS = 1000L;
private static final long RETRY_MAX_ATTEMPTS = 3L; // D-04: "3 retries"
...
new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_BACKOFF_INTERVAL_MS, RETRY_MAX_ATTEMPTS));
```

### IN-02: Consumer group id is defined in two places that must be kept in sync by hand

**File:** `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java:31,36` and `src/main/resources/application.properties:50` / `application-test.properties:55`

**Issue:** `ActivityLogConsumer.GROUP_ID = "activity-log"` is passed explicitly to `@KafkaListener(groupId = ...)`, while `spring.kafka.consumer.group-id=activity-log` sets the same value at the property level. The annotation's explicit `groupId` wins, so the property is currently redundant. Both must be edited together if the group id ever changes, and nothing enforces that — this is a minor drift risk rather than a bug today.

**Fix:** Either drop the property (since the annotation is authoritative) or drop the annotation's `groupId` and let the property drive it, so there is exactly one place to change.

---

_Reviewed: 2026-08-02T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
