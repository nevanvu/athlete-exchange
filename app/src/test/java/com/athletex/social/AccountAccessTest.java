package com.athletex.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Over real HTTP, sharing the context of AuthWalletFlowTest. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AccountAccessTest {

    record Auth(String token, String username) {
    }

    record Me(String username, boolean portfolioPublic, boolean admin) {
    }

    @LocalServerPort
    private int port;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private String register(String username) {
        return client().post().uri("/api/auth/register")
                .body(Map.of("username", username, "email", username + "@test.local", "password", "hunter2hunter2"))
                .retrieve().body(Auth.class).token();
    }

    /** "ops_admin" is an operator in the test configuration; sign in, or create it on first use. */
    private String adminToken() {
        ResponseEntity<Auth> login = client().post().uri("/api/auth/login")
                .body(Map.of("username", "ops_admin", "password", "hunter2hunter2")).retrieve().toEntity(Auth.class);
        return login.getStatusCode().is2xxSuccessful() ? login.getBody().token() : register("ops_admin");
    }

    private int status(String method, String path, String token) {
        var spec = client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.retrieve().toBodilessEntity().getStatusCode().value();
    }

    @Test
    void surveillanceIsForOperatorsOnly() {
        String trader = register("trader" + System.nanoTime());
        String admin = adminToken();

        assertThat(status("GET", "/api/admin/surveillance", null)).isEqualTo(401);
        assertThat(status("GET", "/api/admin/surveillance", trader)).isEqualTo(403);
        assertThat(status("GET", "/api/admin/surveillance", admin)).isEqualTo(200);

        Me me = client().get().uri("/api/me").header("Authorization", "Bearer " + admin).retrieve().body(Me.class);
        assertThat(me.admin()).isTrue();
    }

    @Test
    void privacyIsOptInAndMarketDataIsPublic() {
        String token = register("private" + System.nanoTime());
        Me before = client().get().uri("/api/me").header("Authorization", "Bearer " + token).retrieve().body(Me.class);
        assertThat(before.portfolioPublic()).isFalse();
        assertThat(before.admin()).isFalse();

        ResponseEntity<Me> after = client().put().uri("/api/me/privacy").header("Authorization", "Bearer " + token)
                .body(Map.of("portfolioPublic", true)).retrieve().toEntity(Me.class);
        assertThat(after.getBody().portfolioPublic()).isTrue();

        assertThat(status("GET", "/api/leaderboard", null)).isEqualTo(200);
        assertThat(status("GET", "/api/feed", null)).isEqualTo(200);
        assertThat(status("GET", "/api/me", null)).isEqualTo(401);
    }
}
