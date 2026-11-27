package com.jpmc.midascore.incentive;

import com.jpmc.midascore.foundation.Transaction;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the client against a real local HTTP server, including timeouts. */
class IncentiveClientTest {
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"amount\": 12.5}");
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicInteger delayMillis = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/incentive", exchange -> {
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sleep(delayMillis.get());
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void postsTheTransactionAndReturnsTheIncentive() {
        assertThat(client(true).fetchIncentive(new Transaction(1, 2, 99.5f))).isEqualByComparingTo("12.50");
        assertThat(requestBody.get()).contains("\"senderId\":1", "\"recipientId\":2", "\"amount\":99.5");
    }

    @Test
    void disabledClientReturnsZeroWithoutCallingTheApi() {
        assertThat(client(false).fetchIncentive(new Transaction(1, 2, 10f))).isEqualByComparingTo("0");
        assertThat(calls.get()).isZero();
    }

    @Test
    void negativeIncentiveIsTreatedAsNone() {
        responseBody.set("{\"amount\": -3}");
        assertThat(client(true).fetchIncentive(new Transaction(1, 2, 10f))).isEqualByComparingTo("0");
    }

    @Test
    void serverErrorBecomesIncentiveUnavailable() {
        status.set(503);
        responseBody.set("{}");
        assertThatThrownBy(() -> client(true).fetchIncentive(new Transaction(1, 2, 10f)))
                .isInstanceOf(IncentiveUnavailableException.class);
    }

    @Test
    void slowServerTimesOutInsteadOfBlockingTheConsumer() {
        delayMillis.set(1500);
        assertThatThrownBy(() -> client(true).fetchIncentive(new Transaction(1, 2, 10f)))
                .isInstanceOf(IncentiveUnavailableException.class);
    }

    private IncentiveClient client(boolean enabled) {
        URI url = URI.create("http://localhost:" + server.getAddress().getPort() + "/incentive");
        IncentiveProperties properties = new IncentiveProperties(enabled, url, Duration.ofSeconds(1), Duration.ofMillis(500));
        return new IncentiveClient(properties, RestClient.builder());
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
