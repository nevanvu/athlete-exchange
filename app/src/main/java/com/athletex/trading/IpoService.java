package com.athletex.trading;

import com.athletex.ledger.AccountEntity;
import com.athletex.ledger.AccountType;
import com.athletex.ledger.LedgerService;
import com.athletex.ledger.Posting;
import com.athletex.ledger.TransactionType;
import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.market.Pricing;
import com.athletex.market.SlateEntity;
import com.athletex.market.SlateRepository;
import com.athletex.market.SlateStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pre-game single-price auction per docs/slate-lifecycle.md. Bidders escrow max_price x quantity;
 * at close every winner pays the same clearing price and the difference is released.
 */
@Service
public class IpoService {

    private static final Logger log = LoggerFactory.getLogger(IpoService.class);

    private final IpoBidRepository bidRepository;
    private final AthleteMarketRepository marketRepository;
    private final SlateRepository slateRepository;
    private final PositionRepository positionRepository;
    private final LedgerService ledgerService;

    public IpoService(IpoBidRepository bidRepository,
                      AthleteMarketRepository marketRepository,
                      SlateRepository slateRepository,
                      PositionRepository positionRepository,
                      LedgerService ledgerService) {
        this.bidRepository = bidRepository;
        this.marketRepository = marketRepository;
        this.slateRepository = slateRepository;
        this.positionRepository = positionRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public IpoBidEntity bid(UUID userId, UUID marketId, long maxPriceCents, int quantity) {
        if (quantity <= 0 || maxPriceCents <= 0) {
            throw new IllegalArgumentException("quantity and max price must be positive");
        }
        AthleteMarketEntity market = marketRepository.findById(marketId)
                .orElseThrow(() -> new IllegalArgumentException("unknown market " + marketId));
        // Locking the slate row serializes bids against the clearing transition.
        SlateEntity slate = slateRepository.findByIdForUpdate(market.getSlateId()).orElseThrow();
        if (slate.getStatus() != SlateStatus.IPO) {
            throw new TradingRejectedException("slate is " + slate.getStatus() + "; IPO bidding is not open");
        }
        IpoBidEntity bid = new IpoBidEntity(userId, marketId, maxPriceCents, quantity);
        ledgerService.lockFunds("ipobid:" + bid.getId() + ":lock", userId, bid.escrowCents());
        return bidRepository.save(bid);
    }

    @Transactional
    public IpoBidEntity cancelBid(UUID userId, UUID bidId) {
        IpoBidEntity bid = bidRepository.findByIdAndUserId(bidId, userId)
                .orElseThrow(() -> new IllegalArgumentException("unknown bid " + bidId));
        AthleteMarketEntity market = marketRepository.findById(bid.getMarketId()).orElseThrow();
        SlateEntity slate = slateRepository.findByIdForUpdate(market.getSlateId()).orElseThrow();
        if (bid.getStatus() != IpoBidEntity.Status.OPEN || slate.getStatus() != SlateStatus.IPO) {
            throw new TradingRejectedException("bid can no longer be cancelled");
        }
        ledgerService.releaseFunds("ipobid:" + bid.getId() + ":cancel", userId, bid.escrowCents());
        bid.cancel();
        return bid;
    }

    /** Clears every market in the slate. Idempotent: already-cleared markets are skipped. */
    @Transactional
    public void clearSlate(UUID slateId) {
        for (AthleteMarketEntity market : marketRepository.findBySlateId(slateId)) {
            if (market.getIpoClearingPriceCents() == null) {
                clearMarket(market);
            }
        }
    }

    /** Refunds every open bid (slate voided during IPO). */
    @Transactional
    public void refundOpenBids(UUID slateId) {
        for (AthleteMarketEntity market : marketRepository.findBySlateId(slateId)) {
            for (IpoBidEntity bid : openBidsBestFirst(market.getId())) {
                ledgerService.releaseFunds("ipobid:" + bid.getId() + ":void", bid.getUserId(), bid.escrowCents());
                bid.allocate(0, 0);
            }
        }
    }

    private void clearMarket(AthleteMarketEntity market) {
        List<IpoBidEntity> bids = openBidsBestFirst(market.getId());
        long reserve = Pricing.reservePriceCents(market.getProjectedFantasyPoints());
        int supply = market.getTotalShares();

        long clearing = clearingPrice(bids, reserve, supply);

        int remaining = supply;
        for (IpoBidEntity bid : bids) {
            int allocated = 0;
            if (bid.getMaxPriceCents() >= clearing && remaining > 0) {
                allocated = Math.min(bid.getQuantity(), remaining);
                remaining -= allocated;
            }
            settleBid(bid, allocated, clearing, market);
        }

        if (remaining > 0) {
            housePosition(market.getId()).acquire(remaining, 0);
        }
        market.setIpoClearingPriceCents(clearing);
        market.setLastTradePriceCents(clearing);
        log.info("IPO cleared market {} at {} cents: {} bids, {} unsold -> house",
                market.getId(), clearing, bids.size(), remaining);
    }

    /**
     * Highest price at which cumulative demand (best bids first) covers the supply; the reserve
     * price when demand is short. Bids below the reserve never count.
     */
    static long clearingPrice(List<IpoBidEntity> bidsBestFirst, long reserve, int supply) {
        int cumulative = 0;
        for (IpoBidEntity bid : bidsBestFirst) {
            if (bid.getMaxPriceCents() < reserve) {
                break;
            }
            cumulative += bid.getQuantity();
            if (cumulative >= supply) {
                return bid.getMaxPriceCents();
            }
        }
        return reserve;
    }

    private void settleBid(IpoBidEntity bid, int allocated, long clearing, AthleteMarketEntity market) {
        long escrow = bid.escrowCents();
        if (allocated == 0) {
            ledgerService.releaseFunds("ipobid:" + bid.getId() + ":refund", bid.getUserId(), escrow);
            bid.allocate(0, clearing);
            return;
        }
        long cost = allocated * clearing;
        List<Posting> postings = new ArrayList<>();
        postings.add(new Posting(ledgerService.accountIdOf(bid.getUserId(), AccountType.USER_ESCROW), -escrow));
        if (escrow - cost > 0) {
            postings.add(new Posting(ledgerService.accountIdOf(bid.getUserId(), AccountType.USER_CASH), escrow - cost));
        }
        postings.add(new Posting(AccountEntity.HOUSE_ACCOUNT_ID, cost));
        ledgerService.post("ipobid:" + bid.getId() + ":allocate", TransactionType.IPO_ALLOCATION, postings);

        positionRepository.findByUserIdAndMarketIdForUpdate(bid.getUserId(), market.getId())
                .orElseGet(() -> positionRepository.save(new PositionEntity(bid.getUserId(), market.getId())))
                .acquire(allocated, clearing);
        bid.allocate(allocated, clearing);
    }

    private PositionEntity housePosition(UUID marketId) {
        return positionRepository.findByUserIdAndMarketIdForUpdate(PositionEntity.HOUSE_USER_ID, marketId)
                .orElseGet(() -> positionRepository.save(new PositionEntity(PositionEntity.HOUSE_USER_ID, marketId)));
    }

    private List<IpoBidEntity> openBidsBestFirst(UUID marketId) {
        return bidRepository.findByMarketIdAndStatusOrderByMaxPriceCentsDescCreatedAtAsc(marketId, IpoBidEntity.Status.OPEN);
    }
}
