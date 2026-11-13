package com.athletex.surveillance;

import com.athletex.trading.PositionEntity;
import com.athletex.trading.TradeEntity;
import com.athletex.trading.TradeRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wash-trading signals. Trading with yourself is blocked outright by the engine (and recorded
 * here); two accounts trading the same athlete back and forth, so that shares go round in a
 * circle and little changes hands net, is the collusive version and is flagged for review.
 */
@Service
public class SurveillanceService {

    private static final Logger log = LoggerFactory.getLogger(SurveillanceService.class);

    private final SurveillanceAlertRepository alerts;
    private final TradeRepository trades;
    private final Duration window;
    private final int minTrades;
    private final double maxNetShare;

    public SurveillanceService(SurveillanceAlertRepository alerts, TradeRepository trades,
                               @Value("${athletex.surveillance.window:PT24H}") Duration window,
                               @Value("${athletex.surveillance.round-trip-min-trades:4}") int minTrades,
                               @Value("${athletex.surveillance.round-trip-max-net-share:0.2}") double maxNetShare) {
        this.alerts = alerts;
        this.trades = trades;
        this.window = window;
        this.minTrades = minTrades;
        this.maxNetShare = maxNetShare;
    }

    /** Called in the order's transaction when the engine stopped an order at the same user's resting order. */
    @Transactional
    public void selfTradeBlocked(UUID userId, UUID marketId) {
        Instant now = Instant.now();
        SurveillanceAlertEntity alert = alerts
                .findByKindAndMarketIdAndUserAAndUserB(SurveillanceAlertEntity.Kind.SELF_TRADE_BLOCKED, marketId, userId, userId)
                .orElseGet(() -> alerts.save(new SurveillanceAlertEntity(
                        SurveillanceAlertEntity.Kind.SELF_TRADE_BLOCKED, marketId, userId, userId, now)));
        int count = alert.getOccurrences() + 1;
        alert.update(count, "Order stopped " + count + (count == 1 ? " time" : " times")
                + " before it could trade against the same account's resting order.", alert.getFirstSeenAt(), now);
    }

    private record Pair(UUID market, UUID a, UUID b) {
    }

    private static final class Flow {
        int aToBTrades;
        int bToATrades;
        long aToBShares;
        long bToAShares;
        Instant first;
        Instant last;
    }

    /** Scans recent trades for round trips between pairs of users. Returns the number of alerts raised or updated. */
    @Transactional
    public int scanRoundTrips(Instant now) {
        Map<Pair, Flow> flows = new HashMap<>();
        for (TradeEntity t : trades.findByExecutedAtAfter(now.minus(window))) {
            UUID seller = t.getSellerUserId();
            UUID buyer = t.getBuyerUserId();
            if (seller.equals(buyer) || PositionEntity.HOUSE_USER_ID.equals(seller)
                    || PositionEntity.HOUSE_USER_ID.equals(buyer)) {
                continue;
            }
            boolean sellerIsA = seller.toString().compareTo(buyer.toString()) < 0;
            Pair pair = sellerIsA ? new Pair(t.getMarketId(), seller, buyer) : new Pair(t.getMarketId(), buyer, seller);
            Flow flow = flows.computeIfAbsent(pair, p -> new Flow());
            if (sellerIsA) {
                flow.aToBTrades++;
                flow.aToBShares += t.getQuantity();
            } else {
                flow.bToATrades++;
                flow.bToAShares += t.getQuantity();
            }
            flow.first = flow.first == null || t.getExecutedAt().isBefore(flow.first) ? t.getExecutedAt() : flow.first;
            flow.last = flow.last == null || t.getExecutedAt().isAfter(flow.last) ? t.getExecutedAt() : flow.last;
        }

        List<Pair> flagged = new ArrayList<>();
        flows.forEach((pair, f) -> {
            long gross = f.aToBShares + f.bToAShares;
            long net = Math.abs(f.aToBShares - f.bToAShares);
            boolean bothWays = f.aToBTrades > 0 && f.bToATrades > 0;
            if (bothWays && f.aToBTrades + f.bToATrades >= minTrades && net <= maxNetShare * gross) {
                SurveillanceAlertEntity alert = alerts
                        .findByKindAndMarketIdAndUserAAndUserB(SurveillanceAlertEntity.Kind.ROUND_TRIP, pair.market(), pair.a(), pair.b())
                        .orElseGet(() -> alerts.save(new SurveillanceAlertEntity(
                                SurveillanceAlertEntity.Kind.ROUND_TRIP, pair.market(), pair.a(), pair.b(), f.first)));
                int trades = f.aToBTrades + f.bToATrades;
                alert.update(trades, trades + " trades between the same two accounts in "
                        + human(Duration.between(f.first, f.last)) + ": " + f.aToBShares + " shares one way, "
                        + f.bToAShares + " back. Net " + net + " of " + gross + " shares changed hands.",
                        f.first, f.last);
                flagged.add(pair);
            }
        });
        if (!flagged.isEmpty()) {
            log.warn("surveillance: {} round-trip pattern(s) flagged", flagged.size());
        }
        return flagged.size();
    }

    @Transactional
    public SurveillanceAlertEntity dismiss(UUID alertId) {
        SurveillanceAlertEntity alert = alerts.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("unknown alert " + alertId));
        alert.dismiss();
        return alert;
    }

    private static String human(Duration d) {
        long minutes = Math.max(1, d.toMinutes());
        return minutes < 120 ? minutes + (minutes == 1 ? " minute" : " minutes") : d.toHours() + " hours";
    }
}
