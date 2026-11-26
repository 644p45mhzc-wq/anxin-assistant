package com.example.voicechat;

public final class SpeechText {
    private SpeechText() {}
    public static String of(String reply, String followUp) {
        String text = reply + (followUp == null || followUp.isBlank() ? "" : "\n" + followUp);
        return text.replaceAll("(?s)```[a-zA-Z0-9_-]*\\n?(.*?)```", "$1")
            .replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1")
            .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
            .replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "")
            .replaceAll("[*`_]", "").strip();
    }
}
