package com.athletex.outbox;

import com.athletex.ingestion.GameDataChanged;
import com.athletex.market.GameEntity;
import com.athletex.market.GameRepository;
import com.athletex.market.GameStatus;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Publishes each applied game clock change so live scoreboards update without polling. */
@Component
public class GameStreamPublisher {

    public record GameUpdated(String type, UUID gameId, UUID slateId, GameStatus status, int period,
                              int clockSecondsRemaining) {
    }

    private final GameRepository gameRepository;
    private final OutboxService outbox;
    private final String topic;

    public GameStreamPublisher(GameRepository gameRepository, OutboxService outbox,
                               @Value("${athletex.kafka.market-events-topic:market-events}") String topic) {
        this.gameRepository = gameRepository;
        this.outbox = outbox;
        this.topic = topic;
    }

    @EventListener
    public void onGameDataChanged(GameDataChanged change) {
        if (change instanceof GameDataChanged.GameClock clock) {
            GameEntity game = gameRepository.findById(clock.gameId()).orElseThrow();
            outbox.enqueue(topic, game.getSlateId().toString(), new GameUpdated("game_updated", game.getId(),
                    game.getSlateId(), game.getStatus(), game.getPeriod(), game.getClockSecondsRemaining()));
        }
    }
}
