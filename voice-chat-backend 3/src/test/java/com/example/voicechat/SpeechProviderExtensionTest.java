package com.example.voicechat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class SpeechProviderExtensionTest {
    @Test void realProvidersReplaceBrowserFallbackWithoutChangingControllers() {
        new ApplicationContextRunner().withBean(SpeechRecognitionProvider.class, () -> new SpeechRecognitionProvider() {
            public String name() { return "test-recognition"; }
            public boolean available() { return true; }
            public Transcription transcribe(AudioInput input) { return new Transcription("识别结果", input.language()); }
        }).withBean(SpeechSynthesisProvider.class, () -> new SpeechSynthesisProvider() {
            public String name() { return "test-synthesis"; }
            public boolean available() { return true; }
            public AudioOutput synthesize(SynthesisInput input) { return new AudioOutput(new byte[]{1, 2, 3}, "audio/wav"); }
        }).withUserConfiguration(SpeechConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(SpeechRecognitionProvider.class).hasSingleBean(SpeechSynthesisProvider.class);
            var controller = new SpeechController(context.getBean(SpeechRecognitionProvider.class), context.getBean(SpeechSynthesisProvider.class));
            assertThat(controller.capabilities().recognition().mode()).isEqualTo("backend");
            assertThat(controller.capabilities().synthesis().backendAvailable()).isTrue();
            assertThat(controller.synthesize(new SpeechController.SynthesisRequest("你好", null, null)).getBody()).containsExactly(1, 2, 3);
        });
    }
}
