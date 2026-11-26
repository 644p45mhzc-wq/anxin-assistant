package com.example.voicechat;

/** Return audio bytes and a valid audio/* media type. Playback controls remain in the browser. */
public interface SpeechSynthesisProvider {
    String name();
    boolean available();
    AudioOutput synthesize(SynthesisInput input);
    record SynthesisInput(String text, String voice, double speed) {}
    record AudioOutput(byte[] bytes, String mediaType) {}
}
