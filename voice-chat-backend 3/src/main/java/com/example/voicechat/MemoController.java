package com.example.voicechat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/memos")
public class MemoController {
    public record Fields(@NotBlank String text, @NotNull String when, @NotNull String details,
                         @NotNull Instant scheduleAnchor) {}
    public record Create(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String id,
                         @NotNull Instant created, @Valid @NotNull Fields fields) {}
    public record Edit(@Positive long revision, @Valid @NotNull Fields fields) {}
    public record Memo(String id, String text, String when, String details, Instant created,
                       Instant scheduleAnchor, long revision) {}

    private final JdbcTemplate db;
    MemoController(JdbcTemplate db) { this.db = db; }

    private static Memo read(ResultSet row, int index) throws SQLException {
        return new Memo(row.getString("id"), row.getString("content"), row.getString("time_text"),
            row.getString("details"), row.getObject("created_at", OffsetDateTime.class).toInstant(),
            row.getObject("schedule_anchor", OffsetDateTime.class).toInstant(), row.getLong("revision"));
    }

    @GetMapping
    public List<Memo> list(@AuthenticationPrincipal AccountPrincipal user) {
        return db.query("SELECT * FROM memos WHERE user_id=? AND NOT deleted ORDER BY created_at DESC, id DESC",
            MemoController::read, user.userId());
    }

    @PostMapping
    @Transactional
    public Memo create(@AuthenticationPrincipal AccountPrincipal user, @Valid @RequestBody Create request) {
        // Serialize first inserts for an account, including retries from different tabs.
        String owner = user.userId();
        db.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE", String.class, owner);
        var previous = db.queryForList("SELECT deleted FROM memos WHERE user_id=? AND id=?", Boolean.class, owner, request.id());
        if (previous.isEmpty()) {
            Fields f = request.fields();
            db.update("INSERT INTO memos(user_id,id,content,time_text,details,created_at,schedule_anchor) VALUES(?,?,?,?,?,?,?)",
                owner, request.id(), f.text(), f.when(), f.details(), request.created(), f.scheduleAnchor());
        } else if (previous.get(0)) {
            throw new ApiException(410, "MEMO_DELETED", "这条备忘已删除。");
        }
        return current(owner, request.id());
    }

    private Memo current(String owner, String id) {
        return db.query("SELECT * FROM memos WHERE user_id=? AND id=? AND NOT deleted",
            MemoController::read, owner, id).stream().findFirst()
            .orElseThrow(() -> new ApiException(404, "MEMO_NOT_FOUND", "找不到这条备忘，可能已删除。"));
    }

    private void checkUpdated(int count, String owner, String id) {
        if (count == 0) {
            current(owner, id);
            throw new ApiException(409, "MEMO_CHANGED", "这条备忘已在别处修改，请核对最新内容。");
        }
    }

    @PutMapping("/{id}")
    @Transactional
    public Memo edit(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id, @Valid @RequestBody Edit request) {
        Fields f = request.fields();
        int count = db.update("UPDATE memos SET content=?, time_text=?, details=?, schedule_anchor=?, revision=revision+1 " +
            "WHERE user_id=? AND id=? AND revision=? AND NOT deleted",
            f.text(), f.when(), f.details(), f.scheduleAnchor(), user.userId(), id, request.revision());
        checkUpdated(count, user.userId(), id);
        return current(user.userId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal AccountPrincipal user, @PathVariable String id, @RequestParam @Positive long revision) {
        // Keep the key only for replay protection. Old imports must never restore deleted content.
        int count = db.update("UPDATE memos SET deleted=TRUE, content='', time_text='', details='', revision=revision+1 " +
            "WHERE user_id=? AND id=? AND revision=? AND NOT deleted", user.userId(), id, revision);
        checkUpdated(count, user.userId(), id);
    }
}
