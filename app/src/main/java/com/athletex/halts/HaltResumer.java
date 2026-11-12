package com.athletex.halts;

import com.athletex.marketmaker.MarketMakerService;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reopens halted markets once their minimum duration has passed. The house's quotes are pulled
 * first, in their own transactions, so nothing priced before the halt is left on the book when
 * matching restarts (users' own resting orders are theirs to manage).
 */
@Service
public class HaltResumer {

    private static final Logger log = LoggerFactory.getLogger(HaltResumer.class);

    private final HaltService haltService;
    private final MarketMakerService marketMaker;

    public HaltResumer(HaltService haltService, MarketMakerService marketMaker) {
        this.haltService = haltService;
        this.marketMaker = marketMaker;
    }

    public int resumeDue(Instant now) {
        int resumed = 0;
        for (UUID marketId : haltService.dueForResumption(now)) {
            try {
                marketMaker.pullQuotes(marketId);
                if (haltService.resume(marketId, now)) {
                    resumed++;
                }
            } catch (RuntimeException e) {
                log.warn("could not resume market {}; will retry: {}", marketId, e.getMessage());
            }
        }
        return resumed;
    }
}
