ALTER TABLE messages ADD COLUMN retry_key VARCHAR(72);
CREATE UNIQUE INDEX idx_messages_retry ON messages(conversation_id, retry_key, role);
