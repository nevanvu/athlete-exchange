package com.athletex.trading;

import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Public entry point for orders: serializes each market and keeps the in-memory book
 * consistent with the database by rebuilding it whenever an execution fails.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final MatchingEngineRegistry registry;
    private final OrderExecutionService execution;
    private final OrderRepository orderRepository;

    public OrderService(MatchingEngineRegistry registry, OrderExecutionService execution, OrderRepository orderRepository) {
        this.registry = registry;
        this.execution = execution;
        this.orderRepository = orderRepository;
    }

    public PlacedOrder place(UUID userId, UUID marketId, Side side, OrderType type, Long priceCents, int quantity) {
        return registry.withMarketLock(marketId,
                () -> guarded(marketId, () -> execution.place(userId, marketId, side, type, priceCents, quantity)));
    }

    public OrderEntity cancel(UUID userId, UUID orderId) {
        UUID marketId = orderRepository.findByIdAndUserId(orderId, userId)
                .map(OrderEntity::getMarketId)
                .orElseThrow(() -> new IllegalArgumentException("unknown order " + orderId));
        return registry.withMarketLock(marketId, () -> guarded(marketId, () -> execution.cancel(userId, orderId)));
    }

    public int cancelAllResting(UUID marketId, String reason) {
        return registry.withMarketLock(marketId,
                () -> guarded(marketId, () -> execution.cancelAllResting(marketId, reason)));
    }

    /**
     * The transaction has already rolled back by the time the exception reaches us, so the
     * database is the pre-failure state and the book can be replayed from it.
     */
    private <T> T guarded(UUID marketId, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            log.debug("execution failed for market {}; rebuilding book: {}", marketId, e.getMessage());
            registry.rebuildMarket(marketId);
            throw e;
        }
    }
}
