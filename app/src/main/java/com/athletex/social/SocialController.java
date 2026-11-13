package com.athletex.social;

import com.athletex.auth.AdminDirectory;
import com.athletex.auth.UserEntity;
import com.athletex.auth.UserRepository;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SocialController {

    public record FeedItemView(UUID id, String kind, UUID slateId, UUID athleteMarketId, String headline,
                               String detail, Instant occurredAt) {
    }

    public record MeView(String username, boolean portfolioPublic, boolean admin) {
    }

    public record PrivacyRequest(@NotNull Boolean portfolioPublic) {
    }

    private final LeaderboardService leaderboards;
    private final FeedItemRepository feed;
    private final UserRepository userRepository;
    private final AdminDirectory admins;

    public SocialController(LeaderboardService leaderboards, FeedItemRepository feed, UserRepository userRepository,
                            AdminDirectory admins) {
        this.leaderboards = leaderboards;
        this.feed = feed;
        this.userRepository = userRepository;
        this.admins = admins;
    }

    @GetMapping("/api/leaderboard")
    public List<LeaderboardService.Row> overall(@AuthenticationPrincipal Jwt jwt) {
        return leaderboards.overall(viewer(jwt), 50);
    }

    @GetMapping("/api/slates/{id}/leaderboard")
    public List<LeaderboardService.Row> slate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return leaderboards.slate(id, viewer(jwt), 50);
    }

    @GetMapping("/api/feed")
    public List<FeedItemView> feed(@RequestParam(required = false) UUID slateId,
                                   @RequestParam(defaultValue = "40") int limit) {
        Limit max = Limit.of(Math.max(1, Math.min(limit, 200)));
        List<FeedItemEntity> items = slateId == null
                ? feed.findAllByOrderByOccurredAtDesc(max)
                : feed.findBySlateIdOrderByOccurredAtDesc(slateId, max);
        return items.stream().map(i -> new FeedItemView(i.getId(), i.getKind(), i.getSlateId(), i.getMarketId(),
                i.getHeadline(), i.getDetail(), i.getOccurredAt())).toList();
    }

    @GetMapping("/api/me")
    public MeView me(@AuthenticationPrincipal Jwt jwt) {
        UserEntity user = userRepository.findById(UUID.fromString(jwt.getSubject())).orElseThrow();
        return new MeView(user.getUsername(), user.isPortfolioPublic(), admins.isAdmin(user.getUsername()));
    }

    @PutMapping("/api/me/privacy")
    @Transactional
    public MeView privacy(@AuthenticationPrincipal Jwt jwt, @RequestBody PrivacyRequest request) {
        UserEntity user = userRepository.findById(UUID.fromString(jwt.getSubject())).orElseThrow();
        user.setPortfolioPublic(Boolean.TRUE.equals(request.portfolioPublic()));
        return new MeView(user.getUsername(), user.isPortfolioPublic(), admins.isAdmin(user.getUsername()));
    }

    private static UUID viewer(Jwt jwt) {
        return jwt == null ? null : UUID.fromString(jwt.getSubject());
    }
}
