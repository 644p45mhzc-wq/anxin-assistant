package com.example.voicechat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Single-instance coordination; active keys are always removed, including on provider failure. */
@Component
public class ConversationLocks {
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    public <T> T exclusive(String id, Supplier<T> action) {
        if (!active.add(id)) throw new ApiException(409, "CONVERSATION_BUSY", "该会话正在处理请求，请稍后重试");
        try { return action.get(); } finally { active.remove(id); }
    }
}
