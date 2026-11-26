package com.example.voicechat;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import com.example.voicechat.Domain.*;

interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUsername(String username);
}
interface TokenRepository extends JpaRepository<AuthToken, String> {}
interface ConversationRepository extends JpaRepository<Conversation, String> {
    Optional<Conversation> findByIdAndUserId(String id, String userId);
    Page<Conversation> findByUserIdOrderByUpdatedAtDescIdDesc(String userId, Pageable pageable);
}
interface MessageRepository extends JpaRepository<Message, Long> {
    java.util.List<Message> findByConversationIdAndRetryKeyOrderByIdAsc(String conversationId, String retryKey);
    Page<Message> findByConversationIdOrderByIdAsc(String conversationId, Pageable pageable);
    Page<Message> findByConversationIdOrderByIdDesc(String conversationId, Pageable pageable);
}
