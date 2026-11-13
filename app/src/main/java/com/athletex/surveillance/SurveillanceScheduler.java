package com.athletex.surveillance;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "athletex.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SurveillanceScheduler {

    private final SurveillanceService surveillance;

    public SurveillanceScheduler(SurveillanceService surveillance) {
        this.surveillance = surveillance;
    }

    @Scheduled(fixedDelayString = "${athletex.surveillance.scan-ms:60000}")
    public void scan() {
        surveillance.scanRoundTrips(Instant.now());
    }
}
