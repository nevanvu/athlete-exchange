package com.athletex.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.auth.TokenService;
import com.athletex.events.EventPayload.GameState;
import com.athletex.events.EventPayload.InjuryStatus;
import com.athletex.marketmaker.MarketMakerService;
import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import com.athletex.settlement.AbstractLiveSlateTest;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Browser's-eye view: STOMP over a real WebSocket, fed by the real outbox -> Kafka -> relay path. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "athletex.scheduler.enabled=false",
        "athletex.demo-seed.enabled=false",
        "athletex.fair-value.refresh-ms=3600000",
        "athletex.ingestion.reorder-max-wait-ms=0",
        "athletex.ingestion.reorder-flush-ms=3600000"
})
class StreamingTest extends AbstractLiveSlateTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @LocalServerPort private int port;
    @Autowired private TokenService tokenService;
    @Autowired private MarketMakerService marketMaker;
    @Autowired private KafkaListenerEndpointRegistry listeners;

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<StompSession> sessions = new ArrayList<>();
    private WebSocketStompClient client;

    /** Collects frames for one subscription, plus any ERROR frames the server sends the session. */
    private static final class Inbox extends StompSessionHandlerAdapter implements StompFrameHandler {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            messages.add(payload == null ? "" : payload.toString());
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errors.add("transport: " + exception.getMessage());
        }
    }

    /** Session-level handler: on a session, handleFrame only receives ERROR frames. */
    private static final class SessionErrors extends StompSessionHandlerAdapter {
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.add(StompCommand.ERROR + " " + headers.getFirst("message"));
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errors.add("transport: " + exception.getMessage());
        }
    }

    @BeforeEach
    void connectClient() {
        // The relay reads from the latest offset, so it must own its partition before we publish.
        ContainerTestUtils.waitForAssignment(listeners.getListenerContainer(MarketStreamRelay.LISTENER_ID), 1);
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
    }

    @AfterEach
    void disconnect() {
        sessions.forEach(s -> {
            if (s.isConnected()) {
                s.disconnect();
            }
        });
        client.stop();
    }

    private StompSession connect(String token, StompSessionHandlerAdapter handler) throws Exception {
        StompHeaders headers = new StompHeaders();
        if (token != null) {
            headers.add("Authorization", "Bearer " + token);
        }
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                headers, handler).get(10, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private Inbox subscribe(StompSession session, String destination) throws InterruptedException {
        Inbox inbox = new Inbox();
        session.subscribe(destination, inbox);
        Thread.sleep(300); // the simple broker does not acknowledge subscriptions
        return inbox;
    }

    private JsonNode await(Inbox inbox, String type, Predicate<JsonNode> condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            String raw = inbox.messages.poll(200, TimeUnit.MILLISECONDS);
            if (raw != null) {
                JsonNode node = json.readTree(raw);
                if (type.equals(node.path("type").asString()) && condition.test(node)) {
                    return node;
                }
            }
        }
        throw new AssertionError("no " + type + " message within " + TIMEOUT);
    }

    private String tokenFor(UUID userId) {
        return tokenService.issue(userRepository.findById(userId).orElseThrow());
    }

    @Test
    void bookAndTradesStreamPubliclyWhileFillsStayPrivate() throws Exception {
        Slate s = newSlate(40);
        s.openMarket(); // the house keeps all 1000 shares
        UUID market = s.markets.get(0);
        UUID bob = newTrader();

        Inbox marketFeed = subscribe(connect(null, new StompSessionHandlerAdapter() {}), "/topic/markets/" + market);
        Inbox bobFills = subscribe(connect(tokenFor(bob), new StompSessionHandlerAdapter() {}), "/user/queue/fills");

        marketMaker.quoteMarket(market); // $9.80 bid / $10.20 ask
        JsonNode book = await(marketFeed, "book_updated", n -> n.path("asks").size() == 1);
        assertThat(book.path("asks").get(0).path("priceCents").asLong()).isEqualTo(1020);

        orderService.place(bob, market, Side.BUY, OrderType.LIMIT, 1030L, 50);

        JsonNode trade = await(marketFeed, "trade_executed", n -> true);
        assertThat(trade.path("priceCents").asLong()).isEqualTo(1020);
        assertThat(trade.path("quantity").asInt()).isEqualTo(50);
        assertThat(trade.has("buyerUserId")).as("user ids never leave on public topics").isFalse();
        assertThat(trade.has("sellerUserId")).isFalse();

        JsonNode fill = await(bobFills, "trade_executed", n -> true);
        assertThat(fill.path("side").asString()).isEqualTo("BUY");
        assertThat(fill.path("tradeId").asString()).isEqualTo(trade.path("tradeId").asString());
    }

    @Test
    void aHaltReachesTheSlateTickerBeforeTheRepricingThatCausedIt() throws Exception {
        Slate s = newSlate(40);
        s.toIpo();
        s.apply(s.line(0, 12, 0, 0, false));
        s.apply(s.clock(GameState.HALFTIME, 2, 0));
        s.openMarket();
        Inbox ticker = subscribe(connect(null, new StompSessionHandlerAdapter() {}), "/topic/slates/" + s.slateId);

        s.apply(s.injury(0, InjuryStatus.OUT));

        // Earlier repricings from the setup may still be in flight; only the injury's pair matters here.
        List<String> order = new ArrayList<>();
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (order.size() < 2 && Instant.now().isBefore(deadline)) {
            String raw = ticker.messages.poll(200, TimeUnit.MILLISECONDS);
            if (raw == null) {
                continue;
            }
            JsonNode node = json.readTree(raw);
            String type = node.path("type").asString();
            if (type.equals("market_halted")
                    || (type.equals("fair_value_updated") && node.path("fairValueCents").asLong() == 300)) {
                order.add(type);
            }
        }
        assertThat(order).containsExactly("market_halted", "fair_value_updated");
    }

    @Test
    void privateQueuesNeedAValidToken() throws Exception {
        SessionErrors badToken = new SessionErrors();
        try {
            connect("not-a-jwt", badToken);
        } catch (Exception expected) {
            // refused during CONNECT
        }
        assertThat(badToken.errors.poll(10, TimeUnit.SECONDS)).as("bad token is refused").isNotNull();

        SessionErrors anonymous = new SessionErrors();
        StompSession session = connect(null, anonymous);
        session.subscribe("/user/queue/fills", new Inbox());
        assertThat(anonymous.errors.poll(10, TimeUnit.SECONDS)).as("anonymous fills subscription is refused").isNotNull();
    }
}
