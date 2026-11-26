CREATE TABLE memos (
    user_id VARCHAR(36) NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    id VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    time_text TEXT NOT NULL,
    details TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    schedule_anchor TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, id)
);
CREATE INDEX idx_memos_user_created ON memos(user_id, deleted, created_at);
