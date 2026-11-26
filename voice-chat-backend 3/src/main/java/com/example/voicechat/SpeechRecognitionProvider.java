package com.example.voicechat;

/** Implement this as a Spring bean to enable server-side speech recognition. */
public interface SpeechRecognitionProvider {
    String name();
    boolean available();
    Transcription transcribe(AudioInput input);
    record AudioInput(byte[] bytes, String mediaType, String filename, String language) {}
    record Transcription(String text, String language) {}
}
