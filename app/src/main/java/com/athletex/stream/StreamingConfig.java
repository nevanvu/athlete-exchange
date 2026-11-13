package com.athletex.stream;

import java.security.Principal;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket at /ws.
 *
 * <ul>
 *   <li>/topic/markets/{marketId}: book, trades, fair value, halts for one market (public)</li>
 *   <li>/topic/slates/{slateId}: ticker for every market in a slate, plus slate phase changes (public)</li>
 *   <li>/user/queue/fills: the signed-in user's own fills (needs a token on CONNECT)</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class StreamingConfig implements WebSocketMessageBrokerConfigurer {

    record StreamUser(String getName) implements Principal {
    }

    private final JwtDecoder jwtDecoder;

    public StreamingConfig(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return message;
                }
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String authorization = accessor.getFirstNativeHeader("Authorization");
                    if (authorization != null && authorization.startsWith("Bearer ")) {
                        try {
                            accessor.setUser(new StreamUser(jwtDecoder.decode(authorization.substring(7)).getSubject()));
                        } catch (JwtException e) {
                            throw new MessagingException("invalid or expired token");
                        }
                    }
                }
                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    String destination = accessor.getDestination();
                    if (destination != null && destination.startsWith("/user/") && accessor.getUser() == null) {
                        throw new MessagingException("sign in to subscribe to " + destination);
                    }
                }
                return message;
            }
        });
    }
}
