package com.athletex.trading;

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
@RequestMapping("/api/ipo/bids")
public class IpoController {

    public record BidRequest(@NotNull UUID marketId, @Min(1) long maxPriceCents, @Min(1) int quantity) {
    }

    public record BidView(UUID id, UUID marketId, long maxPriceCents, int quantity, int allocatedQuantity,
                          Long clearingPriceCents, IpoBidEntity.Status status, Instant createdAt) {
    }

    private final IpoService ipoService;
    private final IpoBidRepository bidRepository;

    public IpoController(IpoService ipoService, IpoBidRepository bidRepository) {
        this.ipoService = ipoService;
        this.bidRepository = bidRepository;
    }

    @PostMapping
    public ResponseEntity<BidView> bid(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BidRequest request) {
        IpoBidEntity bid = ipoService.bid(userId(jwt), request.marketId(), request.maxPriceCents(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(bid));
    }

    @GetMapping
    public List<BidView> myBids(@AuthenticationPrincipal Jwt jwt) {
        return bidRepository.findByUserIdOrderByCreatedAtDesc(userId(jwt)).stream().map(IpoController::toView).toList();
    }

    @DeleteMapping("/{id}")
    public BidView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return toView(ipoService.cancelBid(userId(jwt), id));
    }

    private static BidView toView(IpoBidEntity b) {
        return new BidView(b.getId(), b.getMarketId(), b.getMaxPriceCents(), b.getQuantity(),
                b.getAllocatedQuantity(), b.getClearingPriceCents(), b.getStatus(), b.getCreatedAt());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
