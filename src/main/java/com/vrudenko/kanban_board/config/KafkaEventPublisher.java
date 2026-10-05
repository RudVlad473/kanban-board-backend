package com.vrudenko.kanban_board.config;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.event.ActivityEvent;
import com.vrudenko.kanban_board.event.avro.ActivityEventAvroMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Send each {@link ActivityEvent} published inside a transaction to {@link KafkaTopics#ACTIVITY}
 * strictly after commit.
 *
 * <p>A committed mutation's HTTP outcome therefore never depends on Kafka reachability. This is the
 * only place in {@code src/main} that touches the Kafka client API. A failed send is logged, never
 * swallowed; the mutation has already succeeded and returned by then.
 *
 * <p>Decisions:
 *
 * <p>Dispatch is {@code @Async} onto the {@code kafkaPublishExecutor} pool ({@link AsyncConfig}):
 * {@code KafkaTemplate.send()} blocks its caller inside {@code KafkaProducer.doSend ->
 * waitOnMetadata} for up to {@code max.block.ms} before returning its future, so the AFTER_COMMIT
 * thread (the request thread in production, the fixture-setup thread in tests) stalled for that
 * bound on every mutation. Without {@code @Async} this was a real 20 to 25 minute full-suite hang.
 *
 * <p>No try/catch wraps the Avro mapping ({@link ActivityEventAvroMapper}) or the send: a
 * registry-down or schema-rejected failure is the same class as a broker-down one, and Confluent's
 * serializer wraps both in a Kafka {@code SerializationException} that becomes a failed future
 * rather than a synchronous throw, so the {@code whenComplete} callback already catches it.
 */
@Component
public class KafkaEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private ActivityEventAvroMapper activityEventAvroMapper;

    @Async("kafkaPublishExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivityEvent(ActivityEvent event) {
        // The chained future from whenComplete() is deliberately unused; assigning it to `unused`
        // tells ErrorProne's FutureReturnValueIgnored check that dropping it is intended.
        var unused =
                kafkaTemplate
                        .send(
                                KafkaTopics.ACTIVITY,
                                event.eventId().toString(),
                                activityEventAvroMapper.toAvro(event))
                        .whenComplete(
                                (result, ex) -> {
                                    if (ex != null) {
                                        log.error(
                                                "Failed to publish {} (eventId={}, boardId={}) to {}",
                                                event.getClass().getSimpleName(),
                                                event.eventId(),
                                                event.boardId(),
                                                KafkaTopics.ACTIVITY,
                                                ex);
                                    }
                                });
    }
}
