package com.example.voicechat;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekChatProvider implements ChatProvider {
    private static final String SYSTEM = """
        你是网页智能对话助手。默认用中文，以简洁、自然、友善的方式回答，通常1至3句话。
        根据同一会话的上下文理解代词和连续提问。信息足够时直接回答；信息不足时提出一个必要的追问。
        不要每轮机械追问，不编造不确定的事实。使用适合朗读的纯文本，避免Markdown格式。
        必须只输出一个JSON对象：{"reply":"主要回答","followUp":null}。
        如确有必要追问，followUp填写一个简短问题，否则为null；reply不得为空，不要在reply里重复followUp。
        """;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    DeepSeekChatProvider(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties; this.mapper = mapper;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public Reply generate(List<Turn> history, String userMessage) {
        var config = properties.deepseek();
        if (config.apiKey() == null || config.apiKey().isBlank())
            throw new ApiException(503, "MODEL_NOT_CONFIGURED", "尚未配置对话服务密钥");
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SYSTEM));
            history.forEach(t -> messages.add(Map.of("role", t.role(), "content", t.content())));
            messages.add(Map.of("role", "user", "content", userMessage));
            Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "model", config.model(), "messages", messages, "stream", false,
                "temperature", 0.5, "max_tokens", 1024));
            if (config.jsonMode()) body.put("response_format", Map.of("type", "json_object"));
            byte[] payload = mapper.writeValueAsBytes(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(config.timeout()).header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            HttpResponse<String> response = sendWithReconnect(request);
            int status = response.statusCode();
            if (status == 429) throw new ApiException(429, "MODEL_RATE_LIMITED", "对话服务繁忙，请稍后重试");
            if (status == 401 || status == 403) throw new ApiException(503, "MODEL_AUTH_FAILED", "对话服务认证失败，请联系管理员");
            if (status < 200 || status >= 300)
                throw new ApiException(502, "MODEL_UPSTREAM_ERROR", "对话服务暂时不可用");
            JsonNode root = mapper.readTree(response.body());
            if (root == null) throw invalid();
            JsonNode choice = root.path("choices").path(0);
            if (!choice.path("finish_reason").asText().equals("stop")) throw invalid();
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual()) throw invalid();
            return parseAnswer(content.textValue());
        } catch (HttpTimeoutException e) {
            throw new ApiException(504, "MODEL_TIMEOUT", "回答生成超时，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "MODEL_INTERRUPTED", "回答生成已中断，请重试");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid();
        } catch (IOException e) {
            throw new ApiException(502, "MODEL_CONNECTION_FAILED", "无法连接对话服务，请稍后重试");
        } catch (IllegalArgumentException e) {
            throw new ApiException(503, "MODEL_CONFIGURATION_ERROR", "对话服务配置无效，请联系管理员");
        }
    }
    private HttpResponse<String> sendWithReconnect(HttpRequest request) throws IOException, InterruptedException {
        var handler = HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8);
        try {
            return client.send(request, handler);
        } catch (IOException first) {
            if (first instanceof HttpTimeoutException || first instanceof javax.net.ssl.SSLPeerUnverifiedException) throw first;
            for (Throwable cause = first; cause != null; cause = cause.getCause())
                if (cause instanceof java.security.cert.CertificateException) throw first;
            var logger = org.slf4j.LoggerFactory.getLogger(DeepSeekChatProvider.class);
            logger.warn("Model connection interrupted ({}); reconnecting once", first.getClass().getSimpleName());
            Thread.sleep(400);
            var freshClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
            try {
                return freshClient.send(request, handler);
            } finally {
                freshClient.close();
            }
        }
    }
    private Reply parseAnswer(String raw) throws com.fasterxml.jackson.core.JsonProcessingException {
            String text = raw.strip();
            if (text.startsWith("```")) {
                var fence = java.util.regex.Pattern.compile("(?s)^```(?:json)?\\s*\\n?(.*?)\\s*```$").matcher(text);
                if (fence.matches()) text = fence.group(1).strip();
            }
            if (text.isBlank() || text.length() > 7000 || text.equals("null")) throw invalid();
            // JSON mode can return whitespace on some requests. Use ordinary output by default,
            // accepting either the requested JSON object or a genuine plain-text answer.
            if (!text.startsWith("{") && !text.startsWith("[")) {
                if (text.length() > 6000) throw invalid();
                return new Reply(text, null);
            }
            JsonNode object = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (object == null) throw invalid();
            JsonNode reply = object.path("reply"), followUp = object.path("followUp");
            if (!object.isObject() || !reply.isTextual() || reply.asText().isBlank()
                || reply.asText().length() > 6000
                || !(followUp.isMissingNode() || followUp.isNull() || followUp.isTextual())
                || followUp.asText("").length() > 1000) throw invalid();
            String question = followUp.isMissingNode() || followUp.isNull() || followUp.asText().isBlank() ? null : followUp.asText().strip();
            return new Reply(reply.asText().strip(), question);
    }
    private ApiException invalid() { return new ApiException(502, "MODEL_INVALID_RESPONSE", "对话服务返回格式异常，请重试"); }
}
