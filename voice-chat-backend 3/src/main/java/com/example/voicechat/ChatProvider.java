package com.example.voicechat;

import java.util.List;

/** Provider-neutral text conversation boundary. No audio or persistence dependencies. */
public interface ChatProvider {
    Reply generate(List<Turn> history, String userMessage);
    record Turn(String role, String content) {}
    record Reply(String reply, String followUp) {}
}
