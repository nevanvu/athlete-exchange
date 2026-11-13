package com.athletex.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final Duration retention;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                       @Value("${athletex.outbox.retention:PT1H}") Duration retention) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.retention = retention;
    }

    /** Published rows are only kept for troubleshooting; the market maker alone writes several a second. */
    @Scheduled(fixedDelayString = "${athletex.outbox.purge-ms:300000}")
    @Transactional
    public int purgePublished() {
        return repository.deletePublishedBefore(Instant.now().minus(retention));
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
