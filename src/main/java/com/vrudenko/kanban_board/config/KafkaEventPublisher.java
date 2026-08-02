package com.vrudenko.kanban_board.config;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.event.ActivityEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The only place in {@code src/main} that touches the Kafka client API. Listens for any {@link
 * ActivityEvent} published via {@code ApplicationEventPublisher} while inside a transaction, and
 * sends it to {@link KafkaTopics#ACTIVITY} strictly after that transaction commits — never during
 * it, so a committed mutation's HTTP outcome never depends on Kafka reachability (D-01). A failed
 * send is logged, never silently swallowed (D-02); the mutation itself has already succeeded and
 * returned to the caller by the time this method runs.
 *
 * <p>Dispatched via {@code @Async} onto the {@code kafkaPublishExecutor} pool ({@link
 * AsyncConfig}): {@code KafkaTemplate.send()} blocks the calling thread inside {@code
 * KafkaProducer.doSend -> waitOnMetadata} for up to {@code max.block.ms} even before returning its
 * future, so without this the AFTER_COMMIT listener thread — the request thread in production, the
 * fixture-setup thread in tests — would still stall for that bound on every mutation. Running
 * off-thread means neither production requests nor test fixture creation ever wait on Kafka
 * reachability at all.
 */
@Component
public class KafkaEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;

    // @Async fixes a real 20-25min full-suite hang (see class Javadoc): without it this method
    // blocks its caller inside KafkaTemplate.send() regardless of the bounded producer timeout.
    @Async("kafkaPublishExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivityEvent(ActivityEvent event) {
        // The failure path is already handled inside whenComplete (logged above), and this
        // listener has nothing further to do with either outcome - so the chained Future
        // returned by whenComplete() is deliberately unused, not accidentally dropped. Assigning
        // it to `unused` documents that intent to ErrorProne's FutureReturnValueIgnored check.
        var unused =
                kafkaTemplate
                        .send(KafkaTopics.ACTIVITY, event.eventId().toString(), event)
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
