package com.athletex.surveillance;

import com.athletex.auth.UserEntity;
import com.athletex.auth.UserRepository;
import com.athletex.market.AthleteEntity;
import com.athletex.market.AthleteRepository;
import com.athletex.market.AthleteMarketRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operators only (SCOPE_admin, see SecurityConfig). Shows real usernames: this is the enforcement view. */
@RestController
@RequestMapping("/api/admin/surveillance")
public class SurveillanceController {

    public record AlertView(UUID id, SurveillanceAlertEntity.Kind kind, UUID marketId, UUID slateId, String athlete,
                            String userA, String userB, int occurrences, String detail, Instant firstSeenAt,
                            Instant lastSeenAt, SurveillanceAlertEntity.Status status) {
    }

    private final SurveillanceAlertRepository alerts;
    private final SurveillanceService surveillance;
    private final AthleteMarketRepository marketRepository;
    private final AthleteRepository athleteRepository;
    private final UserRepository userRepository;

    public SurveillanceController(SurveillanceAlertRepository alerts, SurveillanceService surveillance,
                                  AthleteMarketRepository marketRepository, AthleteRepository athleteRepository,
                                  UserRepository userRepository) {
        this.alerts = alerts;
        this.surveillance = surveillance;
        this.marketRepository = marketRepository;
        this.athleteRepository = athleteRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<AlertView> list(@RequestParam(defaultValue = "false") boolean includeDismissed) {
        List<SurveillanceAlertEntity> rows = includeDismissed
                ? alerts.findAllByOrderByLastSeenAtDesc()
                : alerts.findByStatusOrderByLastSeenAtDesc(SurveillanceAlertEntity.Status.OPEN);
        return rows.stream().map(this::view).toList();
    }

    @PostMapping("/{id}/dismiss")
    public AlertView dismiss(@PathVariable UUID id) {
        return view(surveillance.dismiss(id));
    }

    private AlertView view(SurveillanceAlertEntity a) {
        var market = marketRepository.findById(a.getMarketId()).orElseThrow();
        String athlete = athleteRepository.findById(market.getAthleteId()).map(AthleteEntity::getName).orElse(null);
        return new AlertView(a.getId(), a.getKind(), a.getMarketId(), market.getSlateId(), athlete,
                username(a.getUserA()), username(a.getUserB()), a.getOccurrences(), a.getDetail(),
                a.getFirstSeenAt(), a.getLastSeenAt(), a.getStatus());
    }

    private String username(UUID id) {
        return userRepository.findById(id).map(UserEntity::getUsername).orElse(id.toString());
    }
}
