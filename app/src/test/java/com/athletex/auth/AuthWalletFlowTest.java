package com.athletex.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthWalletFlowTest {

    record AuthResponse(String token, String username) {
    }

    record WalletResponse(long cashCents, long escrowCents) {
    }

    @LocalServerPort
    private int port;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {
                    // keep 4xx/5xx responses as return values instead of exceptions
                })
                .build();
    }

    @Test
    void registerLoginAndReadWallet() {
        RestClient client = client();
        String username = "trader" + System.nanoTime();

        ResponseEntity<AuthResponse> registered = client.post().uri("/api/auth/register")
                .body(Map.of("username", username, "email", username + "@test.local", "password", "hunter2hunter2"))
                .retrieve().toEntity(AuthResponse.class);
        assertThat(registered.getStatusCode().value()).isEqualTo(201);
        assertThat(registered.getBody().token()).isNotBlank();

        ResponseEntity<AuthResponse> loggedIn = client.post().uri("/api/auth/login")
                .body(Map.of("username", username, "password", "hunter2hunter2"))
                .retrieve().toEntity(AuthResponse.class);
        assertThat(loggedIn.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<WalletResponse> wallet = client.get().uri("/api/wallet")
                .header("Authorization", "Bearer " + loggedIn.getBody().token())
                .retrieve().toEntity(WalletResponse.class);
        assertThat(wallet.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(wallet.getBody().cashCents()).isEqualTo(1_000_000L);
        assertThat(wallet.getBody().escrowCents()).isZero();
    }

    @Test
    void walletRequiresAuthentication() {
        ResponseEntity<Void> response = client().get().uri("/api/wallet").retrieve().toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void duplicateUsernameIsRejected() {
        RestClient client = client();
        String username = "dupe" + System.nanoTime();
        Map<String, String> body =
                Map.of("username", username, "email", username + "@test.local", "password", "hunter2hunter2");

        HttpStatusCode first = client.post().uri("/api/auth/register").body(body)
                .retrieve().toBodilessEntity().getStatusCode();
        assertThat(first.value()).isEqualTo(201);

        HttpStatusCode second = client.post().uri("/api/auth/register").body(body)
                .retrieve().toBodilessEntity().getStatusCode();
        assertThat(second.value()).isEqualTo(409);
    }

    @Test
    void wrongPasswordIsUnauthorized() {
        RestClient client = client();
        String username = "pw" + System.nanoTime();
        client.post().uri("/api/auth/register")
                .body(Map.of("username", username, "email", username + "@test.local", "password", "hunter2hunter2"))
                .retrieve().toBodilessEntity();

        ResponseEntity<Void> login = client.post().uri("/api/auth/login")
                .body(Map.of("username", username, "password", "wrong-password"))
                .retrieve().toBodilessEntity();
        assertThat(login.getStatusCode().value()).isEqualTo(401);
    }
}
