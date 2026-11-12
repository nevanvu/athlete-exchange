package com.athletex.trading;

import com.athletex.ledger.AccountType;
import com.athletex.ledger.LedgerService;
import com.athletex.ledger.Posting;
import com.athletex.ledger.TransactionType;
import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.market.MarketStatus;
import com.athletex.market.SlateEntity;
import com.athletex.market.SlateRepository;
import com.athletex.market.SlateStatus;
import com.athletex.matching.BookSnapshot;
import com.athletex.matching.Fill;
import com.athletex.matching.MatchResult;
import com.athletex.matching.NewOrder;
import com.athletex.matching.OrderBook;
import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional core of order handling. Every public method assumes the caller holds the
 * market lock (see {@link OrderService}); the in-memory book and the database are mutated
 * in the same unit of work, and the caller rebuilds the book if the transaction fails.
 */
@Service
public class OrderExecutionService {

    private final MatchingEngineRegistry registry;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final PositionRepository positionRepository;
    private final AthleteMarketRepository marketRepository;
    private final SlateRepository slateRepository;
    private final LedgerService ledgerService;

    public OrderExecutionService(MatchingEngineRegistry registry,
                                 OrderRepository orderRepository,
                                 TradeRepository tradeRepository,
                                 PositionRepository positionRepository,
                                 AthleteMarketRepository marketRepository,
                                 SlateRepository slateRepository,
                                 LedgerService ledgerService) {
        this.registry = registry;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.positionRepository = positionRepository;
        this.marketRepository = marketRepository;
        this.slateRepository = slateRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public PlacedOrder place(UUID userId, UUID marketId, Side side, OrderType type, Long priceCents, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (type == OrderType.LIMIT && (priceCents == null || priceCents <= 0)) {
            throw new IllegalArgumentException("limit orders need a positive price");
        }
        // Row lock: a halt committed by event ingestion is either fully visible here or waits for this order.
        AthleteMarketEntity market = marketRepository.findByIdForUpdate(marketId)
                .orElseThrow(() -> new IllegalArgumentException("unknown market " + marketId));
        SlateEntity slate = slateRepository.findById(market.getSlateId()).orElseThrow();
        if (slate.getStatus() != SlateStatus.OPEN_MARKET) {
            throw new TradingRejectedException("slate is " + slate.getStatus() + "; open-market trading is not available");
        }
        if (market.getStatus() == MarketStatus.HALTED) {
            throw new TradingRejectedException("market " + marketId + " is halted");
        }

        OrderBook book = registry.book(marketId);
        OrderEntity order = new OrderEntity(userId, marketId, side, type,
                type == OrderType.LIMIT ? priceCents : null, quantity);

        if (side == Side.BUY) {
            long required = type == OrderType.LIMIT ? priceCents * quantity : sweepCost(book, quantity);
            if (required > 0) {
                ledgerService.lockFunds("order:" + order.getId() + ":lock", userId, required);
            }
            order.setEscrowCents(required);
        } else {
            PositionEntity position = positionRepository.findByUserIdAndMarketIdForUpdate(userId, marketId)
                    .orElseThrow(() -> new InsufficientSharesException(marketId, quantity, 0));
            position.lock(quantity);
            order.setLockedShares(quantity);
        }
        // Pre-assigned ids make save() a merge, which returns the managed copy; keep mutating that one.
        order = orderRepository.save(order);

        MatchResult result = book.submit(new NewOrder(order.getId().toString(), side, type,
                type == OrderType.LIMIT ? priceCents : 0L, quantity));

        List<TradeEntity> trades = new ArrayList<>();
        for (Fill fill : result.fills()) {
            trades.add(settleFill(order, fill, market));
        }
        if (result.cancelledQuantity() > 0) {
            releaseRemainder(order);
            order.cancel();
        }
        return new PlacedOrder(order, trades);
    }

    @Transactional
    public OrderEntity cancel(UUID userId, UUID orderId) {
        OrderEntity order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new IllegalArgumentException("unknown order " + orderId));
        if (!order.getStatus().isResting()) {
            throw new TradingRejectedException("order is " + order.getStatus() + " and cannot be cancelled");
        }
        registry.book(order.getMarketId()).cancel(orderId.toString());
        releaseRemainder(order);
        order.cancel();
        return order;
    }

    /**
     * Cancels every resting order in the market (slate close / void). Caller holds the market lock.
     * Runs in its own transaction because the lifecycle invokes it from an after-commit callback,
     * where joining the (already committed) surrounding transaction would silently lose the writes.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cancelAllResting(UUID marketId, String reason) {
        List<OrderEntity> resting = orderRepository.findByMarketIdAndStatusInOrderByCreatedAtAsc(
                marketId, List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED));
        OrderBook book = registry.book(marketId);
        for (OrderEntity order : resting) {
            book.cancel(order.getId().toString());
            releaseRemainder(order);
            order.cancel();
        }
        return resting.size();
    }

    private TradeEntity settleFill(OrderEntity taker, Fill fill, AthleteMarketEntity market) {
        OrderEntity maker = orderRepository.findById(UUID.fromString(fill.makerOrderId()))
                .orElseThrow(() -> new IllegalStateException("maker order missing: " + fill.makerOrderId()));
        OrderEntity buy = taker.getSide() == Side.BUY ? taker : maker;
        OrderEntity sell = taker.getSide() == Side.BUY ? maker : taker;
        long price = fill.priceCents();
        int quantity = fill.quantity();
        long notional = price * quantity;

        TradeEntity trade = tradeRepository.save(new TradeEntity(market.getId(), taker.getId(), maker.getId(),
                buy.getUserId(), sell.getUserId(), price, quantity));

        // Cash: buyer's escrow pays the seller's cash account.
        ledgerService.post("trade:" + trade.getId(), TransactionType.TRADE_SETTLEMENT, List.of(
                new Posting(ledgerService.accountIdOf(buy.getUserId(), AccountType.USER_ESCROW), -notional),
                new Posting(ledgerService.accountIdOf(sell.getUserId(), AccountType.USER_CASH), notional)));
        buy.setEscrowCents(buy.getEscrowCents() - notional);

        // A taker buy escrowed at its own limit but filled at the (lower) maker price: refund the difference.
        if (buy == taker && taker.getType() == OrderType.LIMIT && taker.getPriceCents() > price) {
            long improvement = (taker.getPriceCents() - price) * quantity;
            ledgerService.releaseFunds("trade:" + trade.getId() + ":improvement", buy.getUserId(), improvement);
            buy.setEscrowCents(buy.getEscrowCents() - improvement);
        }

        // Shares: seller delivers from locked inventory, buyer acquires at the trade price.
        PositionEntity sellerPosition = positionRepository
                .findByUserIdAndMarketIdForUpdate(sell.getUserId(), market.getId())
                .orElseThrow(() -> new IllegalStateException("seller has no position in " + market.getId()));
        sellerPosition.deliverLocked(quantity, price);
        sell.setLockedShares(sell.getLockedShares() - quantity);
        PositionEntity buyerPosition = positionRepository
                .findByUserIdAndMarketIdForUpdate(buy.getUserId(), market.getId())
                .orElseGet(() -> positionRepository.save(new PositionEntity(buy.getUserId(), market.getId())));
        buyerPosition.acquire(quantity, price);

        buy.recordFill(quantity);
        sell.recordFill(quantity);
        market.setLastTradePriceCents(price);
        return trade;
    }

    private void releaseRemainder(OrderEntity order) {
        if (order.getSide() == Side.BUY) {
            if (order.getEscrowCents() > 0) {
                ledgerService.releaseFunds("order:" + order.getId() + ":release", order.getUserId(), order.getEscrowCents());
                order.setEscrowCents(0);
            }
        } else if (order.getLockedShares() > 0) {
            PositionEntity position = positionRepository
                    .findByUserIdAndMarketIdForUpdate(order.getUserId(), order.getMarketId())
                    .orElseThrow();
            position.unlock(order.getLockedShares());
            order.setLockedShares(0);
        }
    }

    /** Cash a market buy needs to sweep the current asks for {@code quantity} shares. */
    private static long sweepCost(OrderBook book, int quantity) {
        long cost = 0;
        int remaining = quantity;
        for (BookSnapshot.Level level : book.snapshot().asks()) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(remaining, level.quantity());
            cost += take * level.priceCents();
            remaining -= take;
        }
        return cost;
    }
}
