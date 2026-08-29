ALTER TABLE emails ADD COLUMN attempts INTEGER NOT NULL DEFAULT 1;
ALTER TABLE emails ADD COLUMN last_error VARCHAR(500);

CREATE INDEX idx_emails_retry ON emails (status, attempts);
