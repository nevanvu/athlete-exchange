package com.athletex.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "athletex.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Publishes pending rows in order; a failure leaves the row unpublished for the next pass. */
    @Scheduled(fixedDelayString = "${athletex.outbox.relay.delay-ms:1000}")
    @Transactional
    public int relay() {
        List<OutboxEntity> pending = repository.findTop100ByPublishedAtIsNullOrderBySeqAsc();
        int published = 0;
        for (OutboxEntity row : pending) {
            try {
                kafkaTemplate.send(row.getTopic(), row.getMessageKey(), row.getPayload()).get(10, TimeUnit.SECONDS);
                row.markPublished();
                published++;
            } catch (Exception e) {
                log.warn("outbox relay stopped at {}: {}", row.getId(), e.getMessage());
                break;
            }
        }
        return published;
    }
}
