package com.athletex.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fans market-events out to WebSocket subscribers. Every instance reads the whole topic (its own
 * consumer group) from the latest offset: a client loads the current state over REST and then
 * applies these messages, so nothing published before it connected is needed. Fills go privately
 * to the buyer and seller; their user ids are removed before the trade is broadcast.
 */
@Component
@ConditionalOnProperty(name = "athletex.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class MarketStreamRelay {

    public static final String LISTENER_ID = "market-stream";
    private static final Logger log = LoggerFactory.getLogger(MarketStreamRelay.class);

    private final SimpMessagingTemplate messaging;
    private final JsonMapper json;

    public MarketStreamRelay(SimpMessagingTemplate messaging, JsonMapper json) {
        this.messaging = messaging;
        this.json = json;
    }

    @KafkaListener(id = LISTENER_ID, topics = "${athletex.kafka.market-events-topic}",
            groupId = "athletex-stream-${random.uuid}", properties = "auto.offset.reset=latest")
    public void onMessage(String message) {
        try {
            route((ObjectNode) json.readTree(message));
        } catch (RuntimeException e) {
            log.warn("could not relay market event: {}", e.getMessage());
        }
    }

    void route(ObjectNode event) {
        String type = event.path("type").asString();
        String market = event.path("athleteMarketId").asString(null);
        String slate = event.path("slateId").asString(null);
        switch (type) {
            case "trade_executed" -> {
                JsonNode buyer = event.remove("buyerUserId");
                JsonNode seller = event.remove("sellerUserId");
                if (buyer != null) {
                    sendToUser(buyer.asString(), event.deepCopy().put("side", "BUY"));
                }
                if (seller != null) {
                    sendToUser(seller.asString(), event.deepCopy().put("side", "SELL"));
                }
                send("/topic/markets/" + market, event);
                send("/topic/slates/" + slate, event);
            }
            case "book_updated" -> send("/topic/markets/" + market, event);
            case "fair_value_updated", "market_halted", "market_resumed" -> {
                send("/topic/markets/" + market, event);
                send("/topic/slates/" + slate, event);
            }
            case "slate_transitioned", "game_updated" -> send("/topic/slates/" + slate, event);
            case "feed_item" -> send("/topic/feed", event);
            default -> log.debug("not streamed: {}", type);
        }
    }

    private void send(String destination, JsonNode payload) {
        messaging.convertAndSend(destination, json.writeValueAsString(payload));
    }

    private void sendToUser(String userId, JsonNode payload) {
        messaging.convertAndSendToUser(userId, "/queue/fills", json.writeValueAsString(payload));
    }
}
