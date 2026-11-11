package com.athletex.trading;

import com.athletex.matching.NewOrder;
import com.athletex.matching.OrderBook;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Holds one in-memory {@link OrderBook} per athlete market plus the lock that serializes
 * all mutations of that market. The database is the source of truth: books are rebuilt
 * from resting orders on startup and whenever an execution fails after touching a book.
 */
@Component
public class MatchingEngineRegistry {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngineRegistry.class);
    private static final List<OrderStatus> RESTING = List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED);

    private final OrderRepository orderRepository;
    private final Map<UUID, OrderBook> books = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public MatchingEngineRegistry(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /** Must only be called while holding the market lock. */
    public OrderBook book(UUID marketId) {
        return books.computeIfAbsent(marketId, id -> new OrderBook());
    }

    public <T> T withMarketLock(UUID marketId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(marketId, id -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Replaces the market's book with one replayed from resting orders. Caller holds the market lock. */
    public void rebuildMarket(UUID marketId) {
        OrderBook fresh = new OrderBook();
        replay(fresh, orderRepository.findByMarketIdAndStatusInOrderByCreatedAtAsc(marketId, RESTING));
        books.put(marketId, fresh);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildAll() {
        Map<UUID, List<OrderEntity>> byMarket = orderRepository.findByStatusInOrderByCreatedAtAsc(RESTING).stream()
                .collect(Collectors.groupingBy(OrderEntity::getMarketId));
        books.clear();
        byMarket.forEach((marketId, orders) -> withMarketLock(marketId, () -> {
            OrderBook book = new OrderBook();
            replay(book, orders);
            books.put(marketId, book);
            return null;
        }));
        log.info("rebuilt {} order book(s) from {} resting order(s)", byMarket.size(),
                byMarket.values().stream().mapToInt(List::size).sum());
    }

    private static void replay(OrderBook book, List<OrderEntity> restingOrders) {
        for (OrderEntity order : restingOrders) {
            // Resting orders never crossed when they were persisted, so replaying them in
            // arrival order produces no fills and restores price-time priority exactly.
            var result = book.submit(NewOrder.limit(order.getId().toString(), order.getSide(),
                    order.getPriceCents(), order.getRemainingQuantity()));
            if (!result.fills().isEmpty()) {
                throw new IllegalStateException("resting orders crossed during rebuild; order " + order.getId());
            }
        }
    }
}
