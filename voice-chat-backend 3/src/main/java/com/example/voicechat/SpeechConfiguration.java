package com.example.voicechat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration
public class SpeechConfiguration {
    static ApiException notConfigured() {
        return new ApiException(501, "SPEECH_PROVIDER_NOT_CONFIGURED", "尚未配置后端语音服务，请使用浏览器语音能力");
    }
    @Bean @ConditionalOnMissingBean(SpeechRecognitionProvider.class)
    SpeechRecognitionProvider browserRecognition() {
        return new SpeechRecognitionProvider() {
            public String name() { return "browser"; }
            public boolean available() { return false; }
            public Transcription transcribe(AudioInput input) { throw notConfigured(); }
        };
    }
    @Bean @ConditionalOnMissingBean(SpeechSynthesisProvider.class)
    SpeechSynthesisProvider browserSynthesis() {
        return new SpeechSynthesisProvider() {
            public String name() { return "browser"; }
            public boolean available() { return false; }
            public AudioOutput synthesize(SynthesisInput input) { throw notConfigured(); }
        };
    }
}
