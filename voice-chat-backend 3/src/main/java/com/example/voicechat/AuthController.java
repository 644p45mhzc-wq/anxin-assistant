package com.example.voicechat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED) @SecurityRequirements
    @Operation(summary = "注册：用户名大小写不敏感；密码8至64字符且UTF-8不超过72字节")
    public Api.UserView register(@Valid @RequestBody Api.Credentials request) { return auth.register(request); }
    @PostMapping("/login") @SecurityRequirements
    @Operation(summary = "登录并获得24小时有效的Bearer Token")
    public Api.LoginResponse login(@Valid @RequestBody Api.Credentials request) { return auth.login(request); }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "使当前登录凭证立即失效")
    public void logout(@AuthenticationPrincipal AccountPrincipal principal) { auth.logout(principal); }
}
