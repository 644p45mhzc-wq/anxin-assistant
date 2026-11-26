package com.example.voicechat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VoiceChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(VoiceChatApplication.class, args);
    }
}
