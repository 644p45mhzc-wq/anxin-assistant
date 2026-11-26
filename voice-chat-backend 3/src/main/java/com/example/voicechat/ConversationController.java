package com.example.voicechat;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationStore store;
    private final ConversationService conversations;
    ConversationController(ConversationStore store, ConversationService conversations) {
        this.store = store; this.conversations = conversations;
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Operation(summary = "创建当前用户的独立会话")
    public Api.ConversationView create(@AuthenticationPrincipal AccountPrincipal user,
                                       @Valid @RequestBody(required = false) Api.CreateConversation request) {
        return store.create(user.userId(), request == null ? null : request.title());
    }
    @PostMapping("/{id}/title") @Operation(summary = "修改本人会话的名称")
    public Api.ConversationView rename(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id,
                                       @Valid @RequestBody Api.RenameConversation request) {
        return store.rename(user.userId(), id, request.title());
    }
    @GetMapping @Operation(summary = "按更新时间倒序分页查询会话")
    public Api.PageView<Api.ConversationView> list(@AuthenticationPrincipal AccountPrincipal user,
        @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return store.list(user.userId(), page, size);
    }
    @GetMapping("/{id}/messages") @Operation(summary = "按消息顺序正序分页查询历史")
    public Api.PageView<Api.MessageView> history(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id,
        @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return store.history(user.userId(), id, page, size);
    }
    @PostMapping("/{id}/messages") @Operation(summary = "发送文字或语音识别后的文字，返回回答和可选追问")
    public Api.ChatResponse send(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id,
                                 @Valid @RequestBody Api.SendMessage request) {
        return conversations.send(user.userId(), id, request);
    }
    @PostMapping("/{id}/memo-suggestions")
    public Api.ChatResponse suggestMemo(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id,
                                        @Valid @RequestBody Api.SendMessage request) {
        return conversations.suggestMemo(user.userId(), id, request);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary = "删除会话及全部消息")
    public void delete(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id) {
        conversations.delete(user.userId(), id);
    }
}
