package com.example.voicechat;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.voicechat.Domain.*;

record AccountPrincipal(String userId, String username, String tokenHash) {}

@Service
public class AuthService {
    private final UserRepository users;
    private final TokenRepository tokens;
    private final PasswordEncoder passwords;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;
    AuthService(UserRepository users, TokenRepository tokens, PasswordEncoder passwords, AppProperties properties) {
        this.users = users; this.tokens = tokens; this.passwords = passwords; this.properties = properties;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }
    @Transactional
    public Api.UserView register(Api.Credentials request) {
        checkPassword(request.password());
        String username = request.username().toLowerCase(Locale.ROOT);
        if (users.findByUsername(username).isPresent()) throw duplicate();
        try { return view(users.saveAndFlush(new User(username, passwords.encode(request.password())))); }
        catch (DataIntegrityViolationException e) { throw duplicate(); }
    }
    @Transactional
    public Api.LoginResponse login(Api.Credentials request) {
        checkPassword(request.password());
        User user = users.findByUsername(request.username().toLowerCase(Locale.ROOT)).orElse(null);
        boolean valid = passwords.matches(request.password(), user == null ? dummyHash : user.passwordHash);
        if (user == null || !valid) throw new ApiException(401, "INVALID_CREDENTIALS", "用户名或密码错误");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(properties.tokenTtl());
        tokens.save(new AuthToken(hash(raw), user.id, expiresAt));
        return new Api.LoginResponse(raw, "Bearer", expiresAt, view(user));
    }
    @Transactional(readOnly = true)
    public Optional<AccountPrincipal> authenticate(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return tokens.findById(hash(raw)).filter(t -> t.expiresAt.isAfter(Instant.now()))
            .flatMap(t -> users.findById(t.userId).map(u -> new AccountPrincipal(u.id, u.username, t.tokenHash)));
    }
    @Transactional
    public void logout(AccountPrincipal principal) { tokens.deleteById(principal.tokenHash()); }
    private void checkPassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(400, "VALIDATION_ERROR", "密码UTF-8编码长度不能超过72字节");
    }
    private ApiException duplicate() { return new ApiException(409, "USERNAME_EXISTS", "用户名已被使用"); }
    private Api.UserView view(User u) { return new Api.UserView(u.id, u.username, u.createdAt); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
