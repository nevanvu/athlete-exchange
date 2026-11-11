package com.athletex.market;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "athletex.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SlateScheduler {

    private final SlateLifecycleService lifecycleService;

    public SlateScheduler(SlateLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${athletex.scheduler.fixed-delay-ms:5000}")
    public void tick() {
        lifecycleService.advanceDueSlates(Instant.now());
    }
}
