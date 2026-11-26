package com.example.voicechat;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public final class Api {
    private Api() {}
    public record Credentials(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "用户名须为3至32位字母、数字或下划线") String username,
        @NotBlank @Size(min = 8, max = 64) String password) {}
    public record UserView(String id, String username, Instant createdAt) {}
    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, UserView user) {}
    public record CreateConversation(@Size(max = 100) String title) {}
    public record RenameConversation(@NotBlank @Size(max = 100) String title) {}
    public record ConversationView(String id, String title, Instant createdAt, Instant updatedAt) {}
    public record SendMessage(@NotBlank @Size(max = 4000) String content,
                              @Pattern(regexp = "text|speech") String inputSource,
                              @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String clientMessageId) {
        public SendMessage(String content, String inputSource) { this(content, inputSource, null); }
    }
    public record MessageView(Long id, String role, String content, String followUp,
                              String speakText, String inputSource, Instant createdAt) {}
    public record ChatResponse(String conversationId, Long userMessageId, Long assistantMessageId,
                               String reply, String followUp, String speakText, Instant createdAt) {}
    public record PageView<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
        public static <T> PageView<T> of(Page<T> page) {
            return new PageView<>(page.getContent(), page.getNumber(), page.getSize(),
                                  page.getTotalElements(), page.getTotalPages());
        }
    }
    public record ErrorResponse(String code, String message, String requestId) {}
}
