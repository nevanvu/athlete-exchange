package com.athletex.trading;

import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.market.MarketStatus;
import com.athletex.matching.BookSnapshot;
import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TradingController {

    public record PlaceOrderRequest(@NotNull UUID marketId, @NotNull Side side, @NotNull OrderType type,
                                    Long priceCents, @Min(1) int quantity) {
    }

    public record TradeView(UUID id, long priceCents, int quantity, Instant executedAt) {
    }

    public record OrderView(UUID id, UUID marketId, Side side, OrderType type, Long priceCents, int quantity,
                            int filledQuantity, OrderStatus status, long escrowCents, Instant createdAt) {
    }

    public record PlacedOrderView(OrderView order, List<TradeView> trades, boolean selfTradePrevented) {
    }

    public record BookView(UUID marketId, MarketStatus status, String haltReason, Instant resumeAfter,
                           Long lastTradePriceCents, Long ipoClearingPriceCents, Long fairValueCents,
                           List<BookSnapshot.Level> bids, List<BookSnapshot.Level> asks) {
    }

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final AthleteMarketRepository marketRepository;
    private final MatchingEngineRegistry registry;

    public TradingController(OrderService orderService, OrderRepository orderRepository,
                             TradeRepository tradeRepository, AthleteMarketRepository marketRepository,
                             MatchingEngineRegistry registry) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.marketRepository = marketRepository;
        this.registry = registry;
    }

    @PostMapping("/orders")
    public ResponseEntity<PlacedOrderView> place(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody PlaceOrderRequest request) {
        PlacedOrder placed = orderService.place(userId(jwt), request.marketId(), request.side(), request.type(),
                request.priceCents(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(new PlacedOrderView(
                toView(placed.order()), placed.trades().stream().map(TradingController::toView).toList(),
                placed.selfTradePrevented()));
    }

    @GetMapping("/orders")
    public List<OrderView> myOrders(@AuthenticationPrincipal Jwt jwt) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId(jwt)).stream()
                .map(TradingController::toView).toList();
    }

    @DeleteMapping("/orders/{id}")
    public OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return toView(orderService.cancel(userId(jwt), id));
    }

    @GetMapping("/markets/{id}/book")
    public BookView book(@PathVariable UUID id) {
        AthleteMarketEntity market = marketRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown market " + id));
        BookSnapshot snapshot = registry.withMarketLock(id, () -> registry.book(id).snapshot());
        return new BookView(id, market.getStatus(), market.getHaltReason(), market.getResumeAfter(),
                market.getLastTradePriceCents(), market.getIpoClearingPriceCents(), market.getFairValueCents(),
                snapshot.bids(), snapshot.asks());
    }

    @GetMapping("/markets/{id}/trades")
    public List<TradeView> trades(@PathVariable UUID id) {
        return tradeRepository.findTop50ByMarketIdOrderByExecutedAtDesc(id).stream()
                .map(TradingController::toView).toList();
    }

    static OrderView toView(OrderEntity o) {
        return new OrderView(o.getId(), o.getMarketId(), o.getSide(), o.getType(), o.getPriceCents(),
                o.getQuantity(), o.getFilledQuantity(), o.getStatus(), o.getEscrowCents(), o.getCreatedAt());
    }

    static TradeView toView(TradeEntity t) {
        return new TradeView(t.getId(), t.getPriceCents(), t.getQuantity(), t.getExecutedAt());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
