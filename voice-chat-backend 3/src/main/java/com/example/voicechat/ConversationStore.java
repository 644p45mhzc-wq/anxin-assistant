package com.example.voicechat;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.voicechat.Domain.*;

/** Short database transactions only; no network requests while holding a database transaction. */
@Service
public class ConversationStore {
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final AppProperties properties;
    ConversationStore(ConversationRepository conversations, MessageRepository messages, AppProperties properties) {
        this.conversations = conversations; this.messages = messages; this.properties = properties;
    }
    @Transactional
    public Api.ConversationView create(String userId, String title) {
        return view(conversations.save(new Conversation(userId,
            title == null || title.isBlank() ? "新对话" : title.strip())));
    }
    @Transactional
    public Api.ConversationView rename(String userId, String id, String title) {
        Conversation conversation = owned(userId, id);
        conversation.title = title.strip();
        conversation.updatedAt = Instant.now();
        return view(conversation);
    }
    @Transactional(readOnly = true)
    public Api.PageView<Api.ConversationView> list(String userId, int page, int size) {
        return Api.PageView.of(conversations.findByUserIdOrderByUpdatedAtDescIdDesc(userId, PageRequest.of(page, size)).map(this::view));
    }
    @Transactional(readOnly = true)
    public void checkOwner(String userId, String conversationId) { owned(userId, conversationId); }
    @Transactional(readOnly = true)
    public Api.PageView<Api.MessageView> history(String userId, String conversationId, int page, int size) {
        owned(userId, conversationId);
        return Api.PageView.of(messages.findByConversationIdOrderByIdAsc(conversationId, PageRequest.of(page, size)).map(this::view));
    }
    @Transactional(readOnly = true)
    public List<ChatProvider.Turn> context(String userId, String conversationId, String input) {
        owned(userId, conversationId);
        var recent = new ArrayList<>(messages.findByConversationIdOrderByIdDesc(conversationId,
            PageRequest.of(0, properties.contextRounds() * 2)).getContent());
        Collections.reverse(recent);
        Deque<ChatProvider.Turn> selected = new ArrayDeque<>();
        int remaining = properties.contextMaxChars() - input.length();
        // Retain only complete newest user/assistant pairs; never cut a message in half.
        for (int i = recent.size() - 2; i >= 0; i -= 2) {
            Message user = recent.get(i), assistant = recent.get(i + 1);
            if (!user.role.equals("user") || !assistant.role.equals("assistant")) break;
            String answer = SpeechText.of(assistant.content, assistant.followUp);
            int length = user.content.length() + answer.length();
            if (length > remaining) break;
            remaining -= length;
            selected.addFirst(new ChatProvider.Turn("assistant", answer));
            selected.addFirst(new ChatProvider.Turn("user", user.content));
        }
        return List.copyOf(selected);
    }
    @Transactional(readOnly = true)
    public Optional<Api.ChatResponse> savedRound(String conversationId, String retryKey, Api.SendMessage input) {
        if (retryKey == null) return Optional.empty();
        var pair = messages.findByConversationIdAndRetryKeyOrderByIdAsc(conversationId, retryKey);
        if (pair.isEmpty()) return Optional.empty();
        Message user = pair.getFirst();
        String source = input.inputSource() == null ? "text" : input.inputSource();
        if (!user.content.equals(input.content().strip()) || !user.inputSource.equals(source)) {
            throw new ApiException(409, "MESSAGE_CHANGED", "这次发送的文字已改变，请重新确认。");
        }
        return Optional.of(response(user, pair.getLast()));
    }
    @Transactional
    public Api.ChatResponse saveRound(String userId, String conversationId, Api.SendMessage input, ChatProvider.Reply answer) {
        return saveRound(userId, conversationId, input, answer, null);
    }
    @Transactional
    public Api.ChatResponse saveRound(String userId, String conversationId, Api.SendMessage input,
                                      ChatProvider.Reply answer, String retryKey) {
        Conversation conversation = owned(userId, conversationId);
        Message user = messages.save(new Message(conversationId, "user", input.content().strip(), null,
            input.inputSource() == null ? "text" : input.inputSource(), retryKey));
        Message assistant = messages.save(new Message(conversationId, "assistant", answer.reply(), answer.followUp(), null, retryKey));
        conversation.updatedAt = assistant.createdAt;
        if (conversation.title.equals("新对话")) {
            String text = input.content().strip();
            conversation.title = text.substring(0, text.offsetByCodePoints(0, Math.min(40, text.codePointCount(0, text.length()))));
        }
        return response(user, assistant);
    }
    private Api.ChatResponse response(Message user, Message assistant) {
        return new Api.ChatResponse(user.conversationId, user.id, assistant.id, assistant.content, assistant.followUp,
            SpeechText.of(assistant.content, assistant.followUp), assistant.createdAt);
    }
    @Transactional
    public void delete(String userId, String conversationId) { conversations.delete(owned(userId, conversationId)); }
    private Conversation owned(String userId, String id) {
        return conversations.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ApiException(404, "CONVERSATION_NOT_FOUND", "会话不存在或无权访问"));
    }
    private Api.ConversationView view(Conversation c) { return new Api.ConversationView(c.id, c.title, c.createdAt, c.updatedAt); }
    private Api.MessageView view(Message m) {
        return new Api.MessageView(m.id, m.role, m.content, m.followUp,
            m.role.equals("assistant") ? SpeechText.of(m.content, m.followUp) : null, m.inputSource, m.createdAt);
    }
}
