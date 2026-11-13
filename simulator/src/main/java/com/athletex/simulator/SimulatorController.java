package com.athletex.simulator;

import com.athletex.events.EventPayload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/simulations")
public class SimulatorController {

    public record AthleteRequest(@NotBlank String athleteId, @Positive double projectedFantasyPoints,
                                 @Positive double projectedMinutes) {
    }

    public record StartRequest(@NotBlank String gameId, @NotBlank String slateId,
                               @NotEmpty List<@Valid AthleteRequest> athletes,
                               Double speed, Long seed, Double injuriesPerGame) {
    }

    /** {@code returningThisGame} is optional and defaults to false. */
    public record InjuryRequest(@NotBlank String athleteId, @NotNull EventPayload.InjuryStatus status,
                                String detail, Boolean returningThisGame) {
    }

    public record NewsRequest(@NotBlank String athleteId, @NotBlank String headline,
                              @DecimalMin("-0.15") @DecimalMax("0.15") double impact,
                              @DecimalMin("0") @DecimalMax("1") double confidence) {
    }

    public record SessionView(String gameId, String slateId, double speed, int elapsedSeconds,
                              boolean finished, long eventsPublished) {
    }

    private final SimulationService simulations;

    public SimulatorController(SimulationService simulations) {
        this.simulations = simulations;
    }

    @PostMapping
    public ResponseEntity<SessionView> start(@Valid @RequestBody StartRequest request) {
        double speed = request.speed() == null ? 60.0 : request.speed();
        GameSpec spec = new GameSpec(request.gameId(), request.slateId(), Instant.now(),
                request.seed() == null ? System.nanoTime() : request.seed(),
                request.injuriesPerGame() == null ? 0.0 : request.injuriesPerGame(),
                request.athletes().stream()
                        .map(a -> new GameSpec.AthleteSpec(a.athleteId(), a.projectedFantasyPoints(), a.projectedMinutes()))
                        .toList(), speed);
        GameSession session = simulations.start(spec, speed);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(session));
    }

    @GetMapping
    public List<SessionView> list() {
        return simulations.sessions().stream().map(SimulatorController::view).toList();
    }

    @PostMapping("/{gameId}/injury")
    public SessionView injury(@PathVariable String gameId, @Valid @RequestBody InjuryRequest request) {
        return view(simulations.inject(gameId, new EventPayload.Injury(
                request.athleteId(), request.status(), request.detail(), Boolean.TRUE.equals(request.returningThisGame()))));
    }

    @PostMapping("/{gameId}/news")
    public SessionView news(@PathVariable String gameId, @Valid @RequestBody NewsRequest request) {
        return view(simulations.inject(gameId, new EventPayload.News(
                request.athleteId(), request.headline(), request.impact(), request.confidence())));
    }

    @DeleteMapping("/{gameId}")
    public SessionView stop(@PathVariable String gameId) {
        return view(simulations.stop(gameId));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ProblemDetail onBadRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(
                e instanceof IllegalStateException ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND, e.getMessage());
    }

    private static SessionView view(GameSession s) {
        return new SessionView(s.spec().gameId(), s.spec().slateId(), s.speed(), s.elapsedSeconds(),
                s.isFinished(), s.eventsPublished());
    }
}
