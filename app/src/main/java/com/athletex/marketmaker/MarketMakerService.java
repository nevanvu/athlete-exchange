package com.athletex.marketmaker;

import com.athletex.fairvalue.FairValueTickEntity;
import com.athletex.fairvalue.FairValueTickRepository;
import com.athletex.ledger.InsufficientFundsException;
import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.market.MarketStatus;
import com.athletex.market.SlateEntity;
import com.athletex.market.SlateRepository;
import com.athletex.market.SlateStatus;
import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import com.athletex.trading.InsufficientSharesException;
import com.athletex.trading.OrderEntity;
import com.athletex.trading.OrderRepository;
import com.athletex.trading.OrderService;
import com.athletex.trading.OrderStatus;
import com.athletex.trading.PositionEntity;
import com.athletex.trading.PositionRepository;
import com.athletex.trading.TradingRejectedException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * House liquidity: keeps one bid and one ask around each open market's fair value, trading the
 * house's own inventory (unsold IPO shares) and cash through the normal order path. The spread
 * widens with recent fair-value volatility; buying stops at an inventory cap and selling at zero
 * inventory. Quotes are pulled while a market is halted and replaced only when they change.
 */
@Service
public class MarketMakerService {

    private static final Logger log = LoggerFactory.getLogger(MarketMakerService.class);
    private static final UUID HOUSE = PositionEntity.HOUSE_USER_ID;
    private static final List<OrderStatus> RESTING = List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED);

    public record Settings(int quoteSize, int maxLong, double baseSpread, double maxSpread, int volatilityWindow) {
    }

    /** One side is null when the house cannot (or should not) quote it. */
    public record Quote(Long bidCents, Integer bidSize, Long askCents, Integer askSize, double spread) {
    }

    private final AthleteMarketRepository marketRepository;
    private final SlateRepository slateRepository;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final FairValueTickRepository tickRepository;
    private final Settings defaults;

    public MarketMakerService(AthleteMarketRepository marketRepository,
                              SlateRepository slateRepository,
                              PositionRepository positionRepository,
                              OrderRepository orderRepository,
                              OrderService orderService,
                              FairValueTickRepository tickRepository,
                              @Value("${athletex.market-maker.quote-size:50}") int quoteSize,
                              @Value("${athletex.market-maker.max-long:2000}") int maxLong,
                              @Value("${athletex.market-maker.base-spread:0.02}") double baseSpread,
                              @Value("${athletex.market-maker.max-spread:0.05}") double maxSpread,
                              @Value("${athletex.market-maker.volatility-window:10}") int volatilityWindow) {
        this.marketRepository = marketRepository;
        this.slateRepository = slateRepository;
        this.positionRepository = positionRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.tickRepository = tickRepository;
        this.defaults = new Settings(quoteSize, maxLong, baseSpread, maxSpread, volatilityWindow);
    }

    public Settings defaults() {
        return defaults;
    }

    public void quoteAll() {
        for (SlateEntity slate : slateRepository.findByStatus(SlateStatus.OPEN_MARKET)) {
            for (AthleteMarketEntity market : marketRepository.findBySlateId(slate.getId())) {
                try {
                    quoteMarket(market.getId(), defaults);
                } catch (RuntimeException e) {
                    log.warn("market maker could not quote market {}: {}", market.getId(), e.getMessage());
                }
            }
        }
    }

    public Quote quoteMarket(UUID marketId) {
        return quoteMarket(marketId, defaults);
    }

    public Quote quoteMarket(UUID marketId, Settings settings) {
        AthleteMarketEntity market = marketRepository.findById(marketId).orElseThrow();
        SlateStatus slateStatus = slateRepository.findById(market.getSlateId()).orElseThrow().getStatus();
        if (slateStatus != SlateStatus.OPEN_MARKET || market.getStatus() == MarketStatus.HALTED
                || market.getFairValueCents() == null) {
            pullQuotes(marketId);
            return null;
        }

        Quote desired = desiredQuote(market, settings);
        List<OrderEntity> mine = houseOrders(marketId);
        if (alreadyQuoted(mine, desired)) {
            return desired;
        }
        pullQuotes(marketId);
        if (desired.bidCents() != null) {
            place(marketId, Side.BUY, desired.bidCents(), desired.bidSize());
        }
        if (desired.askCents() != null) {
            place(marketId, Side.SELL, desired.askCents(), desired.askSize());
        }
        return desired;
    }

    /** Cancels every resting house order in the market. */
    public void pullQuotes(UUID marketId) {
        for (OrderEntity order : houseOrders(marketId)) {
            try {
                orderService.cancel(HOUSE, order.getId());
            } catch (TradingRejectedException e) {
                // filled or cancelled since we looked: nothing left to pull
            }
        }
    }

    Quote desiredQuote(AthleteMarketEntity market, Settings settings) {
        long fairValue = market.getFairValueCents();
        double spread = Math.min(settings.maxSpread(),
                settings.baseSpread() + 2 * recentVolatility(market.getId(), settings.volatilityWindow()));
        long bid = Math.max(1, (long) Math.floor(fairValue * (1 - spread)));
        long ask = Math.max(bid + 1, (long) Math.ceil(fairValue * (1 + spread)));

        int held = positionRepository.findByUserIdAndMarketId(HOUSE, market.getId())
                .map(PositionEntity::getQuantity).orElse(0);
        boolean canBuy = held + settings.quoteSize() <= settings.maxLong();
        int askSize = Math.min(settings.quoteSize(), held);
        return new Quote(canBuy ? bid : null, canBuy ? settings.quoteSize() : null,
                askSize > 0 ? ask : null, askSize > 0 ? askSize : null, spread);
    }

    /** Root-mean-square of the relative changes over the last few fair-value ticks. */
    double recentVolatility(UUID marketId, int window) {
        List<FairValueTickEntity> ticks = tickRepository.findTop200ByMarketIdOrderByIdDesc(marketId);
        int n = Math.min(window, ticks.size() - 1);
        if (n <= 0) {
            return 0.0;
        }
        double sumSquares = 0;
        for (int i = 0; i < n; i++) {
            double newer = ticks.get(i).getFairValueCents();
            double older = ticks.get(i + 1).getFairValueCents();
            double change = (newer - older) / older;
            sumSquares += change * change;
        }
        return Math.sqrt(sumSquares / n);
    }

    private boolean alreadyQuoted(List<OrderEntity> mine, Quote desired) {
        int expected = (desired.bidCents() != null ? 1 : 0) + (desired.askCents() != null ? 1 : 0);
        if (mine.size() != expected) {
            return false;
        }
        for (OrderEntity order : mine) {
            boolean buy = order.getSide() == Side.BUY;
            Long price = buy ? desired.bidCents() : desired.askCents();
            Integer size = buy ? desired.bidSize() : desired.askSize();
            if (price == null || !price.equals(order.getPriceCents()) || order.getRemainingQuantity() != size) {
                return false;
            }
        }
        return true;
    }

    private void place(UUID marketId, Side side, long priceCents, int quantity) {
        try {
            orderService.place(HOUSE, marketId, side, OrderType.LIMIT, priceCents, quantity);
        } catch (InsufficientFundsException | InsufficientSharesException | TradingRejectedException e) {
            log.debug("market maker skipped {} {} @ {} in {}: {}", side, quantity, priceCents, marketId, e.getMessage());
        }
    }

    private List<OrderEntity> houseOrders(UUID marketId) {
        return orderRepository.findByMarketIdAndStatusInOrderByCreatedAtAsc(marketId, RESTING).stream()
                .filter(o -> o.getUserId().equals(HOUSE))
                .toList();
    }
}
