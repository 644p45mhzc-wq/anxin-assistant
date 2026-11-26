package com.example.voicechat;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class DeepSeekChatProviderTest {
    HttpServer server;
    ExecutorService executor;
    ObjectMapper mapper = new ObjectMapper();
    AtomicReference<String> request = new AtomicReference<>(), authorization = new AtomicReference<>();
    volatile int status = 200;
    volatile String response;
    volatile long delay;

    @BeforeEach void start() throws Exception {
        response = envelope("{\"reply\":\"你好。\",\"followUp\":\"需要什么帮助？\"}", "stop");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // The client may deliberately time out and close the connection.
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    String envelope(String content, String reason) throws Exception {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", reason,
            "message", Map.of("content", content)))));
    }
    DeepSeekChatProvider provider(Duration timeout, String key) {
        return provider(timeout, key, false);
    }
    DeepSeekChatProvider provider(Duration timeout, String key, boolean jsonMode) {
        return new DeepSeekChatProvider(new AppProperties(List.of(), Duration.ofHours(24), 20, 16000,
            new AppProperties.Deepseek("http://127.0.0.1:" + server.getAddress().getPort(), key, "deepseek-chat", timeout, jsonMode)), mapper);
    }
    @Test void sendsOrderedContextAndParsesFollowUp() throws Exception {
        var answer = provider(Duration.ofSeconds(3), "test-key").generate(
            List.of(new ChatProvider.Turn("user", "我叫小明"), new ChatProvider.Turn("assistant", "你好小明")), "我叫什么？");
        assertThat(answer.reply()).isEqualTo("你好。"); assertThat(answer.followUp()).isEqualTo("需要什么帮助？");
        JsonNode payload = mapper.readTree(request.get());
        assertThat(payload.path("messages").size()).isEqualTo(4);
        assertThat(payload.at("/messages/1/content").asText()).isEqualTo("我叫小明");
        assertThat(payload.at("/messages/3/content").asText()).isEqualTo("我叫什么？");
        assertThat(payload.has("response_format")).isFalse();
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
    }
    @Test void nullFollowUpIsAccepted() throws Exception {
        response = envelope("{\"reply\":\"直接回答。\",\"followUp\":null}", "stop");
        assertThat(provider(Duration.ofSeconds(3), "test-key").generate(List.of(), "你好").followUp()).isNull();
    }
    @Test void optionalJsonModeCanBeEnabled() throws Exception {
        provider(Duration.ofSeconds(3), "test-key", true).generate(List.of(), "你好");
        assertThat(mapper.readTree(request.get()).at("/response_format/type").asText()).isEqualTo("json_object");
    }
    @Test void plainTextFencedJsonAndMissingFollowUpAreSupported() throws Exception {
        response = envelope("你今天重点学 Java 的接口。", "stop");
        assertThat(provider(Duration.ofSeconds(3), "test-key").generate(List.of(), "我学什么？"))
            .isEqualTo(new ChatProvider.Reply("你今天重点学 Java 的接口。", null));
        response = envelope("```json\n{\"reply\":\"你好。\"}\n```", "stop");
        assertThat(provider(Duration.ofSeconds(3), "test-key").generate(List.of(), "你好"))
            .isEqualTo(new ChatProvider.Reply("你好。", null));
    }
    @Test void rateLimitsAndUpstreamFailuresAreSanitized() {
        status = 429; response = "sensitive upstream body";
        expect("MODEL_RATE_LIMITED", 429);
        status = 401; expect("MODEL_AUTH_FAILED", 503);
        status = 403; expect("MODEL_AUTH_FAILED", 503);
        status = 500; expect("MODEL_UPSTREAM_ERROR", 502);
    }
    @Test void invalidJsonEmptyReplyAndTruncatedAnswersAreRejected() throws Exception {
        for (String content : List.of("{invalid-json", "null", "", "                ", "{\"reply\":\"\",\"followUp\":null}",
            "{\"reply\":\"x\",\"followUp\":[]}", "[]")) {
            response = envelope(content, "stop"); expect("MODEL_INVALID_RESPONSE", 502);
        }
        response = envelope("{\"reply\":\"x\",\"followUp\":null}", "length"); expect("MODEL_INVALID_RESPONSE", 502);
        response = "{}"; expect("MODEL_INVALID_RESPONSE", 502);
        response = ""; expect("MODEL_INVALID_RESPONSE", 502);
    }
    @Test void timeoutsAreMappedToGatewayTimeout() {
        delay = 600;
        assertThatThrownBy(() -> provider(Duration.ofMillis(100), "test-key").generate(List.of(), "你好"))
            .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.code()).isEqualTo("MODEL_TIMEOUT"); assertThat(e.status()).isEqualTo(504); });
    }
    @Test void missingKeyFailsBeforeMakingAnHttpRequest() {
        assertThatThrownBy(() -> provider(Duration.ofSeconds(3), "").generate(List.of(), "你好"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("MODEL_NOT_CONFIGURED"));
        assertThat(request.get()).isNull();
    }
    private void expect(String code, int httpStatus) {
        assertThatThrownBy(() -> provider(Duration.ofSeconds(3), "test-key").generate(List.of(), "你好"))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo(code); assertThat(e.status()).isEqualTo(httpStatus);
                assertThat(e.getMessage()).doesNotContain("sensitive", "test-key");
            });
    }
}
