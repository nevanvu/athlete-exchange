package com.athletex.halts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HaltController {

    public record HaltView(String reason, String triggerEventId, Long fairValueBeforeCents, Long fairValueAfterCents,
                           Instant haltedAt, Instant resumeAfter, Instant resumedAt) {
    }

    private final MarketHaltRepository haltRepository;

    public HaltController(MarketHaltRepository haltRepository) {
        this.haltRepository = haltRepository;
    }

    @GetMapping("/api/markets/{id}/halts")
    public List<HaltView> halts(@PathVariable UUID id) {
        return haltRepository.findByMarketIdOrderByHaltedAtAsc(id).stream()
                .map(h -> new HaltView(h.getReason(), h.getTriggerEventId(), h.getFairValueBeforeCents(),
                        h.getFairValueAfterCents(), h.getHaltedAt(), h.getResumeAfter(), h.getResumedAt()))
                .toList();
    }
}
