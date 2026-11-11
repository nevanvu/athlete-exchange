package com.athletex.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a demo NBA slate on startup so local dev has something to trade.
 * Athlete and team names are fictional on purpose (no real-athlete likeness).
 */
@Component
@ConditionalOnProperty(name = "athletex.demo-seed.enabled", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private static final String[][] TEAMS = {
            {"HRB", "Harbor City Breakers"},
            {"IRN", "Ironvale Forge"},
            {"SOL", "Solana Heat"},
            {"NWD", "Northwood Owls"},
    };

    private static final String[] FIRST = {"Avery", "Jordan", "Marcus", "Dante", "Theo", "Elias", "Rowan", "Kai"};
    private static final String[] LAST = {"Stone", "Vance", "Okafor", "Reyes", "Lindqvist", "Carter", "Bell", "Mori"};
    private static final String[] POSITIONS = {"PG", "SG", "SF", "PF", "C"};

    private final TeamRepository teamRepository;
    private final AthleteRepository athleteRepository;
    private final SlateRepository slateRepository;
    private final SlateSetupService setupService;

    public DemoSeeder(TeamRepository teamRepository,
                      AthleteRepository athleteRepository,
                      SlateRepository slateRepository,
                      SlateSetupService setupService) {
        this.teamRepository = teamRepository;
        this.athleteRepository = athleteRepository;
        this.slateRepository = slateRepository;
        this.setupService = setupService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (slateRepository.count() > 0) {
            return;
        }
        Random random = new Random(7);

        List<TeamEntity> teams = new ArrayList<>();
        for (String[] t : TEAMS) {
            teams.add(teamRepository.save(new TeamEntity(t[0], t[1])));
        }

        Instant now = Instant.now();
        SlateEntity slate = setupService.createSlate(
                "Demo Slate — Opening Night", now.plus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(30)));
        GameEntity game1 = setupService.addGame(slate.getId(), teams.get(0).getId(), teams.get(1).getId(),
                now.plus(Duration.ofMinutes(35)));
        GameEntity game2 = setupService.addGame(slate.getId(), teams.get(2).getId(), teams.get(3).getId(),
                now.plus(Duration.ofMinutes(40)));

        int n = 0;
        for (TeamEntity team : teams) {
            GameEntity game = (team == teams.get(0) || team == teams.get(1)) ? game1 : game2;
            for (int i = 0; i < 4; i++) {
                String name = FIRST[n % FIRST.length] + " " + LAST[(n / FIRST.length + n) % LAST.length];
                AthleteEntity athlete = athleteRepository.save(
                        new AthleteEntity(team.getId(), name, POSITIONS[i % POSITIONS.length]));
                BigDecimal projectedPoints = BigDecimal.valueOf(18 + random.nextInt(28));
                BigDecimal projectedMinutes = BigDecimal.valueOf(22 + random.nextInt(14));
                setupService.listMarket(slate.getId(), athlete.getId(), game.getId(), projectedPoints, projectedMinutes);
                n++;
            }
        }
        log.info("seeded demo slate {} with {} teams and {} athlete markets", slate.getId(), teams.size(), n);
    }
}
