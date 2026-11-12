package com.athletex.settlement;

import com.athletex.market.SlateEntity;
import com.athletex.market.SlateRepository;
import com.athletex.market.SlateStatus;
import com.athletex.market.SlateTransitionEntity;
import com.athletex.market.SlateTransitionRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles closed slates once a short grace period has passed (so late box-score lines are in),
 * retrying every poll while a slate is not ready.
 */
@Component
@ConditionalOnProperty(name = "athletex.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SlateRepository slateRepository;
    private final SlateTransitionRepository transitionRepository;
    private final SettlementService settlementService;
    private final Duration delay;

    public SettlementScheduler(SlateRepository slateRepository,
                               SlateTransitionRepository transitionRepository,
                               SettlementService settlementService,
                               @Value("${athletex.settlement.delay:PT10S}") Duration delay) {
        this.slateRepository = slateRepository;
        this.transitionRepository = transitionRepository;
        this.settlementService = settlementService;
        this.delay = delay;
    }

    @Scheduled(fixedDelayString = "${athletex.settlement.poll-ms:5000}")
    public void settleDueSlates() {
        Instant now = Instant.now();
        for (SlateEntity slate : slateRepository.findByStatus(SlateStatus.CLOSED)) {
            Instant closedAt = transitionRepository.findBySlateIdOrderByOccurredAt(slate.getId()).stream()
                    .filter(t -> t.getToStatus() == SlateStatus.CLOSED)
                    .map(SlateTransitionEntity::getOccurredAt)
                    .reduce((first, second) -> second)
                    .orElse(now);
            if (now.isBefore(closedAt.plus(delay))) {
                continue;
            }
            try {
                SettlementService.Result result = settlementService.settle(slate.getId());
                if (result.outcome() == SettlementService.Outcome.NOT_READY) {
                    log.info("slate {} not ready to settle: {}", slate.getId(), result.detail());
                }
            } catch (RuntimeException e) {
                log.error("settlement of slate {} failed; will retry: {}", slate.getId(), e.getMessage(), e);
            }
        }
    }
}
