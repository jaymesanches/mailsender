CREATE TABLE emails (
    id uuid PRIMARY KEY,
    recipient VARCHAR(255) NOT NULL,
    subject VARCHAR(255),
    body TEXT,
    is_html BOOLEAN,
    status VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE,
    sent_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE email_attachments (
    id uuid PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255),
    storage_path VARCHAR(255),
    email_id uuid,
    CONSTRAINT fk_email_attachments_email_id FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE
);
