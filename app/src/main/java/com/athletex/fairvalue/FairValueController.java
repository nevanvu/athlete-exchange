package com.athletex.fairvalue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FairValueController {

    public record TickView(long fairValueCents, BigDecimal fpScored, BigDecimal fpRemaining, BigDecimal availability,
                           BigDecimal newsFactor, BigDecimal gameFractionRemaining, String asOfEventId,
                           Instant computedAt) {
    }

    private final FairValueTickRepository tickRepository;

    public FairValueController(FairValueTickRepository tickRepository) {
        this.tickRepository = tickRepository;
    }

    /** Oldest first, most recent 200 changes: enough for a sparkline next to the market price. */
    @GetMapping("/api/markets/{id}/fair-value")
    public List<TickView> history(@PathVariable UUID id) {
        return tickRepository.findTop200ByMarketIdOrderByIdDesc(id).reversed().stream()
                .map(t -> new TickView(t.getFairValueCents(), t.getFpScored(), t.getFpRemaining(), t.getAvailability(),
                        t.getNewsFactor(), t.getGameFractionRemaining(), t.getAsOfEventId(), t.getComputedAt()))
                .toList();
    }
}
