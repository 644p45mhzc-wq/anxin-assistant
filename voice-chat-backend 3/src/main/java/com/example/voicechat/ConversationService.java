package com.example.voicechat;

import org.springframework.stereotype.Service;

@Service
public class ConversationService {
    private final ConversationStore store;
    private final ChatProvider chat;
    private final ConversationLocks locks;
    ConversationService(ConversationStore store, ChatProvider chat, ConversationLocks locks) {
        this.store = store; this.chat = chat; this.locks = locks;
    }
    public Api.ChatResponse send(String userId, String id, Api.SendMessage input) {
        return process(userId, id, input, "chat:", () -> {
            var history = store.context(userId, id, input.content());
            return chat.generate(history, input.content().strip());
        });
    }
    public Api.ChatResponse suggestMemo(String userId, String id, Api.SendMessage input) {
        return process(userId, id, input, "memo:", () -> new ChatProvider.Reply("要把这件事记入备忘吗？", null));
    }
    private Api.ChatResponse process(String userId, String id, Api.SendMessage input, String prefix,
                                     java.util.function.Supplier<ChatProvider.Reply> generate) {
        store.checkOwner(userId, id);
        String key = input.clientMessageId() == null ? null : prefix + input.clientMessageId();
        return locks.exclusive(id, () -> store.savedRound(id, key, input)
            .orElseGet(() -> store.saveRound(userId, id, input, generate.get(), key)));
    }
    public void delete(String userId, String id) {
        store.checkOwner(userId, id);
        locks.exclusive(id, () -> { store.delete(userId, id); return null; });
    }
}
