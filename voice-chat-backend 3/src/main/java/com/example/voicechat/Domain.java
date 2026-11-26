package com.example.voicechat;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

public final class Domain {
    private Domain() {}

    @Entity @Table(name = "app_users")
    public static class User {
        @Id @Column(length = 36) String id;
        @Column(nullable = false, unique = true, length = 32) String username;
        @Column(name = "password_hash", nullable = false, length = 100) String passwordHash;
        @Column(nullable = false) Instant createdAt;
        protected User() {}
        User(String username, String passwordHash) {
            this.id = UUID.randomUUID().toString(); this.username = username;
            this.passwordHash = passwordHash; this.createdAt = Instant.now();
        }
    }

    @Entity @Table(name = "auth_tokens")
    public static class AuthToken {
        @Id @Column(length = 64) String tokenHash;
        @Column(nullable = false, length = 36) String userId;
        @Column(nullable = false) Instant expiresAt;
        protected AuthToken() {}
        AuthToken(String tokenHash, String userId, Instant expiresAt) {
            this.tokenHash = tokenHash; this.userId = userId; this.expiresAt = expiresAt;
        }
    }

    @Entity @Table(name = "conversations")
    public static class Conversation {
        @Id @Column(length = 36) String id;
        @Column(nullable = false, length = 36) String userId;
        @Column(nullable = false, length = 100) String title;
        @Column(nullable = false) Instant createdAt;
        @Column(nullable = false) Instant updatedAt;
        protected Conversation() {}
        Conversation(String userId, String title) {
            this.id = UUID.randomUUID().toString(); this.userId = userId; this.title = title;
            this.createdAt = Instant.now(); this.updatedAt = this.createdAt;
        }
    }

    @Entity @Table(name = "messages")
    public static class Message {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
        @Column(nullable = false, length = 36) String conversationId;
        @Column(nullable = false, length = 16) String role;
        @Column(nullable = false, columnDefinition = "text") String content;
        @Column(columnDefinition = "text") String followUp;
        @Column(length = 16) String inputSource;
        @Column(nullable = false) Instant createdAt;
        @Column(length = 72) String retryKey;
        protected Message() {}
        Message(String conversationId, String role, String content, String followUp, String inputSource, String retryKey) {
            this.conversationId = conversationId; this.role = role; this.content = content;
            this.followUp = followUp; this.inputSource = inputSource; this.retryKey = retryKey;
            this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        }
    }
}
