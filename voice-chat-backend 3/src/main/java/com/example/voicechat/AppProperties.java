package com.example.voicechat;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(List<String> corsOrigins, Duration tokenTtl, int contextRounds,
                            int contextMaxChars, Deepseek deepseek) {
    public record Deepseek(String baseUrl, String apiKey, String model, Duration timeout, boolean jsonMode) {
        @Override public String toString() { return "Deepseek[model=" + model + ", apiKey=REDACTED]"; }
    }
}
