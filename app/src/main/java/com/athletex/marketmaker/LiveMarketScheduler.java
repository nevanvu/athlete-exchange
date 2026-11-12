package com.athletex.marketmaker;

import com.athletex.halts.HaltResumer;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background loops for live markets: lifting expired halts and keeping house quotes fresh. */
@Component
@ConditionalOnProperty(name = "athletex.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class LiveMarketScheduler {

    private final HaltResumer haltResumer;
    private final MarketMakerService marketMaker;
    private final boolean marketMakerEnabled;

    public LiveMarketScheduler(HaltResumer haltResumer, MarketMakerService marketMaker,
                               @Value("${athletex.market-maker.enabled:true}") boolean marketMakerEnabled) {
        this.haltResumer = haltResumer;
        this.marketMaker = marketMaker;
        this.marketMakerEnabled = marketMakerEnabled;
    }

    @Scheduled(fixedDelayString = "${athletex.halts.poll-ms:1000}")
    public void resumeExpiredHalts() {
        haltResumer.resumeDue(Instant.now());
    }

    @Scheduled(fixedDelayString = "${athletex.market-maker.quote-ms:2000}")
    public void refreshQuotes() {
        if (marketMakerEnabled) {
            marketMaker.quoteAll();
        }
    }
}
