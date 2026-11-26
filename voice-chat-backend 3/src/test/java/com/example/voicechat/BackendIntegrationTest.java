package com.example.voicechat;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1", "app.deepseek.api-key="})
@AutoConfigureMockMvc
@Import(BackendIntegrationTest.Fakes.class)
class BackendIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired TokenRepository tokens;
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;
    @Autowired ConversationStore store;
    @Autowired ConversationService service;
    @Autowired FakeChat fake;
    @Autowired PasswordEncoder passwords;

    @TestConfiguration static class Fakes {
        @Bean @Primary FakeChat fakeChat() { return new FakeChat(); }
    }
    static class FakeChat implements ChatProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile List<Turn> history = List.of();
        volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        public Reply generate(List<Turn> history, String content) {
            calls.incrementAndGet(); this.history = history;
            if (content.equals("FAIL")) throw new ApiException(504, "MODEL_TIMEOUT", "回答生成超时，请稍后重试");
            if (content.equals("FAIL_SAVE")) return new Reply(null, null);
            if (content.equals("BLOCK")) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }
            return new Reply("你好，我会帮你。", content.equals("需要帮助") ? "你想先了解哪方面？" : null);
        }
        void reset() { calls.set(0); history = List.of(); entered = new CountDownLatch(1); release = new CountDownLatch(1); }
    }
    @BeforeEach void clear() {
        messages.deleteAll(); conversations.deleteAll(); tokens.deleteAll(); users.deleteAll(); fake.reset();
    }
    String login(String username) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", username, "password", "password123"))))
            .andExpect(status().isCreated());
        return body(mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", username, "password", "password123")))))
            .path("accessToken").asText();
    }
    String create(String token) throws Exception {
        return body(mvc.perform(post("/api/conversations").header("Authorization", "Bearer " + token)
            .contentType("application/json").content("{}"))).path("id").asText();
    }
    ResultActions send(String token, String id, String content) throws Exception {
        return mvc.perform(post("/api/conversations/" + id + "/messages").header("Authorization", "Bearer " + token)
            .contentType("application/json").content(json.writeValueAsString(Map.of("content", content, "inputSource", "speech"))));
    }
    JsonNode body(ResultActions action) throws Exception {
        return json.readTree(action.andReturn().getResponse().getContentAsByteArray());
    }

    @Test void authenticationStoresOnlyHashesAndLogoutRevokes() throws Exception {
        String token = login("Alice");
        assertThat(token).hasSize(43);
        assertThat(users.findByUsername("alice")).isPresent();
        var user = users.findByUsername("alice").orElseThrow();
        assertThat(user.passwordHash).isNotEqualTo("password123");
        assertThat(passwords.matches("password123", user.passwordHash)).isTrue();
        assertThat(tokens.findAll().getFirst().tokenHash).isEqualTo(AuthService.hash(token)).isNotEqualTo(token);
        mvc.perform(post("/api/auth/register").contentType("application/json")
            .content("{\"username\":\"ALICE\",\"password\":\"password123\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));
        mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"username\":\"alice\",\"password\":\"wrongpass\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/conversations").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    @Test void expiryAndMissingCredentialsAreRejectedWithRequestId() throws Exception {
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized())
            .andExpect(header().exists("X-Request-ID")).andExpect(jsonPath("$.requestId").isNotEmpty());
        String token = login("expired");
        var row = tokens.findAll().getFirst(); row.expiresAt = Instant.now().minusSeconds(1); tokens.saveAndFlush(row);
        mvc.perform(get("/api/conversations").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    @Test void usersCannotReadWriteOrDeleteEachOthersConversations() throws Exception {
        String a = login("alice"), b = login("bob"), id = create(a);
        send(b, id, "你好").andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations/" + id + "/messages").header("Authorization", "Bearer " + b))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/conversations/" + id).header("Authorization", "Bearer " + b)).andExpect(status().isNotFound());
        assertThat(fake.calls).hasValue(0);
        mvc.perform(get("/api/conversations").header("Authorization", "Bearer " + b))
            .andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void memoRetriesPreserveEditsAndDeletionAndRespectOwnership() throws Exception {
        String a = "Bearer " + login("memoOwner"), b = "Bearer " + login("otherMemoUser");
        String path = "/api/memos", id = "/legacy-1";
        String date = "2026-11-26T01:00:00Z";
        var fields = Map.of("text","社区活动","when","周五九点","details","带水杯","scheduleAnchor",date);
        String create = json.writeValueAsString(Map.of("id","legacy-1","created",date,"fields",fields));
        var editFields = new HashMap<>(fields);
        editFields.put("when", "周五十点");
        String edit = json.writeValueAsString(Map.of("revision",1,"fields",editFields));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization",a).contentType("application/json").content(create))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get(path).header("Authorization",b)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(put(path+id).header("Authorization",b).contentType("application/json").content(edit))
            .andExpect(status().isNotFound());
        mvc.perform(delete(path+id+"?revision=1").header("Authorization",b)).andExpect(status().isNotFound());
        mvc.perform(put(path+id).header("Authorization",a).contentType("application/json").content(edit))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(put(path+id).header("Authorization",a).contentType("application/json").content(edit))
            .andExpect(status().isConflict());
        mvc.perform(post(path).header("Authorization",a).contentType("application/json").content(create))
            .andExpect(status().isOk()).andExpect(jsonPath("$.when").value("周五十点"));
        mvc.perform(delete(path+id+"?revision=1").header("Authorization",a)).andExpect(status().isConflict());
        mvc.perform(delete(path+id+"?revision=2").header("Authorization",a)).andExpect(status().isNoContent());
        mvc.perform(post(path).header("Authorization",a).contentType("application/json").content(create))
            .andExpect(status().isGone());
        mvc.perform(get(path).header("Authorization",a)).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void speechInputFollowUpAndHistoryAreConsistent() throws Exception {
        String token = login("alice"), id = create(token);
        JsonNode first = body(send(token, id, "需要帮助").andExpect(status().isOk()));
        assertThat(first.path("speakText").asText()).isEqualTo("你好，我会帮你。\n你想先了解哪方面？");
        assertThat(first.path("userMessageId").asLong()).isLessThan(first.path("assistantMessageId").asLong());
        send(token, id, "学习Java").andExpect(status().isOk()).andExpect(jsonPath("$.followUp").isEmpty());
        assertThat(fake.history).containsExactly(new ChatProvider.Turn("user", "需要帮助"),
            new ChatProvider.Turn("assistant", "你好，我会帮你。\n你想先了解哪方面？"));
        mvc.perform(get("/api/conversations/" + id + "/messages?page=0&size=2").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4))
            .andExpect(jsonPath("$.items[0].inputSource").value("speech"))
            .andExpect(jsonPath("$.items[1].followUp").value("你想先了解哪方面？"));
        String other = create(token);
        send(token, other, "另一个会话").andExpect(status().isOk());
        assertThat(fake.history).isEmpty();
    }
    @Test void retryReturnsTheOriginalRoundAndNewIntentsCanRepeatTheSameText() throws Exception {
        String token = login("retryUser");
        String url = "/api/conversations/" + create(token) + "/messages";
        var payload = new Api.SendMessage("需要帮助", "speech", "first");
        var request = post(url).header("Authorization", "Bearer " + token).contentType("application/json");
        JsonNode original = body(mvc.perform(request.content(json.writeValueAsString(payload))).andExpect(status().isOk()));
        JsonNode replay = body(mvc.perform(request.content(json.writeValueAsString(payload))).andExpect(status().isOk()));
        assertThat(replay).isEqualTo(original);
        assertThat(fake.calls).hasValue(1);
        mvc.perform(request.content(json.writeValueAsString(new Api.SendMessage("改过的文字","speech","first"))))
            .andExpect(status().isConflict());
        mvc.perform(request.content(json.writeValueAsString(new Api.SendMessage("需要帮助","speech","second"))))
            .andExpect(status().isOk());
        assertThat(fake.calls).hasValue(2);
        assertThat(messages.count()).isEqualTo(4);
    }
    @Test void failedModelAndDatabaseWritesNeverLeaveHalfARound() throws Exception {
        String token = login("alice"), id = create(token);
        send(token, id, "FAIL").andExpect(status().isGatewayTimeout());
        assertThat(messages.count()).isZero();
        send(token, id, "FAIL_SAVE").andExpect(status().isInternalServerError());
        assertThat(messages.count()).isZero();
        send(token, id, "重试").andExpect(status().isOk());
        assertThat(messages.count()).isEqualTo(2);
    }
    @Test void sameConversationRejectsConcurrentGenerationAndDeletion() throws Exception {
        String token = login("alice"), id = create(token);
        String userId = users.findByUsername("alice").orElseThrow().id;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Api.ChatResponse> task = executor.submit(() -> service.send(userId, id, new Api.SendMessage("BLOCK", "text")));
            try {
                assertThat(fake.entered.await(5, TimeUnit.SECONDS)).isTrue();
                send(token, id, "第二个请求").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONVERSATION_BUSY"));
                mvc.perform(delete("/api/conversations/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
                send(token, create(token), "另一个会话可以继续").andExpect(status().isOk());
            } finally { fake.release.countDown(); }
            assertThat(task.get(5, TimeUnit.SECONDS).reply()).isNotBlank();
        }
        mvc.perform(delete("/api/conversations/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        assertThat(messages.count()).isEqualTo(2); // Only the other conversation remains.
    }
    @Test void contextUsesAtMostTwentyCompleteRounds() throws Exception {
        String token = login("alice"), id = create(token), userId = users.findByUsername("alice").orElseThrow().id;
        for (int i = 0; i < 25; i++) store.saveRound(userId, id, new Api.SendMessage("question" + i, "text"), new ChatProvider.Reply("answer" + i, null));
        send(token, id, "继续").andExpect(status().isOk());
        assertThat(fake.history).hasSize(40);
        assertThat(fake.history.getFirst().content()).isEqualTo("question5");
        assertThat(messages.count()).isEqualTo(52);
    }
    @Test void contextCharacterBudgetPreservesWholePairsAndCurrentInput() throws Exception {
        String token = login("alice"), id = create(token), userId = users.findByUsername("alice").orElseThrow().id;
        for (int i = 0; i < 8; i++) store.saveRound(userId, id, new Api.SendMessage("问".repeat(2000), "text"), new ChatProvider.Reply("答".repeat(2000), null));
        send(token, id, "继续").andExpect(status().isOk());
        assertThat(fake.history).hasSize(6);
        assertThat(fake.history.stream().mapToInt(t -> t.content().length()).sum() + 2).isLessThanOrEqualTo(16000);
    }
    @Test void invalidInputPaginationAndCredentialsAreRejected() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
            .content("{\"username\":\"a\",\"password\":\"short\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", "unicode", "password", "密".repeat(30)))))
            .andExpect(status().isBadRequest());
        String token = login("alice"), id = create(token);
        send(token, id, " ").andExpect(status().isBadRequest());
        send(token, id, "a".repeat(4001)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversations?size=101").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversations?page=-1").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        assertThat(fake.calls).hasValue(0);
    }
    @Test void capabilitiesAndDisabledSpeechEndpointsAreExplicit() throws Exception {
        mvc.perform(get("/api/capabilities")).andExpect(status().isOk())
            .andExpect(jsonPath("$.recognition.mode").value("browser"))
            .andExpect(jsonPath("$.recognition.backendAvailable").value(false))
            .andExpect(jsonPath("$.synthesis.mode").value("browser"));
        String token = login("alice");
        mvc.perform(multipart("/api/speech/transcriptions").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("SPEECH_PROVIDER_NOT_CONFIGURED"));
        mvc.perform(post("/api/speech/synthesis").header("Authorization", "Bearer " + token)
            .contentType("application/json").content("{\"text\":\"你好\"}"))
            .andExpect(status().isNotImplemented());
    }
    @Test void openApiAndCorsAreAvailableForWebIntegration() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/conversations/{id}/messages'].post").exists());
        mvc.perform(options("/api/conversations").header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/conversations").header("Origin", "https://untrusted.example")
            .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }
}
