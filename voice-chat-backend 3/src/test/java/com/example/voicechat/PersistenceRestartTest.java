package com.example.voicechat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;

class PersistenceRestartTest {
    @TempDir Path directory;
    @Test void accountsTokensAndMessagesSurviveAnApplicationRestart() {
        String database = "jdbc:h2:file:" + directory.resolve("restart").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        String userId, token, conversationId;
        try (var first = open(database)) {
            var auth = first.getBean(AuthService.class);
            var user = auth.register(new Api.Credentials("persisted", "password123")); userId = user.id();
            token = auth.login(new Api.Credentials("persisted", "password123")).accessToken();
            var store = first.getBean(ConversationStore.class);
            conversationId = store.create(userId, "重启验证").id();
            store.saveRound(userId, conversationId, new Api.SendMessage("你好", "text"), new ChatProvider.Reply("你好。", null));
        }
        try (var second = open(database)) {
            assertThat(second.getBean(AuthService.class).authenticate(token)).isPresent();
            var history = second.getBean(ConversationStore.class).history(userId, conversationId, 0, 20);
            assertThat(history.totalElements()).isEqualTo(2);
            assertThat(history.items().stream().map(Api.MessageView::content).toList()).isEqualTo(List.of("你好", "你好。"));
        }
    }
    ConfigurableApplicationContext open(String database) {
        return new SpringApplicationBuilder(VoiceChatApplication.class).run(
            "--server.port=0", "--spring.datasource.url=" + database, "--app.deepseek.api-key=",
            "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }
}
