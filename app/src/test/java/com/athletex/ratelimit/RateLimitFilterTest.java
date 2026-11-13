package com.athletex.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(
            new RateLimitFilter.Limit(3, 1, Duration.ofSeconds(1)),
            new RateLimitFilter.Limit(2, 1, Duration.ofMinutes(1)));

    @AfterEach
    void clearUser() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse send(String method, String path, String remoteAddr) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddr);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private void signedInAs(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Test
    void ordersAreLimitedPerUserWithARetryHint() throws Exception {
        signedInAs("alice");
        for (int i = 0; i < 3; i++) {
            assertThat(send("POST", "/api/orders", "10.0.0.1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = send("POST", "/api/orders", "10.0.0.1");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("1");
        assertThat(limited.getContentAsString()).contains("Too many requests");

        signedInAs("bob"); // another user's bucket is untouched
        assertThat(send("POST", "/api/orders", "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void readsAreNeverLimited() throws Exception {
        signedInAs("alice");
        for (int i = 0; i < 10; i++) {
            assertThat(send("GET", "/api/orders", "10.0.0.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void signInIsLimitedPerAddress() throws Exception {
        assertThat(send("POST", "/api/auth/login", "10.0.0.9").getStatus()).isEqualTo(200);
        assertThat(send("POST", "/api/auth/register", "10.0.0.9").getStatus()).isEqualTo(200);
        MockHttpServletResponse limited = send("POST", "/api/auth/login", "10.0.0.9");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(send("POST", "/api/auth/login", "10.0.0.10").getStatus()).isEqualTo(200);
    }

    @Test
    void idleBucketsAreEvicted() throws Exception {
        signedInAs("alice");
        send("POST", "/api/orders", "10.0.0.1");
        assertThat(filter.evictIdle(Duration.ZERO)).isEqualTo(1);
    }
}
