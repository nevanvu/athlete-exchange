package com.athletex.trading;

import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.market.SlateLifecycleHooks;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Plugs IPO clearing and order cleanup into the slate state machine. */
@Component
public class TradingLifecycleHooks implements SlateLifecycleHooks {

    private static final Logger log = LoggerFactory.getLogger(TradingLifecycleHooks.class);

    private final IpoService ipoService;
    private final OrderService orderService;
    private final AthleteMarketRepository marketRepository;

    public TradingLifecycleHooks(IpoService ipoService, OrderService orderService,
                                 AthleteMarketRepository marketRepository) {
        this.ipoService = ipoService;
        this.orderService = orderService;
        this.marketRepository = marketRepository;
    }

    @Override
    public void onIpoClosing(UUID slateId) {
        ipoService.clearSlate(slateId);
    }

    @Override
    public void onSlateClosed(UUID slateId) {
        cancelAll(slateId, "slate closed");
    }

    @Override
    public void onSlateVoiding(UUID slateId) {
        ipoService.refundOpenBids(slateId);
    }

    @Override
    public void onSlateVoided(UUID slateId) {
        cancelAll(slateId, "slate voided");
    }

    private void cancelAll(UUID slateId, String reason) {
        int cancelled = 0;
        for (AthleteMarketEntity market : marketRepository.findBySlateId(slateId)) {
            cancelled += orderService.cancelAllResting(market.getId(), reason);
        }
        log.info("slate {}: cancelled {} resting order(s) ({})", slateId, cancelled, reason);
    }
}
