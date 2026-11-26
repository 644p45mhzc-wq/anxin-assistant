package com.example.voicechat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class SpeechController {
    static final Set<String> AUDIO_TYPES = Set.of("audio/webm", "audio/ogg", "audio/wav", "audio/x-wav",
        "audio/mpeg", "audio/mp4", "audio/flac", "audio/x-flac");
    static final long MAX_BYTES = 10 * 1024 * 1024;
    private final SpeechRecognitionProvider recognition;
    private final SpeechSynthesisProvider synthesis;
    SpeechController(SpeechRecognitionProvider recognition, SpeechSynthesisProvider synthesis) {
        this.recognition = recognition; this.synthesis = synthesis;
    }
    public record SpeechCapability(String mode, boolean backendAvailable, String provider) {}
    public record PlaybackCapability(boolean pause, boolean resume, boolean stop) {}
    public record Limits(int maxInputCharacters, long maxAudioBytes, List<String> acceptedAudioTypes) {}
    public record Capabilities(SpeechCapability recognition, SpeechCapability synthesis,
                               PlaybackCapability browserPlayback, Limits limits) {}
    public record SynthesisRequest(@NotBlank @Size(max = 4000) String text,
        @Size(max = 100) String voice, @DecimalMin("0.5") @DecimalMax("2.0") Double speed) {}

    @GetMapping("/api/capabilities") @SecurityRequirements @Operation(summary = "查询浏览器和后端语音能力")
    public Capabilities capabilities() {
        return new Capabilities(capability(recognition.available(), recognition.name()),
            capability(synthesis.available(), synthesis.name()), new PlaybackCapability(true, true, true),
            new Limits(4000, MAX_BYTES, AUDIO_TYPES.stream().sorted().toList()));
    }
    private SpeechCapability capability(boolean available, String name) {
        return new SpeechCapability(available ? "backend" : "browser", available, available ? name : null);
    }
    @PostMapping(value = "/api/speech/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传音频并转写；未配置语音服务时返回501")
    public SpeechRecognitionProvider.Transcription transcribe(
        @RequestPart(value = "file", required = false) MultipartFile file,
        @RequestParam(defaultValue = "zh-CN") @Size(max = 35) String language) throws IOException {
        if (!recognition.available()) throw SpeechConfiguration.notConfigured();
        if (file == null || file.isEmpty()) throw new ApiException(400, "AUDIO_REQUIRED", "请上传非空音频文件");
        if (file.getSize() > MAX_BYTES) throw new ApiException(413, "UPLOAD_TOO_LARGE", "音频文件不能超过10MB");
        String type = Optional.ofNullable(file.getContentType()).orElse("").split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!AUDIO_TYPES.contains(type)) throw new ApiException(415, "UNSUPPORTED_AUDIO_TYPE", "音频格式不受支持");
        return recognition.transcribe(new SpeechRecognitionProvider.AudioInput(file.getBytes(), type,
            file.getOriginalFilename(), language));
    }
    @PostMapping(value = "/api/speech/synthesis", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "合成朗读音频；未配置语音服务时返回501")
    public ResponseEntity<byte[]> synthesize(@Valid @RequestBody SynthesisRequest request) {
        if (!synthesis.available()) throw SpeechConfiguration.notConfigured();
        var audio = synthesis.synthesize(new SpeechSynthesisProvider.SynthesisInput(request.text(), request.voice(),
            request.speed() == null ? 1.0 : request.speed()));
        MediaType type = MediaType.parseMediaType(audio.mediaType());
        if (!type.getType().equals("audio") || audio.bytes() == null || audio.bytes().length == 0)
            throw new ApiException(502, "SPEECH_INVALID_RESPONSE", "语音服务返回格式异常");
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).body(audio.bytes());
    }
    @GetMapping("/api/health") @SecurityRequirements @Operation(summary = "服务存活检查，不调用外部模型")
    public Map<String, String> health() { return Map.of("status", "UP"); }
}
